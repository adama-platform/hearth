package io.hearth.content;

import io.hearth.auth.Access;
import io.hearth.auth.UserRecord;
import io.hearth.auth.Users;
import io.hearth.cache.Caches;
import io.hearth.cache.TtlCache;
import io.hearth.calendar.Calendar;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.people.PeopleStore;
import io.hearth.people.ProfileRecord;
import io.hearth.places.Places;
import io.hearth.store.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The community's own pages for what it already holds: the events, the address book, the members.
 *
 * <b>The same three things every listing needs, written once.</b> A community that wants its own
 * front page for what is on had two options before this: the built-in page, which looks like the
 * built-in page, or a website maintained by hand beside a database that already knows all of it.
 * A feed page is the third: an operator writes the shape, in the same editor as every other page,
 * and this fills it in.
 *
 * <b>The uri is a pattern and the pattern is the parameters.</b> `/whats-on/{{page}}` and
 * `/people/{{member_id}}` are addresses with a hole in them; a request fills the hole and the hole
 * becomes an argument to the query. One token each -- a URL language with two variables in it is a
 * router, and a router is the shortest path to a page nobody can debug. Page one of a listing is
 * always the bare path, whatever the pattern says, because two addresses for one page is two
 * entries in a search engine.
 *
 * <b>Who may read one is inherited, never invented.</b> The address book and the directory need a
 * member exactly as their built-in pages do; the events need one unless the event said anybody may
 * come. Rendering the same page for a stranger and for a member is how a listing quietly becomes a
 * way around the thing it lists, so the audience is part of the cache key rather than a check
 * somewhere upstream.
 *
 * <b>Invalidation is broad on purpose.</b> A listing changes when *anything* it lists changes, and
 * working out whether row 412 was on page three is more code than dropping the events and building
 * them again -- for a few hundred rows, twice a day.
 */
public class Feeds {
  private static final Logger LOG = LoggerFactory.getLogger(Feeds.class);
  /** the most rows any one of these will scan; the scale target is a design input */
  private static final int CEILING = 2000;
  private static final int DEFAULT_PAGE_SIZE = 20;

  /** who is asking, which decides what they get and is half of every cache key */
  public enum Viewer {
    anonymous, member
  }

  /**
   * What a request for a feed address produced.
   *
   * `needsMember` is a third answer, distinct from "here it is" and "there is no such page": the
   * page exists and this person has to sign in first, which is a redirect that carries where they
   * were going rather than a 404 they cannot act on.
   */
  public record Answer(Site.Rendered page, boolean needsMember) {
    static final Answer NOTHING = new Answer(null, false);
    static final Answer SIGN_IN = new Answer(null, true);

    public boolean found() {
      return page != null;
    }
  }

  private final String domain;
  /** the community's clock: what "today" and "upcoming" mean on a listing */
  private final java.time.ZoneId zone;
  private final Site site;
  private final ContentStore content;
  private final Calendar calendar;
  private final Places places;
  private final Users users;
  private final PeopleStore people;
  private final Access access;
  private final TtlCache<String, Site.Rendered> pages;
  private final Verbose verbose;
  /**
   * The feed rows themselves, kept in memory.
   *
   * This is asked on every request that matched nothing else -- which includes every scanner
   * probing for /wp-login.php -- and a query per one of those is a query per piece of noise. It is
   * dropped whenever the content table changes, which is the same event that drops the pages.
   */
  private volatile List<ContentRecord> shapes;

  public Feeds(String domain, java.time.ZoneId zone, Site site, ContentStore content,
               Calendar calendar, Places places, Users users, PeopleStore people, Access access,
               Caches policies, EventBus events, Verbose verbose) {
    this.domain = domain;
    this.zone = zone == null ? java.time.ZoneId.systemDefault() : zone;
    this.site = site;
    this.content = content;
    this.calendar = calendar;
    this.places = places;
    this.users = users;
    this.people = people;
    this.access = access;
    this.pages = new TtlCache<>(Caches.FEEDS, policies.forName(Caches.FEEDS));
    this.verbose = verbose;
    events.subscribe(this::onMutation);
  }

