package io.hearth.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.content.TemplateField;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The address book: places a community cares about, and what it wants to remember about each kind.
 *
 * One table of addresses and one table of *kinds* of address. The kinds are data rather than code,
 * and each declares its own fields -- so a carnivore supper club invents "ranch" with
 * grass-finished and cuts-sold, an MS group invents "vendor" with what-the-discount-is and
 * who-to-ask-for, and a games night invents "venue" with how-many-fit. None of those belong in a
 * schema, and a community that had to ask for a column would keep the information in somebody's
 * head instead.
 *
 * The field definitions reuse {@link TemplateField}, which is the same machinery a content template
 * uses to declare the boxes its editor shows. Two ways to describe a form would eventually disagree
 * about what "required" means.
 *
 * Slugs rather than ids in the URL, unique within a type: `/places/ranch/oak-hill` survives the name
 * being edited, and an address book whose links break when somebody fixes a typo is one nobody links
 * to.
 */
public class Places {
  /**
   * The kind that always exists, so removing a kind never removes an address.
   *
   * Seeded at boot and refuses to be deleted. It declares no fields, which is exactly right for
   * somewhere to put an address whose kind has gone: nothing about it is claimed, and a person can
   * decide later. Unpublished, so an address that lands here is not quietly re-listed under a
   * heading nobody chose.
   */
  public static final String DEFAULT_TYPE = "unsorted";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TYPE_COLUMNS =
      "id, slug, label, plural, description, fields, template_name, icon, published, sort,"
          + " created_at, updated_at";
  private static final String PLACE_COLUMNS =
      "id, type_slug, slug, name, address, locality, region, postcode, country, latitude,"
          + " longitude, url, phone, email, fields, body, published, human_only, created_at,"
          + " updated_at, updated_by";

  private final Store store;

  public Places(Store store) {
    this.store = store;
  }

  public record Type(long id, String slug, String label, String plural, String description,
                     List<TemplateField> fields, String templateName, String icon,
                     boolean published, int sort, Timestamp createdAt, Timestamp updatedAt) {
    public String labelOr() {
      return label == null || label.isBlank() ? slug : label;
    }

    /** the plural is stored rather than derived, because "ranchs" is not a word */
    public String pluralOr() {
      return plural == null || plural.isBlank() ? labelOr() + "s" : plural;
    }
  }

  public record Place(long id, String typeSlug, String slug, String name, String address,
                      String locality, String region, String postcode, String country,
                      Double latitude, Double longitude, String url, String phone, String email,
                      String fields, String body, boolean published, boolean humanOnly,
                      Timestamp createdAt, Timestamp updatedAt, Long updatedBy) {
    /** the address as somebody would read it aloud, with the empty parts left out */
    public String oneLine() {
      StringBuilder out = new StringBuilder();
      for (String part : new String[]{address, locality, region, postcode, country}) {
        if (part != null && !part.isBlank()) {
          if (out.length() > 0) {
            out.append(", ");
          }
          out.append(part.trim());
        }
      }
      return out.toString();
    }

    public boolean mapped() {
      return latitude != null && longitude != null;
    }

    /** the values for whatever its type declared, as a plain map */
    public Map<String, String> values() {
      LinkedHashMap<String, String> values = new LinkedHashMap<>();
      try {
        JsonNode node = JSON.readTree(fields == null || fields.isBlank() ? "{}" : fields);
        node.fieldNames().forEachRemaining(name -> values.put(name, node.get(name).asText("")));
      } catch (Exception ex) {
        // an unreadable blob is a place with no extras, not a page that fails to render
        return values;
      }
      return values;
    }
  }

  // ---- kinds of place --------------------------------------------------------------------------