  public List<TtlCache.Stats> cacheStats() {
    return List.of(pages.stats());
  }

  /**
   * Drop everything a change could have moved.
   *
   * Broad by design: one answer to an event changes its counts, which changes the row on every
   * listing that shows it, on a page number that depends on how many events there are. Working out
   * which cached page is now wrong is more code and more ways to be wrong than building them again.
   */
  private void onMutation(MutationEvent event) {
    if (!event.domain().equals(domain)) {
      return;
    }
    // the shape itself changed, and which page was built from which row is not worth tracking for
    // a table with a handful of feed rows in it
    if (event.touches(Schema.CONTENT) || event.touches(Schema.TEMPLATES)) {
      shapes = null;
      int all = pages.clear();
      if (all > 0) {
        verbose.detail(() -> "cache: a page changed, dropped " + all + " feed page(s)");
      }
      return;
    }
    ContentRecord.Source source = sourceOf(event);
    if (source == ContentRecord.Source.none) {
      return;
    }
    int dropped = pages.invalidateIf(page -> page != null && page.templateName() != null
        && page.templateName().startsWith(source.name() + ":"));
    if (dropped > 0) {
      verbose.detail(() -> "cache: " + source + " changed, dropped " + dropped + " feed page(s)");
    }
  }

  private static ContentRecord.Source sourceOf(MutationEvent event) {
    if (event.touches(Schema.CALENDAR) || event.touches(Schema.RSVPS)
        || event.touches(Schema.PUBLIC_RSVPS)) {
      return ContentRecord.Source.events;
    }
    if (event.touches(Schema.PLACES) || event.touches(Schema.PLACE_TYPES)) {
      return ContentRecord.Source.places;
    }
    if (event.touches(Schema.EMAILS) || event.touches(Schema.PROFILES)) {
      return ContentRecord.Source.members;
    }
    return ContentRecord.Source.none;
  }

  /**
   * The feed page answering this address, if one does.
   *
   * Tried after a real page and after a directory listing, so a page somebody actually wrote always
   * wins over a pattern that wanted the same address.
   */
  public Answer answer(String path, String query, Viewer viewer) {
    String key = viewer.name() + "|" + path + "|" + (query == null ? "" : query);
    Site.Rendered hit = pages.get(key);
    if (hit != null) {
      return new Answer(hit, false);
    }
    try {
      for (ContentRecord row : shapes()) {
        String argument = match(row, path);
        if (argument == null) {
          continue;
        }
        if (viewer != Viewer.member && needsMember(row.kind())) {
          return Answer.SIGN_IN;
        }
        Map<String, Object> model = model(row, argument, viewer);
        if (model == null) {
          // a page number past the end, or a row that is not there: a link that has gone stale,
          // and an empty page saying nothing is worse than a 404 somebody can act on
          return Answer.NOTHING;
        }
        Site.Rendered made = site.renderFeed(row, model, row.kind().source.name() + ":" + row.id());
        if (made == null) {
          return Answer.NOTHING;
        }
        pages.put(key, made);
        return new Answer(made, false);
      }
    } catch (SQLException ex) {
      LOG.error("feed-failed path={}", path, ex);
    }
    return Answer.NOTHING;
  }

  /** every published feed page, from memory when it can be */
  private List<ContentRecord> shapes() throws SQLException {
    List<ContentRecord> known = shapes;
    if (known != null) {
      return known;
    }
    ArrayList<ContentRecord> found = new ArrayList<>();
    for (ContentRecord row : content.allContent(500)) {
      if (row.kind().isFeed() && row.published()) {
        found.add(row);
      }
    }
    shapes = found;
    return found;
  }