  public List<Type> allTypes() throws SQLException {
    ArrayList<Type> types = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TYPE_COLUMNS + " FROM " + Schema.PLACE_TYPES + " ORDER BY sort, label")) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          types.add(readType(rows));
        }
      }
    }
    return types;
  }

  public List<Type> publishedTypes() throws SQLException {
    ArrayList<Type> types = new ArrayList<>();
    for (Type type : allTypes()) {
      if (type.published()) {
        types.add(type);
      }
    }
    return types;
  }

  public Type typeBySlug(String slug) throws SQLException {
    String clean = slugify(slug);
    if (clean == null) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TYPE_COLUMNS + " FROM " + Schema.PLACE_TYPES + " WHERE slug = ?")) {
      statement.setString(1, clean);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readType(rows) : null;
      }
    }
  }

  public Type saveType(String slug, String label, String plural, String description,
                       List<TemplateField> fields, String templateName, String icon,
                       boolean published, int sort, Long actor) throws SQLException {
    String clean = slugify(slug);
    if (clean == null) {
      throw new SQLException("a kind of place needs a name");
    }
    String blob = TemplateField.toBlob(fields);
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PLACE_TYPES + " SET label = ?, plural = ?, description = ?,"
                 + " fields = ?, template_name = ?, icon = ?, published = ?, sort = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE slug = ?")) {
      bindType(statement, label, plural, description, blob, templateName, icon, published, sort);
      statement.setString(9, clean);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.PLACE_TYPES + " (label, plural, description, fields,"
                   + " template_name, icon, published, sort, slug)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        bindType(statement, label, plural, description, blob, templateName, icon, published, sort);
        statement.setString(9, clean);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.PLACE_TYPES, clean, MutationEvent.Kind.update, actor);
    return typeBySlug(clean);
  }

  /**
   * Put the kind that always exists in place.
   *
   * Called at boot and idempotent, so an address whose kind is removed always has somewhere to go.
   * Only *created*, never rewritten: a community that decided to publish it, or to give it a
   * description, keeps that decision.
   */
  public void seed() throws SQLException {
    if (typeBySlug(DEFAULT_TYPE) == null) {
      saveType(DEFAULT_TYPE, "Unsorted", "Unsorted",
          "Addresses whose kind was removed. Nothing is claimed about them; give them a kind when"
              + " you know which one.", List.of(), "", "", false, 1000, null);
    }
  }

  /**
   * Remove a kind. The addresses filed under it are kept.
   *
   * Deleting somebody's forty ranches because they removed a heading is not a thing software should
   * do, and a confirmation dialog is not consent for that. So the kind goes and its addresses move
   * to {@link #DEFAULT_TYPE}, unpublished -- because the fields they carry were declared by a kind
   * that no longer exists, and re-listing them under a heading nobody chose would be worse than
   * putting them aside.
   *
   * Their field values are kept in the blob even though nothing displays them. Moving one back to a
   * kind that declares those fields brings them straight back, and throwing away somebody's typing
   * because a heading moved is the same mistake as deleting the address.
   *
   * The default kind itself refuses, because there would be nowhere for the next one to go.
   */
  public int retireType(String slug, Long actor) throws SQLException {
    String clean = slugify(slug);
    if (clean == null || DEFAULT_TYPE.equals(clean)) {
      return -1;
    }
    seed();
    int moved;
    try (Connection connection = store.connection();
         PreparedStatement places = connection.prepareStatement(
             "UPDATE " + Schema.PLACES + " SET type_slug = ?, published = FALSE,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE type_slug = ?");
         PreparedStatement type = connection.prepareStatement(
             "DELETE FROM " + Schema.PLACE_TYPES + " WHERE slug = ?")) {
      places.setString(1, DEFAULT_TYPE);
      places.setString(2, clean);
      moved = places.executeUpdate();
      type.setString(1, clean);
      type.executeUpdate();
    }
    store.changed(Schema.PLACE_TYPES, clean, MutationEvent.Kind.delete, actor);
    return moved;
  }

  // ---- the addresses ---------------------------------------------------------------------------

  public List<Place> inType(String typeSlug, boolean publishedOnly, int limit) throws SQLException {
    ArrayList<Place> places = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PLACE_COLUMNS + " FROM " + Schema.PLACES + " WHERE type_slug = ?"
                 + (publishedOnly ? " AND published = TRUE" : "")
                 + " ORDER BY name " + store.dialect().limit(limit))) {
      statement.setString(1, slugify(typeSlug));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          places.add(readPlace(rows));
        }
      }
    }
    return places;
  }

  public List<Place> all(int limit) throws SQLException {
    ArrayList<Place> places = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PLACE_COLUMNS + " FROM " + Schema.PLACES + " ORDER BY type_slug, name "
                 + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          places.add(readPlace(rows));
        }
      }
    }
    return places;
  }

  /**
   * How an address in the book last got on with being placed.
   *
   * Read separately from the place itself, exactly as a member's is: it is bookkeeping about a
   * lookup rather than something anybody wrote down about the place, and putting it on the record
   * would mean every caller that builds a Place carrying six fields it does not care about.
   */
  public Placement placementOf(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT geo_state, geo_service, geo_tries, geo_tried_at, geo_next_at, geo_note FROM "
                 + Schema.PLACES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? new Placement(rows.getString("geo_state"), rows.getString("geo_service"),
                rows.getInt("geo_tries"), rows.getTimestamp("geo_tried_at"),
                rows.getTimestamp("geo_next_at"), rows.getString("geo_note"))
            : Placement.blank();
      }
    }
  }

  /**
   * Just the two numbers, written by the queue after the fact.
   *
   * Its own update rather than a full save because it runs minutes later on a background thread:
   * reading the whole row, changing two fields and writing it back would quietly undo whatever
   * somebody edited in between.
   */
  public void placed(long id, double latitude, double longitude, String service)
      throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PLACES + " SET latitude = ?, longitude = ?, geo_state = ?,"
                 + " geo_service = ?, geo_tries = 0, geo_next_at = NULL,"
                 + " geo_tried_at = CURRENT_TIMESTAMP, geo_note = '' WHERE id = ?")) {
      statement.setDouble(1, latitude);
      statement.setDouble(2, longitude);
      statement.setString(3, Placement.PLACED);
      statement.setString(4, service);
      statement.setLong(5, id);
      statement.executeUpdate();
    }
    store.changed(Schema.PLACES, id, io.hearth.events.MutationEvent.Kind.update, null);
  }

  /** the service answered and has no such address; nothing asks again on its own */
  public void notFound(long id, String service, String note) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PLACES + " SET geo_state = ?, geo_service = ?, geo_tries = 0,"
                 + " geo_next_at = NULL, geo_tried_at = CURRENT_TIMESTAMP, geo_note = ?"
                 + " WHERE id = ?")) {
      statement.setString(1, Placement.NOT_FOUND);
      statement.setString(2, service);
      statement.setString(3, note);
      statement.setLong(4, id);
      statement.executeUpdate();
    }
  }

  /** the service could not be asked; try again later, and keep whatever point is already there */
  public void unreachable(long id, String service, String note, long now) throws SQLException {
    int tries = placementOf(id).tries() + 1;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PLACES + " SET geo_state = ?, geo_service = ?, geo_tries = ?,"
                 + " geo_next_at = ?, geo_tried_at = CURRENT_TIMESTAMP, geo_note = ?"
                 + " WHERE id = ?")) {
      statement.setString(1, Placement.UNREACHABLE);
      statement.setString(2, service);
      statement.setInt(3, tries);
      statement.setTimestamp(4, Placement.scheduleAfter(tries, now));
      statement.setString(5, note);
      statement.setLong(6, id);
      statement.executeUpdate();
    }
  }

  /** every address worth asking about right now; the same three rules a member's follows */
  public java.util.List<Long> dueForPlacement(String service, long now, int limit)
      throws SQLException {
    java.util.ArrayList<Long> found = new java.util.ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id FROM " + Schema.PLACES
                 + " WHERE latitude IS NULL AND (address <> '' OR name <> '')"
                 + " AND (geo_state = '' "
                 + "      OR (geo_state = ? AND (geo_next_at IS NULL OR geo_next_at <= ?))"
                 + "      OR (geo_state = ? AND geo_service <> ?))"
                 + " ORDER BY id " + store.dialect().limit(limit))) {
      statement.setString(1, Placement.UNREACHABLE);
      statement.setTimestamp(2, new java.sql.Timestamp(now));
      statement.setString(3, Placement.NOT_FOUND);
      statement.setString(4, service == null ? "" : service);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(rows.getLong("id"));
        }
      }
    }
    return found;
  }

  /** forget every failure, so everything without a point is due again */
  public int reopenPlacements() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PLACES + " SET geo_state = '', geo_tries = 0, geo_next_at = NULL"
                 + " WHERE latitude IS NULL AND geo_state <> ''")) {
      return statement.executeUpdate();
    }
  }

  /** how many addresses are placed, unfindable, waiting to be retried, or never asked about */
  public java.util.Map<String, Integer> placementCounts() throws SQLException {
    java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT geo_state, COUNT(*) AS how_many FROM " + Schema.PLACES
                 + " WHERE address <> '' OR name <> '' GROUP BY geo_state");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String state = rows.getString("geo_state");
        counts.put(state == null || state.isBlank() ? Placement.UNKNOWN : state,
            rows.getInt("how_many"));
      }
    }
    return counts;
  }

  public Place byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PLACE_COLUMNS + " FROM " + Schema.PLACES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readPlace(rows) : null;
      }
    }
  }

  public Place bySlug(String typeSlug, String slug) throws SQLException {
    String type = slugify(typeSlug);
    String clean = slugify(slug);
    if (type == null || clean == null) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PLACE_COLUMNS + " FROM " + Schema.PLACES
                 + " WHERE type_slug = ? AND slug = ?")) {
      statement.setString(1, type);
      statement.setString(2, clean);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readPlace(rows) : null;
      }
    }
  }

  /**
   * Write one.
   *
   * Identity is the id when the caller has one, and the type-and-slug pair only when it does not --
   * the rule content learned the hard way, so that renaming a place moves it rather than leaving a
   * copy at the old address with the links still pointing at it.
   */
  /**
   * Everywhere this community has written down, near a point.
   *
   * Straight-line distance in kilometres, computed in Java over every place that has a coordinate.
   * At a few hundred places that is a scan and a bit of arithmetic; a spatial index would be the
   * right answer at a hundred thousand and is a dependency and a schema at three hundred.
   *
   * The haversine formula rather than a flat approximation, because a flat one is wrong by enough
   * to matter at the latitudes people actually live at, and this is deciding whether a village hall
   * is the village hall.
   */
  public List<Nearby> near(double latitude, double longitude, double withinKm) throws SQLException {
    ArrayList<Nearby> out = new ArrayList<>();
    for (Place place : all(2000)) {
      if (!place.mapped()) {
        continue;
      }
      double km = distanceKm(latitude, longitude, place.latitude(), place.longitude());
      if (km <= withinKm) {
        out.add(new Nearby(place, km));
      }
    }
    out.sort(java.util.Comparator.comparingDouble(Nearby::km));
    return out;
  }

  /** a place and how far away it turned out to be */
  public record Nearby(Place place, double km) {
  }

  /** the great-circle distance, in kilometres */
  public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
    double earthKm = 6371.0088;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return earthKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  /**
   * A place whose name or address somebody typed, matched against what is already here.
   *
   * <b>Loose on purpose, because the alternative is duplicates.</b> "The Oak", "the oak" and "The
   * Oak " are one pub, and an address book that gains a second entry every time somebody types the
   * name slightly differently is one nobody trusts. Names are compared with case, spacing and
   * leading articles taken out; an address is compared the same way.
   *
   * Deliberately *not* fuzzy beyond that. Levenshtein distance over place names would match "The
   * Oak" to "The Oaks", which is two different pubs in a lot of towns.
   */
  public Place matching(String name, String address) throws SQLException {
    String wantedName = normalize(name);
    String wantedAddress = normalize(address);
    if (wantedName.isEmpty() && wantedAddress.isEmpty()) {
      return null;
    }
    for (Place place : all(2000)) {
      if (!wantedName.isEmpty() && normalize(place.name()).equals(wantedName)) {
        return place;
      }
      if (!wantedAddress.isEmpty() && !place.address().isBlank()
          && normalize(place.address()).equals(wantedAddress)) {
        return place;
      }
    }
    return null;
  }

  /**
   * The one line to hand a geocoder.
   *
   * The address parts if there are any, and the name if there are not -- because "The Oak, Ashford"
   * is a thing a geocoder can find and an empty string is not, and a community that types a pub's
   * name and no address is the common case rather than the sloppy one.
   */
  public static String addressLine(String address, String locality, String region, String postcode,
                                   String country, String name) {
    StringBuilder out = new StringBuilder();
    for (String part : new String[]{address, locality, region, postcode, country}) {
      if (part != null && !part.isBlank()) {
        if (out.length() > 0) {
          out.append(", ");
        }
        out.append(part.trim());
      }
    }
    if (out.length() == 0) {
      return name == null ? "" : name.trim();
    }
    return out.toString();
  }

  /** lower case, one space between words, no leading article, nothing on the ends */
  static String normalize(String value) {
    if (value == null) {
      return "";
    }
    String out = value.trim().toLowerCase(java.util.Locale.ROOT)
        // an apostrophe disappears rather than becoming a gap, because "St Mary's" and "St Marys"
        // are one hall and "st mary s hall" matches neither of them
        .replace("'", "")
        .replace("\u2019", "")
        .replaceAll("[\\p{Punct}]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    for (String article : new String[]{"the ", "a ", "an "}) {
      if (out.startsWith(article)) {
        out = out.substring(article.length());
      }
    }
    return out.trim();
  }

  public Place save(Place place, Long actor) throws SQLException {
    Place existing = place.id() > 0 ? byId(place.id()) : bySlug(place.typeSlug(), place.slug());
    long id;
    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.PLACES + " (type_slug, slug, name, address, locality,"
                   + " region, postcode, country, latitude, longitude, url, phone, email, fields,"
                   + " body, published, human_only, updated_by)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
               Statement.RETURN_GENERATED_KEYS)) {
        bindPlace(statement, place, actor);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          keys.next();
          id = keys.getLong(1);
        }
      }
      store.changed(Schema.PLACES, id, MutationEvent.Kind.insert, actor);
    } else {
      id = existing.id();
      if (!existing.typeSlug().equals(slugify(place.typeSlug())) && place.published()) {
        // Changing what something *is* invalidates what was recorded about it: the fields belonged
        // to the old kind, and the new one asks different questions. So it comes off the listing
        // and somebody looks at it before it goes back on.
        place = new Place(place.id(), place.typeSlug(), place.slug(), place.name(), place.address(),
            place.locality(), place.region(), place.postcode(), place.country(), place.latitude(),
            place.longitude(), place.url(), place.phone(), place.email(), place.fields(),
            place.body(), false, place.humanOnly(), place.createdAt(), place.updatedAt(),
            place.updatedBy());
      }
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.PLACES + " SET type_slug = ?, slug = ?, name = ?, address = ?,"
                   + " locality = ?, region = ?, postcode = ?, country = ?, latitude = ?,"
                   + " longitude = ?, url = ?, phone = ?, email = ?, fields = ?, body = ?,"
                   + " published = ?, human_only = ?, updated_by = ?,"
                   + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        bindPlace(statement, place, actor);
        statement.setLong(19, id);
        statement.executeUpdate();
      }
      store.changed(Schema.PLACES, id, MutationEvent.Kind.update, actor);
    }
    return byId(id);
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PLACES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.PLACES, id, MutationEvent.Kind.delete, actor);
  }

  public int countIn(String typeSlug) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.PLACES + " WHERE type_slug = ?")) {
      statement.setString(1, slugify(typeSlug));
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.PLACES)) {
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /**
   * Across the name, the address and the *values* of the extra fields.
   *
   * Searching for "grass" has to find a ranch that recorded grass-finished beef, which is a value
   * somebody typed into a field this code has never heard of -- a search that only knew about
   * columns would never find it.
   *
   * But it must not find one that recorded *grain*. The blob is `{"grass_finished":"grain
   * finished"}`, so a LIKE over the raw text matches the field's **name** and returns exactly the
   * place the person was trying to exclude. So the SQL is a cheap prefilter and the values are
   * checked properly here: at a few hundred rows that costs nothing, and it is the difference
   * between a search that works and one that quietly answers the opposite question.
   */
  public List<Place> search(String query, boolean publishedOnly, int limit) throws SQLException {
    String needle = query == null ? "" : query.trim().toLowerCase();
    String like = "%" + needle + "%";
    ArrayList<Place> places = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PLACE_COLUMNS + " FROM " + Schema.PLACES
                 + " WHERE (LOWER(name) LIKE ? OR LOWER(address) LIKE ? OR LOWER(locality) LIKE ?"
                 + " OR LOWER(fields) LIKE ?)"
                 + (publishedOnly ? " AND published = TRUE" : "")
                 + " ORDER BY name " + store.dialect().limit(limit))) {
      for (int k = 1; k <= 4; k++) {
        statement.setString(k, like);
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          Place place = readPlace(rows);
          if (needle.isEmpty() || matches(place, needle)) {
            places.add(place);
          }
        }
      }
    }
    return places;
  }

  private static boolean matches(Place place, String needle) {
    for (String text : new String[]{place.name(), place.address(), place.locality(),
        place.region(), place.body()}) {
      if (text != null && text.toLowerCase().contains(needle)) {
        return true;
      }
    }
    for (String value : place.values().values()) {
      if (value != null && value.toLowerCase().contains(needle)) {
        return true;
      }
    }
    return false;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  /** what somebody typed, as something that can live in a URL */
  public static String slugify(String raw) {
    if (raw == null) {
      return null;
    }
    String clean = raw.trim().toLowerCase()
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    return clean.isEmpty() || clean.length() > 128 ? null : clean;
  }

  /**
   * Field values as a blob, keeping only what the type actually declared.
   *
   * Anything else somebody posted is dropped rather than stored. A blob that accumulates keys from
   * a form that has since changed is a blob nobody can reason about, and the declaration is the
   * only thing that says what a place of this kind has.
   */
  public static String valuesToBlob(List<TemplateField> declared, Map<String, String> given) {
    ObjectNode node = JSON.createObjectNode();
    for (TemplateField field : declared) {
      String value = given == null ? null : given.get(field.name());
      node.put(field.name(), value == null ? "" : value);
    }
    return node.toString();
  }

  /**
   * The same, but keeping what a previous kind recorded.
   *
   * Two different things are going on and they deserve different answers. A *form* posting a key the
   * current kind never declared is noise, and is dropped. A value already stored under a kind this
   * place used to have is somebody's typing, and moving it to another heading should not destroy it
   * -- move it back and it is there again.
   *
   * So: start from what is stored, overwrite what the current kind asked about, keep the rest.
   */
  public static String mergeValues(String existingBlob, List<TemplateField> declared,
                                   Map<String, String> given) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>();
    try {
      JsonNode node = JSON.readTree(existingBlob == null || existingBlob.isBlank()
          ? "{}" : existingBlob);
      node.fieldNames().forEachRemaining(name -> merged.put(name, node.get(name).asText("")));
    } catch (Exception ex) {
      // an unreadable blob starts empty rather than failing a save
      merged.clear();
    }
    // everything the caller is holding wins over what is stored -- that set is the declared fields
    // from the form *plus* whatever the editor carried from a kind no longer on screen, and both
    // are newer than the database. Filtering to declared here would drop exactly the answers the
    // carry exists to save.
    if (given != null) {
      merged.putAll(given);
    }
    for (TemplateField field : declared) {
      merged.putIfAbsent(field.name(), "");
    }
    ObjectNode out = JSON.createObjectNode();
    merged.forEach(out::put);
    return out.toString();
  }

  private static void bindType(PreparedStatement statement, String label, String plural,
                               String description, String fields, String templateName, String icon,
                               boolean published, int sort) throws SQLException {
    statement.setString(1, cap(label, 64));
    statement.setString(2, cap(plural, 64));
    statement.setString(3, cap(description, 1024));
    statement.setString(4, fields);
    statement.setString(5, cap(templateName, 128));
    statement.setString(6, cap(icon, 32));
    statement.setBoolean(7, published);
    statement.setInt(8, sort);
  }

  private static void bindPlace(PreparedStatement statement, Place place, Long actor)
      throws SQLException {
    statement.setString(1, slugify(place.typeSlug()));
    statement.setString(2, slugify(place.slug()));
    statement.setString(3, cap(place.name(), 256));
    statement.setString(4, cap(place.address(), 1024));
    statement.setString(5, cap(place.locality(), 128));
    statement.setString(6, cap(place.region(), 128));
    statement.setString(7, cap(place.postcode(), 32));
    statement.setString(8, cap(place.country(), 64));
    setDouble(statement, 9, place.latitude());
    setDouble(statement, 10, place.longitude());
    statement.setString(11, cap(place.url(), 512));
    statement.setString(12, cap(place.phone(), 64));
    statement.setString(13, cap(place.email(), 320));
    statement.setString(14, place.fields() == null ? "{}" : place.fields());
    statement.setString(15, place.body() == null ? "" : place.body());
    statement.setBoolean(16, place.published());
    statement.setBoolean(17, place.humanOnly());
    if (actor == null) {
      statement.setNull(18, java.sql.Types.BIGINT);
    } else {
      statement.setLong(18, actor);
    }
  }

  private static void setDouble(PreparedStatement statement, int index, Double value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.DOUBLE);
    } else {
      statement.setDouble(index, value);
    }
  }

  private static Type readType(ResultSet rows) throws SQLException {
    return new Type(rows.getLong("id"), rows.getString("slug"), rows.getString("label"),
        rows.getString("plural"), rows.getString("description"),
        TemplateField.parse(rows.getString("fields")), rows.getString("template_name"),
        rows.getString("icon"), rows.getBoolean("published"), rows.getInt("sort"),
        rows.getTimestamp("created_at"), rows.getTimestamp("updated_at"));
  }

  private static Place readPlace(ResultSet rows) throws SQLException {
    Double latitude = rows.getDouble("latitude");
    if (rows.wasNull()) {
      latitude = null;
    }
    Double longitude = rows.getDouble("longitude");
    if (rows.wasNull()) {
      longitude = null;
    }
    Long updatedBy = rows.getLong("updated_by");
    if (rows.wasNull()) {
      updatedBy = null;
    }
    return new Place(rows.getLong("id"), rows.getString("type_slug"), rows.getString("slug"),
        rows.getString("name"), rows.getString("address"), rows.getString("locality"),
        rows.getString("region"), rows.getString("postcode"), rows.getString("country"),
        latitude, longitude, rows.getString("url"), rows.getString("phone"),
        rows.getString("email"), rows.getString("fields"), rows.getString("body"),
        rows.getBoolean("published"), rows.getBoolean("human_only"),
        rows.getTimestamp("created_at"), rows.getTimestamp("updated_at"), updatedBy);
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