  /**
   * Does this address belong to this page, and what did it put in the hole?
   *
   * Returns the argument -- a page number or a row id as typed -- or null when the address is
   * somebody else's. A listing's bare path is page one, whatever the pattern says.
   */
  static String match(ContentRecord row, String path) {
    String pattern = row.uri();
    String token = row.kind().token;
    if (pattern == null || token == null) {
      return null;
    }
    int hole = pattern.indexOf(token);
    if (hole < 0) {
      // no token: a listing with one page and nowhere to put a number, which is a legitimate thing
      // to want for a community with eleven events and no interest in pagination
      return row.kind().listing && path.equals(pattern) ? "1" : null;
    }
    String before = pattern.substring(0, hole);
    String after = pattern.substring(hole + token.length());
    if (row.kind().listing && path.equals(trimSlash(before))) {
      return "1";
    }
    if (!path.startsWith(before) || !path.endsWith(after)) {
      return null;
    }
    String middle = path.substring(before.length(), path.length() - after.length());
    return middle.isEmpty() || middle.contains("/") ? null : middle;
  }

  private static String trimSlash(String value) {
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1) : value;
  }

  /** the address of page n of a listing, with page one always the bare path */
  static String urlFor(ContentRecord row, int page) {
    String pattern = row.uri();
    String token = row.kind().token;
    int hole = token == null ? -1 : pattern.indexOf(token);
    if (hole < 0) {
      return pattern;
    }
    if (page <= 1) {
      return trimSlash(pattern.substring(0, hole));
    }
    return pattern.replace(token, Integer.toString(page));
  }

  /** the address of one row, through the pattern that names it */
  static String urlFor(ContentRecord row, long id) {
    String token = row.kind().token;
    return token == null ? row.uri() : row.uri().replace(token, Long.toString(id));
  }

  /**
   * A page of the address book, the directory or the calendar needs somebody who has been let in.
   *
   * The same rule the built-in pages follow. An events feed is the exception and it is the point of
   * the exception: an event the community said anybody may come to is one anybody may read about,
   * and everything else on that listing is left out for a stranger.
   */
  static boolean needsMember(ContentRecord.Kind kind) {
    return kind.source == ContentRecord.Source.places
        || kind.source == ContentRecord.Source.members;
  }

  // ---- the models ---------------------------------------------------------------------------

  private Map<String, Object> model(ContentRecord row, String argument, Viewer viewer)
      throws SQLException {
    return switch (row.kind()) {
      case event_listing -> events(row, number(argument), viewer);
      case event -> oneEvent(row, number(argument), viewer);
      case place_listing -> placeList(row, number(argument));
      case place -> onePlace(row, number(argument));
      case member_listing -> memberList(row, number(argument));
      case member -> oneMember(row, number(argument));
      default -> null;
    };
  }

  private static int number(String raw) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private Map<String, Object> events(ContentRecord row, int page, Viewer viewer)
      throws SQLException {
    LocalDate today = LocalDate.now(zone);
    ArrayList<Calendar.Event> all = new ArrayList<>();
    for (Calendar.Event event : calendar.upcoming(today, CEILING)) {
      if (!event.live()) {
        continue;
      }
      if (viewer != Viewer.member && !event.openToPublic()) {
        continue;
      }
      all.add(event);
    }
    Slice slice = slice(all.size(), page, pageSize(row));
    if (slice == null) {
      return null;
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Calendar.Event event : all.subList(slice.from(), slice.to())) {
      rows.add(eventRow(event, today));
    }
    Map<String, Object> model = shell(row, "events", rows);
    model.putAll(pagination(row, slice, rows, "event_id",
        slice.to() < all.size() ? Long.toString(all.get(slice.to()).id()) : ""));
    return model;
  }

  private Map<String, Object> oneEvent(ContentRecord row, int id, Viewer viewer)
      throws SQLException {
    Calendar.Event event = id <= 0 ? null : calendar.byId(id);
    if (event == null || !event.live()) {
      return null;
    }
    if (viewer != Viewer.member && !event.openToPublic()) {
      return null;
    }
    Map<String, Object> model = shell(row, "events", null);
    model.putAll(eventRow(event, LocalDate.now(zone)));
    model.put("body_html", Markdown.toSafeHtml(event.body()));
    return model;
  }

  private Map<String, Object> eventRow(Calendar.Event event, LocalDate today) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", event.id());
    out.put("title", event.title());
    out.put("summary", excerpt(event.body()));
    out.put("starts_on", event.startsOn().toString());
    out.put("ends_on", event.endsOn().toString());
    out.put("spans_days", event.spansDays());
    out.put("time", event.startTime());
    out.put("where", event.location());
    out.put("going", event.goingCount());
    out.put("limited", event.limited());
    out.put("capacity", event.capacity() == null ? 0 : event.capacity());
    out.put("seats_left", event.seatsLeft());
    out.put("full", event.full());
    out.put("open_to_public", event.openToPublic());
    out.put("over", event.over(today));
    out.put("today", event.today(today));
    out.put("ics_url", "/events/" + event.id() + ".ics");
    return out;
  }

  private Map<String, Object> placeList(ContentRecord row, int page) throws SQLException {
    // which kind of place this listing is of: a slug, or "*" for all of them. A community with
    // ranches and vendors wants two pages rather than one page of both, and "*" is how it says it
    // meant everything rather than having forgotten to choose.
    String wanted = setting(row, "place_kind", "*");
    ArrayList<Places.Place> all = new ArrayList<>();
    for (Places.Place place : places.all(CEILING)) {
      if (!place.published() || place.humanOnly()) {
        continue;
      }
      if (!"*".equals(wanted) && !wanted.isEmpty() && !wanted.equals(place.typeSlug())) {
        continue;
      }
      all.add(place);
    }
    sortPlaces(all, setting(row, "sort", "name"));
    Slice slice = slice(all.size(), page, pageSize(row));
    if (slice == null) {
      return null;
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Places.Place place : all.subList(slice.from(), slice.to())) {
      rows.add(placeRow(place));
    }
    Map<String, Object> model = shell(row, "places", rows);
    model.putAll(pagination(row, slice, rows, "place_id",
        slice.to() < all.size() ? Long.toString(all.get(slice.to()).id()) : ""));
    return model;
  }

  private Map<String, Object> onePlace(ContentRecord row, int id) throws SQLException {
    Places.Place place = id <= 0 ? null : places.byId(id);
    if (place == null || !place.published() || place.humanOnly()) {
      return null;
    }
    Map<String, Object> model = shell(row, "places", null);
    model.putAll(placeRow(place));
    model.put("body_html", Markdown.toSafeHtml(place.body()));
    // whatever this kind of place declared, by name, so a template can print "grass finished"
    ArrayList<Map<String, Object>> fields = new ArrayList<>();
    for (Map.Entry<String, String> value : place.values().entrySet()) {
      LinkedHashMap<String, Object> field = new LinkedHashMap<>();
      field.put("name", value.getKey());
      field.put("value", value.getValue());
      fields.add(field);
      model.putIfAbsent(value.getKey(), value.getValue());
    }
    model.put("fields", fields);
    return model;
  }

  private Map<String, Object> placeRow(Places.Place place) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", place.id());
    out.put("name", place.name());
    out.put("kind", place.typeSlug());
    out.put("slug", place.slug());
    out.put("address", place.address());
    out.put("summary", excerpt(place.body()));
    out.put("latitude", place.latitude() == null ? "" : place.latitude().toString());
    out.put("longitude", place.longitude() == null ? "" : place.longitude().toString());
    out.put("located", place.latitude() != null && place.longitude() != null);
    return out;
  }

  private Map<String, Object> memberList(ContentRecord row, int page) throws SQLException {
    ArrayList<UserRecord> all = new ArrayList<>();
    for (UserRecord person : users.recent(CEILING)) {
      if (!person.disabled() && access.isApproved(person)) {
        all.add(person);
      }
    }
    sortMembers(all, setting(row, "sort", "name"));
    Slice slice = slice(all.size(), page, pageSize(row));
    if (slice == null) {
      return null;
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (UserRecord person : all.subList(slice.from(), slice.to())) {
      rows.add(memberRow(person));
    }
    Map<String, Object> model = shell(row, "members", rows);
    model.putAll(pagination(row, slice, rows, "member_id",
        slice.to() < all.size() ? Long.toString(all.get(slice.to()).id()) : ""));
    return model;
  }

  private Map<String, Object> oneMember(ContentRecord row, int id) throws SQLException {
    UserRecord person = id <= 0 ? null : users.byId(id);
    if (person == null || person.disabled() || !access.isApproved(person)) {
      return null;
    }
    Map<String, Object> model = shell(row, "members", null);
    model.putAll(memberRow(person));
    ProfileRecord profile = people.profileOf(person.id());
    model.put("body_html", profile == null ? "" : Markdown.toSafeHtml(profile.about()));
    return model;
  }

  /**
   * One person, as another member sees them.
   *
   * <b>Never an address.</b> Same rule as the directory and for the same reason: a member list is
   * the easiest thing in the world to screenshot, and a feed page is a member list an operator
   * designed. Somebody with no name yet is "a member" rather than the front of their address.
   */
  private Map<String, Object> memberRow(UserRecord person) throws SQLException {
    ProfileRecord profile = people.profileOf(person.id());
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", person.id());
    out.put("name", profile == null || profile.displayName() == null
        || profile.displayName().isBlank() ? "a member" : profile.displayName());
    out.put("where", profile == null ? "" : profile.location());
    out.put("summary", profile == null ? "" : excerpt(profile.about()));
    out.put("joined", person.createdAt() == null ? ""
        : person.createdAt().toLocalDateTime().toLocalDate().toString());
    return out;
  }

  // ---- pagination ---------------------------------------------------------------------------

  /** which rows page n holds, or null when there is no such page */
  private record Slice(int page, int pages, int from, int to, int count) {
  }

  private static Slice slice(int count, int page, int size) {
    if (page <= 0) {
      return null;
    }
    int pages = Math.max(1, (count + size - 1) / size);
    if (page > pages) {
      return null;
    }
    int from = Math.min((page - 1) * size, count);
    return new Slice(page, pages, from, Math.min(from + size, count), count);
  }

  /**
   * How a listing is ordered.
   *
   * <b>Alphabetical is the default for places and people, and it is not an arbitrary choice.</b> A
   * directory somebody is *looking somebody up in* has one useful order, and it is the one they can
   * predict. Newest-first is right for a blog and wrong for an address book, where it means the
   * page moves under whoever is reading it every time anybody adds a venue. Events order themselves
   * by the day they happen, which is the only order an event listing can have.
   */
  private void sortPlaces(List<Places.Place> places, String how) {
    switch (how) {
      case "newest" -> places.sort((left, right) -> Long.compare(right.id(), left.id()));
      case "oldest" -> places.sort((left, right) -> Long.compare(left.id(), right.id()));
      case "kind" -> places.sort((left, right) -> {
        int byKind = left.typeSlug().compareToIgnoreCase(right.typeSlug());
        return byKind != 0 ? byKind : left.name().compareToIgnoreCase(right.name());
      });
      default -> places.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
    }
  }

  private void sortMembers(List<UserRecord> people, String how) {
    if (how.equals("joined")) {
      // oldest first: "who has been here longest" is a thing a community says about itself
      people.sort((left, right) -> Long.compare(
          left.createdAt() == null ? 0 : left.createdAt().getTime(),
          right.createdAt() == null ? 0 : right.createdAt().getTime()));
      return;
    }
    if (how.equals("newest")) {
      people.sort((left, right) -> Long.compare(
          right.createdAt() == null ? 0 : right.createdAt().getTime(),
          left.createdAt() == null ? 0 : left.createdAt().getTime()));
      return;
    }
    people.sort((left, right) -> {
      String leftName = nameOf(left);
      String rightName = nameOf(right);
      return leftName.compareToIgnoreCase(rightName);
    });
  }

  private String nameOf(UserRecord person) {
    try {
      ProfileRecord profile = people.profileOf(person.id());
      String name = profile == null ? null : profile.displayName();
      // somebody with no name yet sorts last rather than under whatever their address starts with
      return name == null || name.isBlank() ? "\uffff" : name;
    } catch (SQLException ex) {
      return "\uffff";
    }
  }

  /** one of the settings a feed page carries in its fields blob */
  public static String setting(ContentRecord row, String name, String fallback) {
    String value = Site.fieldsOf(row).get(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static int pageSize(ContentRecord row) {
    Map<String, String> fields = Site.fieldsOf(row);
    String declared = fields.get("page_size");
    if (declared != null) {
      try {
        int size = Integer.parseInt(declared.trim());
        if (size > 0) {
          return Math.min(size, 200);
        }
      } catch (NumberFormatException ex) {
        // a page size nobody can parse is a page size nobody meant
      }
    }
    return DEFAULT_PAGE_SIZE;
  }

  /**
   * Everything a listing needs to draw its own navigation.
   *
   * `pagination` as an object as well as the flat keys, because a template that says
   * `{{#pagination.has_next}}` reads like what it is and a template that says `{{#hasNext}}` reads
   * like a variable somebody hopes exists.
   */
  private Map<String, Object> pagination(ContentRecord row, Slice slice,
                                         List<Map<String, Object>> rows, String idName,
                                         String nextId) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    LinkedHashMap<String, Object> pagination = new LinkedHashMap<>();
    boolean hasNext = slice.page() < slice.pages();
    pagination.put("page", slice.page());
    pagination.put("pages", slice.pages());
    pagination.put("count", slice.count());
    pagination.put("size", slice.to() - slice.from());
    pagination.put("has_next", hasNext);
    pagination.put("has_prev", slice.page() > 1);
    pagination.put("first", slice.page() == 1);
    pagination.put("last", slice.page() == slice.pages());
    pagination.put("next_url", hasNext ? urlFor(row, slice.page() + 1) : "");
    pagination.put("prev_url", slice.page() > 1 ? urlFor(row, slice.page() - 1) : "");
    pagination.put("first_url", urlFor(row, 1));
    pagination.put("last_url", urlFor(row, slice.pages()));
    // the id of the first row on the next page: what a reader following this feed by machine wants,
    // and the one number here that says something a page number cannot
    pagination.put("next_id", nextId);
    ArrayList<Map<String, Object>> numbers = new ArrayList<>();
    for (int n = 1; n <= slice.pages(); n++) {
      LinkedHashMap<String, Object> number = new LinkedHashMap<>();
      number.put("n", n);
      number.put("url", urlFor(row, n));
      number.put("here", n == slice.page());
      numbers.add(number);
    }
    pagination.put("numbers", numbers);
    out.put("pagination", pagination);
    out.put("id_parameter", idName);
    out.put("any", !rows.isEmpty());
    out.put("count", slice.count());
    return out;
  }

  /** the keys every feed page carries, whatever it is a feed of */
  private Map<String, Object> shell(ContentRecord row, String rowsName,
                                    List<Map<String, Object>> rows) {
    LinkedHashMap<String, Object> model = new LinkedHashMap<>();
    model.put("title", row.title());
    model.put("uri", row.uri());
    model.put("feed", row.kind().name());
    if (rows != null) {
      model.put(rowsName, rows);
      model.put("rows", rows);
    }
    return model;
  }

  /** the plain first words of something, for a listing */
  static String excerpt(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    String text = io.hearth.web.Html.text(Markdown.toSafeHtml(markdown))
        .replace('\n', ' ').trim();
    if (text.length() <= 240) {
      return text;
    }
    int cut = text.lastIndexOf(' ', 240);
    return text.substring(0, cut > 120 ? cut : 240).trim() + "…";
  }

}
