package io.hearth.mcp;

import io.hearth.auth.Accounts;
import io.hearth.content.ContentRecord;
import io.hearth.content.TemplateField;
import io.hearth.places.Places;
import io.hearth.content.TemplateField;
import io.hearth.content.TemplateRecord;
import io.hearth.people.AnswerSheet;
import io.hearth.people.Question;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything an agent is allowed to do, and the only way it can do it.
 *
 * This class exists to be the single narrow place where "is AI allowed to touch this" is answered.
 * The tools above it are shaped for a model; the stores below it know nothing about models. Putting
 * the check anywhere else -- in each tool, say -- would mean the rule is enforced N times and can be
 * forgotten once, which for a rule like this is the same as not having it.
 *
 * **Human only** is asymmetric on purpose, and the asymmetry is worth stating plainly:
 *
 * - **Reads are invisible.** A locked page is not in a listing, not in a search result, and not
 *   fetchable. Not "forbidden" -- absent. An agent has no way to learn it exists.
 * - **Writes are refused, out loud.** Saving to a locked uri comes back as a refusal that says so.
 *
 * The tempting symmetry -- refuse quietly, or pretend the write worked -- is wrong both ways. If a
 * locked uri simply looked empty to a write, an agent asked to "add an about page" would happily
 * overwrite the one thing somebody locked; and a write that claims success while doing nothing
 * teaches a model that it succeeded. Leaking the existence of a uri to something that was already
 * being told "no" is a much smaller problem than either.
 */
public class AiSurface {
  /** how much of a body to hand back in a listing before it stops being a listing */
  private static final int SNIPPET = 400;
  private static final int MAX_ROWS = 200;

  private final Accounts accounts;
  private final boolean readOnly;

  /**
   * How long a post lives here, so a model's conversation expires like everybody else's.
   *
   * Told rather than looked up, because this class deliberately knows about stores and not about
   * domains -- and a post that outlived every other post because a robot wrote it would be a
   * strange thing to discover on a board with a fortnight's horizon.
   */
  private int boardExpiryDays;

  public AiSurface withBoardExpiry(int days) {
    this.boardExpiryDays = Math.max(0, days);
    return this;
  }

  public AiSurface(Accounts accounts, boolean readOnly) {
    this.accounts = accounts;
    this.readOnly = readOnly;
  }

  /** a refusal an agent can read and act on */
  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  /**
   * May the person this connection belongs to do this?
   *
   * <b>An agent can never do anything the person could not.</b> That is invariant 26 stated as a
   * rule about sessions; this is it enforced as a rule about tools. Before this, every tool was
   * reachable by any connection, which was defensible only while the endpoint was admin-only and
   * every admin held everything -- and stopped being defensible the moment the board grew things a
   * plain member may do and a moderator may not.
   *
   * The refusal names the permission, because the model's next useful move is to tell the person
   * holding the connection what they are missing rather than to try a different phrasing.
   */
  private void assertCan(io.hearth.auth.Permission permission) throws SQLException, Refused {
    io.hearth.auth.UserRecord me = actorId == null ? null : accounts.users.byId(actorId);
    if (me == null) {
      throw new Refused("This connection is not acting for anybody.");
    }
    if (!accounts.access.can(me, permission)) {
      throw new Refused("The person this connection belongs to is not allowed to do that. It"
          + " needs '" + permission.label + "'. Do not try another way round it -- tell them, and"
          + " somebody who can grant it will.");
    }
  }

  /**
   * The quiet half of the same question.
   *
   * <b>A read is narrowed, never refused.</b> When the endpoint was admin-only, every connection
   * could see drafts, suggestions and unpublished places, because every connection belonged to
   * somebody who could see them on a screen. Now that a member can connect, the honest answer to
   * "list the events" from a member's agent is *the events they can see*, not a refusal -- a
   * refusal would make the tool useless for the person it belongs to, and returning everything
   * would hand them a draft they could not open in a browser.
   *
   * This is the same asymmetry human-only already has (invariant 28): what somebody may not see is
   * absent rather than forbidden.
   */
  /** the same question, for the listing that decides which tools to offer at all */
  public boolean may(io.hearth.auth.Permission permission) throws SQLException {
    return mayI(permission);
  }

  private boolean mayI(io.hearth.auth.Permission permission) throws SQLException {
    io.hearth.auth.UserRecord me = actorId == null ? null : accounts.users.byId(actorId);
    return me != null && accounts.access.can(me, permission);
  }

  private void assertWritable() throws Refused {
    if (readOnly) {
      throw new Refused("This site is connected read only. An admin can change that with"
          + " mcp.read-only in the domain config.");
    }
  }

  // ---- content ---------------------------------------------------------------------------------

  /**
   * Every page an agent may know about.
   *
   * The filter is applied here rather than in SQL because "invisible to AI" is a property of this
   * surface, not of the content table -- the admin listing right next door has to show these pages,
   * and a WHERE clause in the store would eventually be copied into a query that should not have it.
   */
  public List<ContentRecord> visibleContent() throws SQLException {
    // a draft is a page somebody has not published; an agent whose person could not open it in a
    // browser has no business reading it through a tool
    boolean drafts = mayI(io.hearth.auth.Permission.content_read);
    ArrayList<ContentRecord> visible = new ArrayList<>();
    for (ContentRecord page : accounts.site.store().allContent(MAX_ROWS)) {
      if (!page.humanOnly() && (drafts || page.published())) {
        visible.add(page);
      }
    }
    return visible;
  }

  public List<Map<String, Object>> listContent(String folder, Boolean published) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (folder != null && !folder.isBlank() && !folder.equalsIgnoreCase(page.navFolder())) {
        continue;
      }
      if (published != null && published != page.published()) {
        continue;
      }
      rows.add(summarize(page));
    }
    return rows;
  }

  /** contains-of across uri, title and body; the same search a person gets in the admin */
  public List<Map<String, Object>> searchContent(String query) throws SQLException {
    String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (needle.isEmpty() || contains(needle, page.uri(), page.title(), page.body())) {
        Map<String, Object> row = summarize(page);
        row.put("excerpt", excerpt(page.body(), needle));
        rows.add(row);
      }
    }
    return rows;
  }

  /** one page in full, or null when there is no such page an agent may see */
  public Map<String, Object> getContent(String uri) throws SQLException {
    ContentRecord page = accounts.site.store().byUri(uri);
    if (page == null || page.humanOnly()) {
      return null;
    }
    // The listing was narrowed and this was not, which is the oldest shape of this bug: a filter on
    // the index and none on the direct fetch, so anything unpublished was one exact uri away. A
    // draft is a page somebody has not published, and an agent whose person cannot open it in a
    // browser must not read it here either.
    if (!page.published() && !mayI(io.hearth.auth.Permission.content_read)) {
      return null;
    }
    Map<String, Object> full = summarize(page);
    full.put("body", page.body());
    full.put("fields", page.fields());
    return full;
  }

  /**
   * Create or update a page.
   *
   * Update semantics are a merge against what is there: a field the caller did not mention keeps
   * its value. A model asked to "fix the typo in the third paragraph" should not have to resend the
   * title, the template and the folder to avoid clearing them, and one that forgets should not
   * silently wipe them.
   */
  public Map<String, Object> saveContent(String uri, Map<String, Object> changes) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.content_write);
    if (uri == null || !uri.startsWith("/") || uri.length() > 512) {
      throw new Refused("uri must be an absolute path like /about");
    }
    ContentRecord existing = accounts.site.store().byUri(uri);
    if (existing != null && existing.humanOnly()) {
      throw new Refused("'" + uri + "' is marked human only. AI cannot read or change it,"
          + " and an admin has to unlock it first.");
    }
    ContentRecord.Kind kind = changes.containsKey("kind")
        ? ContentRecord.Kind.of(str(changes, "kind"))
        : (existing == null ? ContentRecord.Kind.markdown : existing.kind());
    String template = changes.containsKey("template")
        ? str(changes, "template")
        : (existing == null ? null : existing.templateName());
    if (template != null && !template.isBlank() && accounts.site.store().templateByName(template) == null) {
      throw new Refused("there is no template called '" + template + "'");
    }
    ContentRecord page = new ContentRecord(
        existing == null ? 0 : existing.id(),
        uri,
        changes.containsKey("title") ? str(changes, "title") : (existing == null ? "" : existing.title()),
        kind,
        kind.wantsTemplate() ? template : null,
        changes.containsKey("folder") ? str(changes, "folder") : (existing == null ? "" : existing.navFolder()),
        existing == null ? "{}" : existing.fields(),
        changes.containsKey("body") ? str(changes, "body") : (existing == null ? "" : existing.body()),
        changes.containsKey("published") ? bool(changes, "published") : (existing == null || existing.published()),
        // an agent can never set this bit; only a person at the admin screen can
        existing != null && existing.humanOnly(),
        null, null, actor());
    ContentRecord saved = accounts.site.store().save(page, actor(), actorEmail);
    Map<String, Object> result = summarize(saved);
    result.put("created", existing == null);
    return result;
  }

  public Map<String, Object> deleteContent(String uri) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.content_write);
    ContentRecord page = accounts.site.store().byUri(uri);
    if (page == null) {
      throw new Refused("there is no page at '" + uri + "'");
    }
    if (page.humanOnly()) {
      throw new Refused("'" + uri + "' is marked human only. AI cannot read or change it.");
    }
    accounts.site.store().deleteContent(page.id(), actor());
    return Map.of("uri", uri, "deleted", true);
  }

  private static Map<String, Object> summarize(ContentRecord page) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("uri", page.uri());
    row.put("title", page.title());
    row.put("kind", page.kind().name());
    row.put("template", page.templateName() == null ? "" : page.templateName());
    row.put("folder", page.navFolder() == null ? "" : page.navFolder());
    row.put("published", page.published());
    row.put("updated", page.updatedAt() == null ? "" : page.updatedAt().toString());
    row.put("length", page.body() == null ? 0 : page.body().length());
    return row;
  }

  // ---- templates -------------------------------------------------------------------------------

  public List<Map<String, Object>> listTemplates() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (TemplateRecord template : accounts.site.store().allTemplates(MAX_ROWS)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", template.name());
      row.put("fields", fieldNames(template));
      row.put("used_by", accounts.site.store().urisUsingTemplate(template.name()).size());
      row.put("length", template.body() == null ? 0 : template.body().length());
      rows.add(row);
    }
    return rows;
  }

  public Map<String, Object> getTemplate(String name) throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    TemplateRecord template = accounts.site.store().templateByName(name);
    if (template == null) {
      return null;
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("name", template.name());
    row.put("body", template.body());
    row.put("fields", fieldNames(template));
    row.put("used_by", accounts.site.store().urisUsingTemplate(template.name()));
    return row;
  }

  /**
   * Write a template.
   *
   * Saving one re-renders every page that uses it, so the result says how many -- a model that just
   * changed forty pages should be told it changed forty pages, and so should whoever reads the log
   * afterwards.
   */
  public Map<String, Object> saveTemplate(String name, String body) throws SQLException, Refused {
    return saveTemplate(name, body, Map.of());
  }

  /**
   * @param index the directory half, when a model is writing one: `directory`, `directory_path`,
   *     `directory_pattern`, `directory_body`, `directory_page_size`, `directory_order`. Absent
   *     keys keep whatever the template already says, so writing a body does not switch somebody's
   *     blog off.
   */
  public Map<String, Object> saveTemplate(String name, String body, Map<String, Object> index)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.templates_write);
    if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
      throw new Refused("a template name is letters, digits, underscore or hyphen");
    }
    if (body == null) {
      throw new Refused("body is required");
    }
    TemplateRecord existing = accounts.site.store().templateByName(name);
    String parameters = existing == null ? "[]" : existing.parameters();
    boolean directory = index.containsKey("directory") ? bool(index, "directory")
        : existing != null && existing.directory();
    String path = index.containsKey("directory_path") ? str(index, "directory_path")
        : (existing == null ? "" : existing.directoryPath());
    if (directory && (path == null || !path.startsWith("/"))) {
      throw new Refused("a directory index needs directory_path, starting with '/'");
    }
    String pattern = index.containsKey("directory_pattern") ? str(index, "directory_pattern")
        : (existing == null ? "" : existing.directoryPattern());
    if (directory && pattern != null && !pattern.isBlank()
        && !pattern.contains(TemplateRecord.PAGE_TOKEN)) {
      throw new Refused("directory_pattern needs " + TemplateRecord.PAGE_TOKEN
          + " in it, which is where the page number goes");
    }
    String indexBody = index.containsKey("directory_body") ? str(index, "directory_body")
        : (existing == null ? "" : existing.directoryBody());
    accounts.site.store().saveTemplate(name, body, parameters, directory,
        path == null ? "" : path, pattern == null ? "" : pattern,
        indexBody == null ? "" : indexBody,
        intOr(index, "directory_page_size", existing == null ? 10 : existing.pageSize()),
        "oldest".equals(index.get("directory_order")) ? "oldest" : "newest", actor());
    List<String> affected = accounts.site.store().urisUsingTemplate(name);
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("created", existing == null);
    result.put("re_rendered", affected.size());
    result.put("pages", affected);
    TemplateRecord saved = accounts.site.store().templateByName(name);
    if (saved != null && saved.publishesDirectory()) {
      result.put("index_at", saved.directoryPath());
      result.put("index_is_its_own_template", saved.hasOwnIndex());
    }
    return result;
  }

  /**
   * How to build a site here, in one answer.
   *
   * <b>A tool description is a prompt, and this is the longest one.</b> A model asked to build a
   * community's website has no screen to read: it cannot see that picking "HTML Member Listing"
   * makes the uri field mean something different, or that a place listing can be narrowed to one
   * kind. Every one of those rules lives in exactly one place in the code, and this hands that
   * place to whoever is writing the pages -- so an agent's first attempt is a working site rather
   * than six refusals it has to learn from.
   */
  public Map<String, Object> siteSpec() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
    ArrayList<Map<String, Object>> kinds = new ArrayList<>();
    for (io.hearth.content.ContentRecord.Kind kind
        : io.hearth.content.ContentRecord.Kind.values()) {
      LinkedHashMap<String, Object> one = new LinkedHashMap<>();
      one.put("kind", kind.name());
      one.put("label", kind.label);
      one.put("what_it_is", kind.describe);
      one.put("uri", kind.uriRule());
      one.put("wraps_in_a_template", kind.wantsTemplate());
      if (kind.isFeed()) {
        one.put("body_is", "a mustache template, not markdown -- a listing is a loop");
        one.put("gives", switch (kind.source) {
          case events -> "{{#events}} id title summary starts_on ends_on time where going limited"
              + " capacity seats_left full open_to_public over today ics_url {{/events}}";
          case places -> "{{#places}} id name kind slug address summary latitude longitude located"
              + " {{/places}}";
          case members -> "{{#members}} id name where summary joined {{/members}}";
          default -> "";
        });
        if (kind.listing) {
          one.put("pagination", "{{pagination.page}} {{pagination.pages}} {{pagination.count}}"
              + " {{#pagination.has_next}}{{pagination.next_url}}{{/pagination.has_next}}"
              + " {{pagination.next_id}} {{#pagination.numbers}} n url here {{/pagination.numbers}}");
        } else {
          one.put("gives_also", "the same keys at the top level, plus {{{body_html}}}");
        }
        if (!kind.settings().isEmpty()) {
          one.put("settings", kind.settings());
        }
        if (!kind.sorts().isEmpty()) {
          one.put("sort", "one of " + String.join(", ", kind.sorts())
              + "; the first is the default");
        }
      }
      kinds.add(one);
    }
    spec.put("page_kinds", kinds);
    ArrayList<String> placeKinds = new ArrayList<>();
    placeKinds.add("* -- every kind");
    for (io.hearth.places.Places.Type type : accounts.places.allTypes()) {
      placeKinds.add(type.slug() + " -- " + type.pluralOr());
    }
    spec.put("place_kind_values", placeKinds);
    spec.put("published_date", "every page has one; it defaults to the first save and orders every"
        + " listing. Set it when moving old writing in, so 2011 says 2011.");
    spec.put("directory_index", "a template can publish an index of every page using it, at an"
        + " address of its own. It is a second template with its own body -- see template_save.");
    spec.put("attachments", "uploaded files are served at /attachment/<id>.<ext> and can be"
        + " embedded in any page body.");
    return spec;
  }

  public Map<String, Object> deleteTemplate(String name) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.templates_write);
    TemplateRecord template = accounts.site.store().templateByName(name);
    if (template == null) {
      throw new Refused("there is no template called '" + name + "'");
    }
    List<String> affected = accounts.site.store().urisUsingTemplate(name);
    if (!affected.isEmpty()) {
      throw new Refused("'" + name + "' is still used by " + affected.size()
          + " page(s); move them off it first: " + String.join(", ", affected));
    }
    accounts.site.store().deleteTemplate(template.id(), actor());
    return Map.of("name", name, "deleted", true);
  }

  private static List<String> fieldNames(TemplateRecord template) {
    ArrayList<String> names = new ArrayList<>();
    for (TemplateField field : template.fields()) {
      names.add(field.name() + ":" + field.type().name() + (field.required() ? " (required)" : ""));
    }
    return names;
  }

  // ---- navigation ------------------------------------------------------------------------------

  public Map<String, Object> navigation() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    LinkedHashMap<String, Object> tree = new LinkedHashMap<>();
    ArrayList<String> loose = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (page.isOutsideNavigation()) {
        loose.add(page.uri());
        continue;
      }
      @SuppressWarnings("unchecked")
      List<String> bucket = (List<String>) tree.computeIfAbsent(page.navFolder(), key -> new ArrayList<String>());
      bucket.add(page.uri());
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("folders", tree);
    result.put("outside_navigation", loose);
    return result;
  }

  // ---- survey ----------------------------------------------------------------------------------

  public List<Map<String, Object>> listQuestions() throws SQLException {
    // The questions themselves are on /survey for every member, so this is offered to anybody --
    // but a draft is a question nobody has decided to ask yet, and how many people have answered
    // is a fact about the community rather than about the question.
    boolean runsIt = mayI(io.hearth.auth.Permission.survey_write);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Question question : accounts.people.allQuestions()) {
      if (!runsIt && !question.published()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("prompt", question.prompt());
      row.put("help", question.help());
      row.put("kind", question.kind().name());
      row.put("options", question.options());
      row.put("required", question.required());
      row.put("published", question.published());
      row.put("position", question.position());
      if (runsIt) {
        row.put("answers", accounts.survey.answersFor(question.id()));
      }
      rows.add(row);
    }
    return rows;
  }

  public Map<String, Object> askQuestion(Map<String, Object> spec) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.survey_write);
    String prompt = str(spec, "prompt");
    if (prompt == null || prompt.isBlank()) {
      throw new Refused("prompt is required");
    }
    Question.Kind kind = Question.Kind.of(str(spec, "kind"));
    List<String> options = strings(spec, "options");
    if (kind == Question.Kind.choice && options.isEmpty()) {
      throw new Refused("a choice question needs at least one option");
    }
    String definition = Question.definition(kind, prompt, str(spec, "help"), options,
        intOr(spec, "min", 1), intOr(spec, "max", 5), bool(spec, "required"));
    Question asked = accounts.people.askQuestion(definition, intOr(spec, "position", 0),
        !spec.containsKey("published") || bool(spec, "published"), actor());
    return Map.of("id", asked.id(), "prompt", asked.prompt(), "kind", asked.kind().name(),
        "published", asked.published());
  }

  public Map<String, Object> updateQuestion(long id, Map<String, Object> spec) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.survey_write);
    Question existing = accounts.people.questionById(id);
    if (existing == null || existing.deleted()) {
      throw new Refused("there is no question with id " + id);
    }
    Question.Kind kind = spec.containsKey("kind") ? Question.Kind.of(str(spec, "kind")) : existing.kind();
    List<String> options = spec.containsKey("options") ? strings(spec, "options") : existing.options();
    if (kind == Question.Kind.choice && options.isEmpty()) {
      throw new Refused("a choice question needs at least one option");
    }
    String definition = Question.definition(kind,
        spec.containsKey("prompt") ? str(spec, "prompt") : existing.prompt(),
        spec.containsKey("help") ? str(spec, "help") : existing.help(),
        options,
        intOr(spec, "min", existing.min()),
        intOr(spec, "max", existing.max()),
        spec.containsKey("required") ? bool(spec, "required") : existing.required());
    accounts.people.updateQuestion(id, definition, intOr(spec, "position", existing.position()),
        spec.containsKey("published") ? bool(spec, "published") : existing.published(), actor());
    return Map.of("id", id, "updated", true);
  }

  /**
   * Put a retired question back.
   *
   * The other half of the soft delete, and the reason the delete is soft: answers were never
   * removed, so restoring one starts counting them again exactly as they were. Without this a
   * model could stop asking something and had no way to change its mind, which is a strange thing
   * to be able to do only in one direction.
   */
  public Map<String, Object> restoreQuestion(long id) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.survey_write);
    Question existing = accounts.people.questionById(id);
    if (existing == null) {
      throw new Refused("there is no question with id " + id);
    }
    accounts.people.restoreQuestion(id, actor());
    return Map.of("id", id, "asking", true,
        "note", "Its answers were never deleted, so they count again as they were.");
  }

  /**
   * The order people are asked in.
   *
   * Worth more than it looks: the survey shows three questions at a time, so what is first is what
   * most people answer. A model rewriting a community's questions has to be able to decide that,
   * or it can only ever append.
   */
  public Map<String, Object> reorderQuestions(List<Long> order) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.survey_write);
    if (order == null || order.isEmpty()) {
      throw new Refused("a list of question ids, first to last");
    }
    int position = 0;
    java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>(order);
    for (Long id : seen) {
      Question question = accounts.people.questionById(id);
      if (question == null || question.deleted()) {
        throw new Refused("there is no question with id " + id);
      }
      accounts.people.updateQuestion(id, definitionOf(question), position++,
          question.published(), actor());
    }
    // anything not named keeps its place after the ones that were, rather than being shuffled to
    // the front by an id nobody mentioned
    for (Question question : accounts.people.allQuestions()) {
      if (!seen.contains(question.id())) {
        accounts.people.updateQuestion(question.id(), definitionOf(question), position++,
            question.published(), actor());
      }
    }
    return Map.of("ordered", seen.size(), "total", position);
  }

  /** a question written back out as the blob it is stored as, unchanged */
  private static String definitionOf(Question question) {
    return Question.definition(question.kind(), question.prompt(), question.help(),
        question.options(), question.min(), question.max(), question.required());
  }

  /** a soft delete, same as the admin button; the cleanup is a person's decision */
  public Map<String, Object> deleteQuestion(long id) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.survey_write);
    Question existing = accounts.people.questionById(id);
    if (existing == null || existing.deleted()) {
      throw new Refused("there is no question with id " + id);
    }
    accounts.people.deleteQuestion(id, actor());
    return Map.of("id", id, "deleted", true,
        "note", "Answers are kept. An admin commits the cleanup from the admin survey page.");
  }

  /**
   * Every answer to one question, for an agent working through the survey a question at a time.
   *
   * <b>This is the shape the work actually has.</b> A model asked "what is the community telling
   * us" across forty questions and two hundred people is reading a wall; the same model given one
   * question and everything said about it produces something worth reading, and the summaries
   * combine afterwards. So an agent lists the questions, fans them out one per worker, and puts the
   * answers back together -- which is only possible if the per-question read exists.
   *
   * Numbered rather than named, like every other read of the survey. The numbering is stable across
   * calls within a sweep, so an agent reading two questions can tell that respondent 4 answered
   * both -- which is most of what makes a cross-question reading possible -- without ever learning
   * who respondent 4 is.
   */
  public Map<String, Object> answersTo(long questionId) throws SQLException, Refused {
    // everybody's answers to one question: the community's, not the reader's
    assertCan(io.hearth.auth.Permission.survey_write);
    Question question = accounts.people.questionById(questionId);
    if (question == null || question.deleted()) {
      throw new Refused("there is no question with id " + questionId);
    }
    List<Long> responders = accounts.people.everybodyWithAnswers();
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", question.id());
    out.put("prompt", question.prompt());
    out.put("kind", question.kind().name());
    out.put("help", question.help());
    out.put("published", question.published());

    ArrayList<Map<String, Object>> answers = new ArrayList<>();
    TreeMap<String, Integer> tally = new TreeMap<>();
    int index = 0;
    int silent = 0;
    for (long userId : responders) {
      index++;
      String answer = accounts.people.answersOf(userId).answerTo(question.id());
      if (answer == null || answer.isBlank()) {
        silent++;
        continue;
      }
      answers.add(Map.of("respondent", index, "answer", answer));
      if (question.kind() != Question.Kind.free) {
        tally.merge(answer, 1, Integer::sum);
      }
    }
    out.put("answers", answers);
    out.put("answered", answers.size());
    out.put("did_not_answer", silent);
    if (question.kind() != Question.Kind.free) {
      out.put("tally", tally);
    }
    return out;
  }

  /**
   * Everything somebody asked a person to look at.
   *
   * Read-only, and deliberately so: there is no tool here that takes anything down. Triage means a
   * model can gather, summarize and recommend -- "three of these are the same argument, this one is
   * somebody having a bad day, this one needs a person today" -- and a human still decides. That is
   * the division of labour this whole feature exists for, and the way to enforce it is to not
   * build the other half rather than to check a permission.
   */
  public List<Map<String, Object>> flagged() throws SQLException, Refused {
    // the flag queue is a moderator's screen: it is a list of what somebody thought needed looking
    // at, which is not the same information as the board itself
    assertCan(io.hearth.auth.Permission.board_moderate);
    LinkedHashMap<String, List<io.hearth.board.Signals.Signal>> grouped = new LinkedHashMap<>();
    for (io.hearth.board.Signals.Signal signal : accounts.signals.openFlags(200)) {
      grouped.computeIfAbsent(signal.subject().key(), key -> new ArrayList<>()).add(signal);
    }
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (List<io.hearth.board.Signals.Signal> flags : grouped.values()) {
      io.hearth.board.Subject subject = flags.get(0).subject();
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("what", subject.kind().name());
      row.put("id", subject.id());
      row.put("flags", flags.size());
      ArrayList<String> reasons = new ArrayList<>();
      for (io.hearth.board.Signals.Signal flag : flags) {
        if (flag.reason() != null && !flag.reason().isBlank()) {
          reasons.add(flag.reason());
        }
      }
      row.put("reasons", reasons);
      if (subject.kind() == io.hearth.board.Subject.Kind.comment) {
        io.hearth.board.Board.Comment comment = accounts.board.commentById(subject.id());
        row.put("said", comment == null || comment.removed() ? null : comment.body());
        row.put("who", comment == null ? null
            : io.hearth.people.Names.nameOf(accounts, comment.authorId()));
        row.put("already_removed", comment == null || comment.removed());
      } else if (subject.kind() == io.hearth.board.Subject.Kind.post) {
        io.hearth.board.Board.Post post = accounts.board.postById(subject.id());
        row.put("title", post == null ? null : post.title());
        row.put("said", post == null || post.removed() ? null : post.body());
        row.put("who", post == null ? null
            : io.hearth.people.Names.nameOf(accounts, post.authorId()));
        row.put("already_removed", post == null || post.removed());
      }
      out.add(row);
    }
    return out;
  }

  /**
   * One event and everything said about it, with each comment placed in time.
   *
   * <b>Before, during and after are three different conversations</b> and reading them as one loses
   * the thing worth knowing. Before is questions and logistics -- what somebody needed answered in
   * order to come. During is what happened. After is what people made of it, which is the only
   * material there is for deciding whether to do it again. A model asked "how did the supper club
   * go" wants the third; one asked "what do people need to know" wants the first.
   */
  public Map<String, Object> eventContext(long eventId) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.board_read);
    io.hearth.calendar.Calendar.Event event = accounts.calendar.byId(eventId);
    if (event == null) {
      return null;
    }
    // the same rule getEvent follows: an event nobody has announced is absent, and its guest list
    // is the last thing that should arrive through a side door
    if ((!event.published() || event.suggested())
        && !mayI(io.hearth.auth.Permission.calendar_review)) {
      return null;
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", event.id());
    out.put("title", event.title());
    out.put("starts_on", event.startsOn().toString());
    out.put("ends_on", event.endsOn().toString());
    out.put("time", event.startTime());
    out.put("location", event.location());
    out.put("body", event.body());
    out.put("cancelled", event.cancelled());
    out.put("going", event.goingCount());
    out.put("maybe", event.maybeCount());
    out.put("waitlist", event.waitlistCount());

    ArrayList<Map<String, Object>> guests = new ArrayList<>();
    int noShows = 0;
    for (io.hearth.calendar.Calendar.Rsvp rsvp : accounts.calendar.guestList(eventId)) {
      guests.add(Map.of("who", io.hearth.people.Names.nameOf(accounts, rsvp.userId()),
          "answer", rsvp.answer().name(), "party", rsvp.party(),
          "answered_from", rsvp.fromEmail() ? "their calendar" : "the site",
          "was_not_there", rsvp.noShow()));
      if (rsvp.noShow()) {
        noShows++;
      }
    }
    out.put("guests", guests);
    out.put("did_not_turn_up", noShows);

    ArrayList<Map<String, Object>> said = new ArrayList<>();
    io.hearth.board.Subject subject =
        new io.hearth.board.Subject(io.hearth.board.Subject.Kind.event, eventId);
    for (io.hearth.board.Board.Comment comment : accounts.board.thread(subject)) {
      if (comment.removed()) {
        continue;
      }
      said.add(Map.of("who", io.hearth.people.Names.nameOf(accounts, comment.authorId()),
          "when", String.valueOf(comment.createdAt()),
          "phase", io.hearth.board.CommentPhase.of(event, comment.createdAt()).name(),
          "said", comment.body()));
    }
    out.put("comments", said);
    return out;
  }

  /**
   * Every answer to every question, aggregated for reading.
   *
   * The point of the whole survey feature: hand this to a model and ask what the community is
   * telling you. Free text comes back verbatim because that is where the substance is; choices and
   * ratings come back tallied because forty copies of "blue" is not an insight.
   *
   * Answers are attributed by a stable index rather than by email. A model summarizing what a
   * community said does not need to know who said it, and once the summary exists somewhere else,
   * neither does anybody who gets hold of it.
   */
  public Map<String, Object> summarizeSurvey(boolean includeText) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.survey_write);
    List<Question> questions = accounts.people.publishedQuestions();
    List<Long> responders = accounts.people.everybodyWithAnswers();
    Map<Long, Integer> index = new LinkedHashMap<>();
    for (long userId : responders) {
      index.put(userId, index.size() + 1);
    }

    ArrayList<Map<String, Object>> summaries = new ArrayList<>();
    for (Question question : questions) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("prompt", question.prompt());
      row.put("kind", question.kind().name());
      int answered = 0;
      TreeMap<String, Integer> tally = new TreeMap<>();
      ArrayList<Map<String, Object>> texts = new ArrayList<>();
      long ratingTotal = 0;
      for (long userId : responders) {
        AnswerSheet sheet = accounts.people.answersOf(userId);
        String answer = sheet.answerTo(question.id());
        if (answer == null || answer.isBlank()) {
          continue;
        }
        answered++;
        if (question.kind() == Question.Kind.free) {
          if (includeText) {
            texts.add(Map.of("respondent", index.get(userId), "answer", answer));
          }
        } else {
          tally.merge(answer, 1, Integer::sum);
          if (question.kind() == Question.Kind.rating) {
            try {
              ratingTotal += Long.parseLong(answer.trim());
            } catch (NumberFormatException ex) {
              // a rating that is not a number is a question that changed kind under an old answer
            }
          }
        }
      }
      row.put("answered", answered);
      row.put("unanswered", responders.size() - answered);
      if (question.kind() == Question.Kind.free) {
        row.put("answers", texts);
      } else {
        row.put("tally", tally);
        if (question.kind() == Question.Kind.rating && answered > 0) {
          row.put("average", Math.round((double) ratingTotal / answered * 100.0) / 100.0);
        }
      }
      summaries.add(row);
    }

    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("respondents", responders.size());
    result.put("members", accounts.users.count());
    result.put("questions", summaries);
    result.put("note", "Respondents are numbered, not named. Ask an admin if you need identities.");
    return result;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Long actor() {
    return actorId;
  }

  /** the community's clock, because "closes on Friday" is a fact about a place */
  private java.time.ZoneId zone = java.time.ZoneOffset.UTC;
  private io.hearth.board.PollClock pollClock;
  private io.hearth.vhost.DomainConfig domainConfig;

  /**
   * The community this connection is for.
   *
   * Needed by exactly one thing -- closing a poll early, which counts it and may put an event in
   * the calendar, and therefore needs the same code path the background sweep uses. Two ways to
   * settle a poll would be two answers to what a tie means.
   */
  public AiSurface inCommunity(io.hearth.vhost.DomainConfig config,
                               io.hearth.board.PollClock clock) {
    this.domainConfig = config;
    this.pollClock = clock;
    this.zone = config == null ? java.time.ZoneOffset.UTC : config.zone;
    return this;
  }

  private Long actorId;

  private String actorEmail;

  /** whose authority this surface acts under; set once when the request is authenticated */
  // ---- the address book -------------------------------------------------------------------------

  /**
   * The kinds of place this community keeps, and what each records.
   *
   * The field list is included because a model that cannot see what a "ranch" has cannot fill one
   * in -- and inventing keys that the type never declared produces a place whose extras are
   * silently dropped on save.
   */
  // ---- events -------------------------------------------------------------------------------

  /**
   * The calendar, which a model may keep.
   *
   * The most obviously useful thing a model can do for a community that meets is the organising
   * nobody volunteers for -- "put the supper club on the second Tuesday of every month for the next
   * six" is twelve minutes of clicking and one sentence to a model. So this is full CRUD, unlike
   * chat, and the safety comes from where it already is: an agent is a session belonging to the
   * admin who authorised it, every write is in the AI log with its arguments, and the mutation
   * event names the row.
   *
   * Suggestions are visible but a model may not decide one. Accepting an event is somebody in the
   * community saying this is what we are doing, and that is not a job to hand over.
   */
  public List<Map<String, Object>> listEvents(boolean includePast) throws SQLException {
    // a draft and a suggestion waiting in the queue are things a member does not see on /events
    boolean everything = mayI(io.hearth.auth.Permission.calendar_review);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    java.time.LocalDate today = java.time.LocalDate.now();
    for (io.hearth.calendar.Calendar.Event event : accounts.calendar.all(MAX_ROWS)) {
      if (!includePast && event.over(today)) {
        continue;
      }
      if (!everything && (!event.published() || event.suggested())) {
        continue;
      }
      out.add(describe(event));
    }
    return out;
  }

  public Map<String, Object> getEvent(long id) throws SQLException {
    io.hearth.calendar.Calendar.Event event = accounts.calendar.byId(id);
    if (event == null) {
      return null;
    }
    if ((!event.published() || event.suggested())
        && !mayI(io.hearth.auth.Permission.calendar_review)) {
      // absent rather than forbidden, which is what a listing already says about it
      return null;
    }
    Map<String, Object> out = describe(event);
    out.put("body", event.body());
    ArrayList<String> guests = new ArrayList<>();
    for (io.hearth.calendar.Calendar.Rsvp rsvp : accounts.calendar.guestList(id)) {
      guests.add(rsvp.userEmail() + " (" + rsvp.answer() + ", party of " + rsvp.party() + ")");
    }
    out.put("guests", guests);
    return out;
  }

  /**
   * Create or change one.
   *
   * A day is required and refused loudly when it is missing or unreadable, rather than defaulted to
   * today -- an event on the wrong day is worse than no event, because people turn up.
   */
  public Map<String, Object> saveEvent(Long id, Map<String, Object> changes)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.calendar_write);
    io.hearth.calendar.Calendar.Event existing = id == null ? null : accounts.calendar.byId(id);
    if (id != null && existing == null) {
      throw new Refused("There is no event with that id.");
    }
    String title = text(changes, "title", existing == null ? null : existing.title());
    if (title == null || title.isBlank()) {
      throw new Refused("An event needs a name.");
    }
    java.time.LocalDate starts = date(changes, "starts_on",
        existing == null ? null : existing.startsOn());
    if (starts == null) {
      throw new Refused("An event needs the day it happens on, as YYYY-MM-DD. People turn up on"
          + " the day it says, so this is not something to guess at.");
    }
    java.time.LocalDate ends = date(changes, "ends_on", existing == null ? starts : existing.endsOn());
    if (ends == null || ends.isBefore(starts)) {
      ends = starts;
    }
    String body = text(changes, "body", existing == null ? "" : existing.body());
    String location = text(changes, "location", existing == null ? "" : existing.location());
    String startTime = text(changes, "start_time", existing == null ? "" : existing.startTime());
    Integer capacity = number(changes, "capacity", existing == null ? null : existing.capacity());
    boolean published = changes.containsKey("published")
        ? Boolean.parseBoolean(String.valueOf(changes.get("published")))
        : existing != null && existing.published();
    Long placeId = existing == null ? null : existing.placeId();
    if (changes.containsKey("place_slug")) {
      // named by slug rather than by id, because a model is reading the address book by name and a
      // numeric id it never saw is the easiest thing in the world to get wrong
      String slug = text(changes, "place_slug", null);
      placeId = null;
      if (slug != null && !slug.isBlank()) {
        for (io.hearth.places.Places.Place place : accounts.places.all(MAX_ROWS)) {
          if (place.slug().equals(slug.trim()) && !place.humanOnly()) {
            placeId = place.id();
          }
        }
        if (placeId == null) {
          throw new Refused("There is no place with the slug '" + slug + "'.");
        }
      }
    }
    io.hearth.calendar.Calendar.Event saved = existing == null
        ? accounts.calendar.create(title, orEmpty(body), orEmpty(location), placeId,
            io.hearth.calendar.Calendar.State.accepted, starts, ends, orEmpty(startTime),
            capacity, published, null, "ai")
        : accounts.calendar.update(existing.id(), title, orEmpty(body), orEmpty(location), placeId,
            starts, ends, orEmpty(startTime), capacity, published, null);
    Map<String, Object> out = describe(saved);
    out.put("created", existing == null);
    return out;
  }

  public Map<String, Object> deleteEvent(long id) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.calendar_write);
    io.hearth.calendar.Calendar.Event event = accounts.calendar.byId(id);
    if (event == null) {
      throw new Refused("There is no event with that id.");
    }
    accounts.calendar.delete(id, null);
    return Map.of("id", id, "title", event.title(), "deleted", true);
  }

  private Map<String, Object> describe(io.hearth.calendar.Calendar.Event event) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", event.id());
    row.put("title", event.title());
    row.put("starts_on", event.startsOn().toString());
    row.put("ends_on", event.endsOn().toString());
    row.put("start_time", event.startTime());
    row.put("location", event.location());
    row.put("state", event.state().name());
    row.put("published", event.published());
    row.put("cancelled", event.cancelled());
    row.put("going", event.goingCount());
    row.put("maybe", event.maybeCount());
    row.put("waiting", event.waitlistCount());
    row.put("capacity", event.capacity());
    return row;
  }

  // ---- the board ------------------------------------------------------------------------------

  /**
   * The discussion board.
   *
   * A model writing here is a model speaking in the community's own voice, which is why every post
   * it makes carries the connector's name in the AI log and belongs to the session of the admin who
   * authorised it. What it is genuinely good for is the job nobody volunteers for: writing up what
   * was decided, posting the thing everybody agreed somebody should post.
   *
   * It cannot moderate. Pinning, locking and removing are the powers a community gave a person, and
   * a model that could take somebody's words down is a different kind of thing entirely.
   */
  public List<Map<String, Object>> listPosts(int limit) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.board_read);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (io.hearth.board.Board.Post post
        : accounts.board.feed(limit <= 0 || limit > MAX_ROWS ? 50 : limit)) {
      out.add(describe(post));
    }
    return out;
  }

  public Map<String, Object> readPost(long id) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.board_read);
    io.hearth.board.Board.Post post = accounts.board.postById(id);
    if (post == null || post.removed()) {
      return null;
    }
    Map<String, Object> out = describe(post);
    out.put("body", post.body());
    ArrayList<Map<String, Object>> comments = new ArrayList<>();
    for (io.hearth.board.Board.Comment comment : accounts.board.thread(id)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", comment.id());
      // a name, for the same reason a member sees a name: an address is not what somebody is
      // called, and a model reading a thread has no use for one it could repeat back
      row.put("who", io.hearth.people.Names.nameOf(accounts, comment.authorId()));
      row.put("who_id", comment.authorId());
      row.put("said", comment.removed() ? null : comment.body());
      row.put("removed", comment.removed());
      comments.add(row);
    }
    out.put("comments", comments);
    return out;
  }

  public Map<String, Object> savePost(Long id, String title, String body)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    if (id != null) {
      io.hearth.board.Board.Post existing = accounts.board.postById(id);
      if (existing == null) {
        throw new Refused("There is no post with that id.");
      }
      // the author check is in the DAO's WHERE clause; a model editing somebody else's words is
      // exactly the thing invariant 60 exists to stop
      // the WHERE clause carries the author check, so a model cannot rewrite somebody else's
      // words -- invariant 60 exists precisely for this shape of caller
      accounts.board.editPost(id, title == null ? existing.title() : title,
          body == null ? existing.body() : body, existing.authorId());
      Map<String, Object> out = describe(accounts.board.postById(id));
      out.put("created", false);
      return out;
    }
    if (title == null || title.isBlank() || body == null || body.isBlank()) {
      throw new Refused("A post needs a title and something to say.");
    }
    long author = aiUserId();
    io.hearth.board.Board.Post made =
        accounts.board.post(author, emailOf(author), title, body, boardExpiryDays);
    Map<String, Object> out = describe(made);
    out.put("created", true);
    return out;
  }

  public Map<String, Object> comment(long postId, String body) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    io.hearth.board.Board.Post post = accounts.board.postById(postId);
    if (post == null || post.removed() || post.locked()) {
      throw new Refused("There is no such conversation, or it is locked.");
    }
    if (body == null || body.isBlank()) {
      throw new Refused("A reply needs something in it.");
    }
    long author = aiUserId();
    io.hearth.board.Board.Comment made =
        accounts.board.comment(postId, null, author, emailOf(author), body);
    return Map.of("id", made.id(), "post", postId, "posted", true);
  }

  // ---- polls -----------------------------------------------------------------------------------

  /**
   * Put a question to the group.
   *
   * <b>A schedule poll needs the permission to create events, and that is the whole reason the
   * check is here rather than at conversion time.</b> It ends by putting something in everybody's
   * calendar; letting anybody start one and discovering the problem at midnight, after people had
   * voted, would waste the group's attention and teach them the feature is unreliable. Somebody who
   * cannot create events can still ask the same question as a plain choice poll -- what they cannot
   * do is have the answer become an event by itself.
   */
  public Map<String, Object> createPoll(long postId, String kindName, String question,
                                        String closesAt, Boolean openOptions,
                                        List<String> choices) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    io.hearth.board.Poll.Kind kind = io.hearth.board.Poll.Kind.of(kindName);
    if (kind == null) {
      throw new Refused("A poll is either 'choice' (a straight either-or) or 'schedule' (which day"
          + " and which place, and it becomes an event).");
    }
    if (kind == io.hearth.board.Poll.Kind.schedule) {
      assertCan(io.hearth.auth.Permission.calendar_write);
    }
    io.hearth.board.Board.Post post = accounts.board.postById(postId);
    if (post == null || post.removed()) {
      throw new Refused("There is no such conversation.");
    }
    if (post.locked()) {
      throw new Refused("That conversation is locked.");
    }
    if (question == null || question.isBlank()) {
      throw new Refused("A poll needs a question -- what is being decided.");
    }
    java.sql.Timestamp closes = parseWhen(closesAt);
    if (kind == io.hearth.board.Poll.Kind.schedule && closes == null) {
      throw new Refused("A schedule poll has to say when it closes: it becomes an event by itself,"
          + " and one that never closes never does. Give closes_at as a date or a date and time.");
    }
    long actor = aiUserId();
    io.hearth.board.Poll.Record poll = accounts.polls.create(postId, kind, question, closes,
        openOptions == null || openOptions, actor);
    if (choices != null) {
      for (String choice : choices) {
        if (choice != null && !choice.isBlank()) {
          accounts.polls.addOption(poll.id(), io.hearth.board.Poll.Facet.choice, choice, null,
              null, null, actor);
        }
      }
    }
    return readPoll(poll.id());
  }

  /** everything about one poll: what is on the table, what the numbers are, and what has won */
  public Map<String, Object> readPoll(long id) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.board_read);
    io.hearth.board.Poll.Record poll = accounts.polls.byId(id);
    if (poll == null) {
      throw new Refused("There is no poll with that id.");
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", poll.id());
    out.put("post", poll.postId());
    out.put("kind", poll.kind().name());
    out.put("question", poll.question());
    out.put("state", poll.state().name());
    out.put("closes_at", poll.closesAt() == null ? null : poll.closesAt().toInstant().toString());
    out.put("anybody_may_add_options", poll.openOptions());
    out.put("who", io.hearth.people.Names.nameOf(accounts, poll.createdBy()));
    if (poll.kind() == io.hearth.board.Poll.Kind.schedule) {
      out.put("when", io.hearth.board.Poll.describe(
          accounts.polls.result(id, io.hearth.board.Poll.Facet.time)));
      out.put("where", io.hearth.board.Poll.describe(
          accounts.polls.result(id, io.hearth.board.Poll.Facet.place)));
      out.put("how_to_vote", "Days take an up, a down, or nothing, each on its own -- somebody free"
          + " on three evenings should say so about all three. Places are either-or: one vote, and"
          + " voting again moves it.");
    } else {
      out.put("choices", io.hearth.board.Poll.describe(
          accounts.polls.result(id, io.hearth.board.Poll.Facet.choice)));
      out.put("how_to_vote", "One vote. Voting again moves it; voting for the same thing twice"
          + " takes it back.");
    }
    if (!poll.outcome().isBlank()) {
      out.put("outcome", poll.outcome());
    }
    if (poll.eventId() != null) {
      out.put("became_event", poll.eventId());
    }
    return out;
  }

  /** every poll in one conversation, for a model that has just read the thread */
  public List<Map<String, Object>> pollsFor(long postId) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.board_read);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (io.hearth.board.Poll.Record poll : accounts.polls.forPost(postId)) {
      out.add(readPoll(poll.id()));
    }
    return out;
  }

  /**
   * Put another thing on the table.
   *
   * A day, a place from the address book, or a plain choice -- whichever half of the poll it
   * belongs to. A place is an id and never free text: the point of voting on a place is that the
   * winner becomes an event's location without anybody retyping an address.
   */
  public Map<String, Object> addPollOption(long pollId, String facetName, String label,
                                           String day, String at, Long placeId)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    io.hearth.board.Poll.Record poll = accounts.polls.byId(pollId);
    if (poll == null) {
      throw new Refused("There is no poll with that id.");
    }
    if (!poll.isOpen()) {
      throw new Refused("That poll has closed.");
    }
    long actor = aiUserId();
    if (!poll.openOptions() && (poll.createdBy() == null || poll.createdBy() != actor)) {
      throw new Refused("Whoever set this poll up asked that only they add to it.");
    }
    io.hearth.board.Poll.Facet facet = io.hearth.board.Poll.Facet.of(facetName);
    if (facet == null) {
      throw new Refused("A facet is 'choice', 'time' or 'place'.");
    }
    if (poll.kind() == io.hearth.board.Poll.Kind.choice
        != (facet == io.hearth.board.Poll.Facet.choice)) {
      throw new Refused("A " + poll.kind() + " poll does not have a '" + facet + "' half.");
    }
    if (accounts.polls.liveOptionCount(pollId, facet)
        >= io.hearth.board.Polls.MAX_OPTIONS) {
      throw new Refused("That is as many as this poll takes. Past "
          + io.hearth.board.Polls.MAX_OPTIONS + " it stops being a decision anybody can make.");
    }
    java.time.LocalDate onDay = null;
    if (facet == io.hearth.board.Poll.Facet.time) {
      onDay = parseDay(day);
      if (onDay == null) {
        throw new Refused("A time option is a day, as YYYY-MM-DD. The time of day goes in `at` and"
            + " is shown as written -- 'doors at 7' is a real answer no clock field holds.");
      }
      for (io.hearth.board.Poll.Option existing : accounts.polls.options(pollId)) {
        if (!existing.removed() && onDay.equals(existing.onDay())) {
          throw new Refused("That day is already on the table.");
        }
      }
    }
    if (facet == io.hearth.board.Poll.Facet.place) {
      if (placeId == null || accounts.places.byId(placeId) == null) {
        throw new Refused("A place option is a place from the address book, by id. Use place_list"
            + " to find one, or place_save to write it down first.");
      }
      for (io.hearth.board.Poll.Option existing : accounts.polls.options(pollId)) {
        if (!existing.removed() && placeId.equals(existing.placeId())) {
          throw new Refused("That place is already on the table.");
        }
      }
    }
    if (facet == io.hearth.board.Poll.Facet.choice && (label == null || label.isBlank())) {
      throw new Refused("A choice needs something to call it.");
    }
    io.hearth.board.Poll.Option option = accounts.polls.addOption(pollId, facet,
        facet == io.hearth.board.Poll.Facet.place && (label == null || label.isBlank())
            ? accounts.places.byId(placeId).name() : label,
        onDay, at, placeId, actor);
    return Map.of("id", option.id(), "what", option.describe(), "added", true);
  }

  /** take one off the table; the votes stay so nothing else silently changes share */
  public Map<String, Object> removePollOption(long optionId) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    io.hearth.board.Poll.Option option = accounts.polls.optionById(optionId);
    if (option == null || option.removed()) {
      throw new Refused("There is no such option, or it has already gone.");
    }
    io.hearth.board.Poll.Record poll = accounts.polls.byId(option.pollId());
    long actor = aiUserId();
    boolean mine = option.addedBy() != null && option.addedBy() == actor;
    boolean theirs = poll != null && poll.createdBy() != null && poll.createdBy() == actor;
    if (!mine && !theirs) {
      // taking away something somebody else put forward, and that other people may have voted for,
      // is moderating rather than participating
      assertCan(io.hearth.auth.Permission.board_moderate);
    }
    accounts.polls.removeOption(optionId, actor);
    return Map.of("id", optionId, "removed", true);
  }

  /**
   * Vote.
   *
   * @param weight 1 for yes, -1 for "not that day". A down vote only means anything on a day; on an
   *     either-or it is read as taking your vote back, because there is no such thing as being
   *     against one option and not for another.
   */
  public Map<String, Object> votePoll(long optionId, int weight) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_vote);
    io.hearth.board.Poll.Option option = accounts.polls.optionById(optionId);
    if (option == null || option.removed()) {
      throw new Refused("There is no such option.");
    }
    io.hearth.board.Poll.Record poll = accounts.polls.byId(option.pollId());
    if (poll == null || !poll.isOpen()) {
      throw new Refused("That poll is not taking votes.");
    }
    int now = accounts.polls.vote(poll.id(), optionId, aiUserId(), weight);
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("option", optionId);
    out.put("your_vote", now == 0 ? "nothing" : now > 0 ? "for" : "against");
    out.put("poll", readPoll(poll.id()));
    return out;
  }

  /**
   * Count it now rather than waiting for its moment.
   *
   * For whoever asked the question, or a moderator. Anybody else closing a vote early is deciding
   * who gets to have voted.
   */
  public Map<String, Object> closePoll(long pollId) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.board_write);
    io.hearth.board.Poll.Record poll = accounts.polls.byId(pollId);
    if (poll == null) {
      throw new Refused("There is no poll with that id.");
    }
    if (!poll.isOpen()) {
      throw new Refused("That poll has already been counted.");
    }
    long actor = aiUserId();
    if (poll.createdBy() == null || poll.createdBy() != actor) {
      assertCan(io.hearth.auth.Permission.board_moderate);
    }
    if (poll.kind() == io.hearth.board.Poll.Kind.schedule) {
      assertCan(io.hearth.auth.Permission.calendar_write);
    }
    io.hearth.board.Poll.Record settled = pollClock.settle(domainConfig, accounts, poll);
    return readPoll(settled.id());
  }

  private static java.time.LocalDate parseDay(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** a date, or a date and a time; anything else is refused rather than guessed at */
  private java.sql.Timestamp parseWhen(String raw) throws Refused {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String clean = raw.trim();
    try {
      if (clean.length() <= 10) {
        // end of that day in the community's own clock, because "closes on Friday" means Friday
        // evening to everybody who reads it and midnight-that-morning to nobody
        return java.sql.Timestamp.from(java.time.LocalDate.parse(clean)
            .plusDays(1).atStartOfDay(zone).toInstant());
      }
      return java.sql.Timestamp.from(
          java.time.LocalDateTime.parse(clean.replace(' ', 'T')).atZone(zone).toInstant());
    } catch (RuntimeException ex) {
      throw new Refused("closes_at should be YYYY-MM-DD or YYYY-MM-DDTHH:MM, in this community's"
          + " own timezone (" + zone.getId() + ").");
    }
  }

  // ---- tasks, routines and what was recorded ---------------------------------------------------

  /**
   * <b>Everything here is about the person this connection belongs to, and only them.</b> There is
   * no argument for a user id anywhere in this half, deliberately: a training log is the most
   * private thing this server holds, and a tool that took a "whose" parameter would be one prompt
   * away from reading somebody else's. An administrator inspecting a member's log does it on a
   * screen, on purpose, and it is written in the AI log when a model does anything at all.
   */
  public List<Map<String, Object>> listProjects() throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.tasks_use);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (io.hearth.tasks.Records.Project project
        : accounts.tasks.projectsFor(aiUserId(), false)) {
      out.add(describe(project));
    }
    return out;
  }

  public Map<String, Object> readProject(long id) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Project project = mineOrShared(id);
    Map<String, Object> out = describe(project);
    ArrayList<Map<String, Object>> tasks = new ArrayList<>();
    for (io.hearth.tasks.Records.Task task : accounts.tasks.tasksIn(id)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", task.id());
      row.put("title", task.title());
      row.put("phase", task.phase());
      if (task.grouped()) {
        row.put("group", task.groupName());
        row.put("group_mode", task.grouping().name());
      }
      row.put("done", task.done());
      row.put("repeats_every_days", task.repeatDays());
      row.put("due", task.dueOn() == null ? null : task.dueOn().toString());
      if (task.defId() != null) {
        io.hearth.tasks.Records.Def def = accounts.tasks.def(task.defId());
        if (def != null) {
          row.put("definition", def.id());
          row.put("measured_in", def.measure().name());
        }
      }
      tasks.add(row);
    }
    out.put("tasks", tasks);
    return out;
  }

  public Map<String, Object> saveProject(Long id, String name, String summary, String taskWord,
                                         String tasksWord, List<String> phases,
                                         Integer hideDoneHours) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    long me = aiUserId();
    io.hearth.tasks.Records.Project existing = id == null ? null : mineOrShared(id);
    if (existing != null && existing.ownerId() == null) {
      // the community's project, not this person's
      assertCan(io.hearth.auth.Permission.tasks_share);
    }
    if (name == null || name.isBlank()) {
      throw new Refused("A project needs a name.");
    }
    io.hearth.tasks.Records.Project saved = accounts.tasks.saveProject(id,
        existing == null ? me : existing.ownerId(), name, summary,
        taskWord == null ? "task" : taskWord, tasksWord == null ? "tasks" : tasksWord,
        phases == null ? List.of() : phases,
        hideDoneHours == null ? (existing == null ? 24 : existing.hideDoneHours()) : hideDoneHours,
        me);
    Map<String, Object> out = describe(saved);
    out.put("created", id == null);
    return out;
  }

  /** the library: what things are, as opposed to any occasion of doing them */
  public List<Map<String, Object>> listDefinitions() throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.tasks_use);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (io.hearth.tasks.Records.Def def : accounts.tasks.defsFor(aiUserId(), false)) {
      out.add(describe(def));
    }
    return out;
  }

  public Map<String, Object> readDefinition(long id) throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Def raw = accounts.tasks.def(id);
    long me = aiUserId();
    if (raw == null || (raw.ownerId() != null && raw.ownerId() != me)
        || (raw.ownerId() == null && !raw.shared())) {
      throw new Refused("There is no such definition, or it is not yours.");
    }
    io.hearth.tasks.Records.Def def = accounts.tasks.resolved(raw);
    Map<String, Object> out = describe(def);
    out.put("instructions", def.instructions());
    out.put("reference_url", def.referenceUrl());
    out.putAll(historyOf(def, me));
    return out;
  }

  /**
   * Write down what a thing is.
   *
   * <b>The instructions field is what this whole tool exists for.</b> A definition with a name and
   * a measurement is a row in a spreadsheet; one that also says how the movement is performed, what
   * to watch for and where the form came from is a thing somebody can use at the moment they have
   * forgotten. Write it properly the first time -- it is read on a phone, mid-set, by somebody who
   * cannot remember whether the elbows go forward.
   */
  public Map<String, Object> saveDefinition(Long id, String name, String measureName,
                                            String summary, String instructions,
                                            String referenceUrl, String tags,
                                            Integer sets, Integer reps, Double weight,
                                            Integer restSeconds, Boolean share)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    long me = aiUserId();
    io.hearth.tasks.Records.Def existing = id == null ? null : accounts.tasks.def(id);
    if (id != null && existing == null) {
      throw new Refused("There is no definition with that id.");
    }
    if (existing != null && existing.ownerId() != null && existing.ownerId() != me) {
      throw new Refused("That definition belongs to somebody else.");
    }
    boolean toCommunity = Boolean.TRUE.equals(share)
        || (existing != null && existing.ownerId() == null);
    if (toCommunity) {
      // the community's library is a shared thing, and one anybody may add to is one nobody can
      // find anything in
      assertCan(io.hearth.auth.Permission.tasks_share);
    }
    io.hearth.tasks.Measure measure = existing != null && measureName == null
        ? existing.measure() : io.hearth.tasks.Measure.of(measureName);
    if (measure == null) {
      throw new Refused("measured_in should be one of: "
          + java.util.Arrays.toString(io.hearth.tasks.Measure.values())
          + ". Use 'none' for something that is simply done or not.");
    }
    if ((name == null || name.isBlank()) && existing == null) {
      throw new Refused("A definition needs a name.");
    }
    com.fasterxml.jackson.databind.node.ObjectNode target = JSON_NODES.createObjectNode();
    if (sets != null) {
      target.put("sets", sets);
    }
    if (reps != null) {
      target.put("reps", reps);
    }
    if (weight != null) {
      target.put("weight", weight);
    }
    io.hearth.tasks.Records.Def saved = accounts.tasks.saveDef(id,
        toCommunity ? null : me, existing == null ? null : existing.parentId(),
        name == null ? existing.name() : name, measure,
        summary == null && existing != null ? existing.summary() : summary,
        instructions == null && existing != null ? existing.instructions() : instructions,
        referenceUrl == null && existing != null ? existing.referenceUrl() : referenceUrl,
        tags == null && existing != null ? existing.tags() : tags,
        target.isEmpty() && existing != null ? existing.target() : target.toString(),
        restSeconds == null ? (existing == null ? 0 : existing.restSeconds()) : restSeconds,
        toCommunity, me);
    Map<String, Object> out = describe(saved);
    out.put("created", id == null);
    return out;
  }

  /** put something on a project, from the library or as a one-off */
  public Map<String, Object> addTask(long projectId, Long defId, String title, String notes,
                                     Integer repeatDays, String dueOn)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Project project = mineOrShared(projectId);
    io.hearth.tasks.Records.Def def = defId == null ? null : accounts.tasks.def(defId);
    if (defId != null && def == null) {
      throw new Refused("There is no definition with that id.");
    }
    String name = title == null || title.isBlank() ? (def == null ? null : def.name()) : title;
    if (name == null) {
      throw new Refused("Give it a title, or a definition to take its name from.");
    }
    io.hearth.tasks.Records.Task task = accounts.tasks.addTask(projectId,
        def == null ? null : def.id(), name, notes, project.firstPhase(),
        repeatDays == null ? 0 : repeatDays, parseDay(dueOn), null, aiUserId());
    return Map.of("id", task.id(), "title", task.title(), "project", projectId, "added", true);
  }

  /**
   * Put things in a group, or take one out.
   *
   * The refusal for an unknown mode names both, because a model that guessed "circuit" should be
   * told the two words rather than left to try a third.
   */
  public Map<String, Object> groupTask(long taskId, String name, String mode)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Task task = accounts.tasks.task(taskId);
    if (task == null) {
      throw new Refused("There is no such task.");
    }
    mineOrShared(task.projectId());
    boolean leaving = name == null || name.isBlank();
    io.hearth.tasks.Records.Grouping grouping = leaving ? null
        : io.hearth.tasks.Records.Grouping.of(mode);
    if (!leaving && grouping == null) {
      throw new Refused("mode is 'related' (a superset -- alternate, and rest after the round) or"
          + " 'sequenced' (a circuit or a progression -- the order is the point).");
    }
    accounts.tasks.group(taskId, name, grouping, aiUserId());
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", taskId);
    out.put("group", leaving ? null : name);
    out.put("mode", leaving ? null : grouping.name());
    out.put("said", leaving ? "took it out of its group"
        : "put it in '" + name + "' as a " + grouping.label());
    if (!leaving) {
      ArrayList<Map<String, Object>> with = new ArrayList<>();
      for (io.hearth.tasks.Records.Task other
          : accounts.tasks.groupWith(accounts.tasks.task(taskId))) {
        with.add(Map.of("id", other.id(), "title", other.title()));
      }
      out.put("done_with", with);
      out.put("how", grouping.hint());
    }
    return out;
  }

  public Map<String, Object> removeTask(long taskId) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Task task = accounts.tasks.task(taskId);
    if (task == null) {
      throw new Refused("There is no such task.");
    }
    mineOrShared(task.projectId());
    accounts.tasks.deleteTask(taskId, aiUserId());
    return Map.of("id", taskId, "removed", true,
        "note", "What was recorded against it is kept.");
  }

  /**
   * Record one set, or one occasion of doing something.
   *
   * <b>Record what actually happened, and ask before rating it.</b> The three scores are somebody's
   * own judgement of an evening's work -- guessing them fills a history with opinions nobody held,
   * and the whole reason they exist is to find the exercise that is exhausting and useless. If the
   * person has not said, leave them out; a missing rating is a different fact from a middling one.
   */
  public Map<String, Object> recordEntry(long taskId, Double weight, Integer reps, Integer seconds,
                                         Double distance, Integer difficulty, Integer timeCost,
                                         Integer impact, String note) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Task task = accounts.tasks.task(taskId);
    if (task == null) {
      throw new Refused("There is no such task.");
    }
    io.hearth.tasks.Records.Project project = mineOrShared(task.projectId());
    long me = aiUserId();
    io.hearth.tasks.Records.Def def = task.defId() == null ? null
        : accounts.tasks.resolved(accounts.tasks.def(task.defId()));
    io.hearth.tasks.Measure measure = def == null
        ? io.hearth.tasks.Measure.none : def.measure();
    int setIndex = accounts.tasks.entriesForTask(taskId, me, 200).size();
    io.hearth.tasks.Records.Entry entry = accounts.tasks.record(taskId, task.defId(),
        project.id(), me, setIndex, weight, reps, seconds, distance, difficulty, timeCost,
        impact, note);
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("id", entry.id());
    out.put("recorded", entry.describe(measure));
    out.put("at", entry.recordedAt().toInstant().toString());
    out.put("set", setIndex + 1);
    return out;
  }

  /** tick something off, which is a data point in its own right */
  public Map<String, Object> completeTask(long taskId, Integer difficulty, Integer timeCost,
                                          Integer impact, String note)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.tasks_use);
    io.hearth.tasks.Records.Task task = accounts.tasks.task(taskId);
    if (task == null) {
      throw new Refused("There is no such task.");
    }
    io.hearth.tasks.Records.Project project = mineOrShared(task.projectId());
    long me = aiUserId();
    accounts.tasks.complete(taskId, true, java.time.LocalDate.now(zone), me);
    accounts.tasks.record(taskId, task.defId(), project.id(), me, 0, null, null, null, null,
        difficulty, timeCost, impact, note);
    io.hearth.tasks.Records.Task after = accounts.tasks.task(taskId);
    return Map.of("id", taskId, "done", true,
        "comes_back_on", after.dueOn() == null ? "" : after.dueOn().toString());
  }

  /**
   * How a routine is actually going, which is what a model should read before changing it.
   *
   * Every definition this person has, with what it has cost and what it gave. Tuning towards high
   * impact for little time is a comparison across the whole routine rather than a fact about one
   * exercise, so it comes back as one answer.
   */
  public Map<String, Object> reviewRoutine() throws SQLException, Refused {
    assertCan(io.hearth.auth.Permission.tasks_use);
    long me = aiUserId();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.tasks.Records.Def raw : accounts.tasks.defsFor(me, false)) {
      io.hearth.tasks.Records.Def def = accounts.tasks.resolved(raw);
      io.hearth.tasks.Records.History history = accounts.tasks.historyOf(def, me);
      if (!history.any()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", def.id());
      row.put("name", def.name());
      row.put("measured_in", def.measure().name());
      row.put("occasions", history.occasions());
      row.put("last_at", history.lastAt() == null ? null
          : history.lastAt().toInstant().toString());
      row.put("difficulty", history.averageDifficulty());
      row.put("time_cost", history.averageTime());
      row.put("impact", history.averageImpact());
      row.put("impact_per_time", history.worth());
      row.put("estimated_one_rep_max", history.estimatedMax());
      row.put("verdict", history.verdict());
      rows.add(row);
    }
    rows.sort((a, b) -> Double.compare(
        b.get("impact_per_time") == null ? -1 : (Double) b.get("impact_per_time"),
        a.get("impact_per_time") == null ? -1 : (Double) a.get("impact_per_time")));
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("definitions", rows);
    out.put("count", rows.size());
    out.put("how_to_read_this", "impact_per_time is impact divided by time cost, both one to five,"
        + " and it is the number to tune towards -- highest first. A definition with no ratings"
        + " has none, which means nobody has said, not that it is average. Suggest changes; do not"
        + " make them without asking.");
    return out;
  }

  private Map<String, Object> historyOf(io.hearth.tasks.Records.Def def, long userId)
      throws SQLException {
    io.hearth.tasks.Records.History history = accounts.tasks.historyOf(def, userId);
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("occasions", history.occasions());
    out.put("sets", history.sets());
    out.put("last_at", history.lastAt() == null ? null : history.lastAt().toInstant().toString());
    out.put("difficulty", history.averageDifficulty());
    out.put("time_cost", history.averageTime());
    out.put("impact", history.averageImpact());
    if (def.measure().hasOneRepMax()) {
      out.put("estimated_one_rep_max", history.estimatedMax());
      out.put("one_rep_max_means", def.measure().oneRepMaxLabel()
          + ", from the best single set. It is Epley's formula and it is only offered up to "
          + io.hearth.tasks.Measure.HONEST_REPS + " reps -- past that the number is about how long"
          + " somebody can suffer rather than what they can lift. Treat it as a direction, not a"
          + " target.");
    }
    out.put("verdict", history.verdict());
    ArrayList<Map<String, Object>> recent = new ArrayList<>();
    for (io.hearth.tasks.Records.Entry entry : history.recent()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("what", entry.describe(def.measure()));
      row.put("at", entry.recordedAt().toInstant().toString());
      row.put("difficulty", entry.difficulty());
      row.put("impact", entry.impact());
      row.put("note", entry.note().isBlank() ? null : entry.note());
      recent.add(row);
    }
    out.put("recent", recent);
    return out;
  }

  /** the project, if it is this person's or the community's; a refusal otherwise */
  private io.hearth.tasks.Records.Project mineOrShared(long id) throws SQLException, Refused {
    io.hearth.tasks.Records.Project project = accounts.tasks.project(id);
    if (project == null || (project.ownerId() != null && project.ownerId() != actorId)) {
      // no distinction between "not there" and "not yours": whether somebody else's project exists
      // is itself their business
      throw new Refused("There is no project with that id.");
    }
    return project;
  }

  private Map<String, Object> describe(io.hearth.tasks.Records.Project project) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", project.id());
    row.put("name", project.name());
    row.put("summary", project.summary());
    row.put("belongs_to", project.isShared() ? "the community" : "you");
    row.put("calls_one", project.one());
    row.put("calls_many", project.many());
    row.put("phases", project.phases());
    row.put("hides_done_after_hours", project.hideDoneHours());
    return row;
  }

  private Map<String, Object> describe(io.hearth.tasks.Records.Def def) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", def.id());
    row.put("name", def.name());
    row.put("summary", def.summary());
    row.put("measured_in", def.measure().name());
    row.put("records", def.measure().fields().stream().map(Enum::name).toList());
    row.put("belongs_to", def.isCommunitys() ? "the community" : "you");
    row.put("from_shared", def.parentId());
    row.put("tags", def.tagList());
    row.put("rest_seconds", def.restSeconds());
    return row;
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_NODES =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private Map<String, Object> describe(io.hearth.board.Board.Post post) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", post.id());
    row.put("title", post.title());
    // A name and an id, and deliberately not an address. Invariant 119 is that a community can ask
    // who put something here and get somebody they can talk to -- the id is what makes that
    // answerable without handing a model provider a list of member email addresses.
    row.put("who", io.hearth.people.Names.nameOf(accounts, post.authorId()));
    row.put("who_id", post.authorId());
    row.put("comments", post.commentCount());
    row.put("pinned", post.pinned());
    row.put("locked", post.locked());
    row.put("removed", post.removed());
    return row;
  }

  /**
   * Which account a model's words belong to.
   *
   * The admin who authorised the connector, because that is whose session it is holding. There is
   * no "the AI" account and there should not be: a community has to be able to ask who put this
   * here and get the name of somebody they can talk to.
   */
  private String emailOf(long userId) throws SQLException {
    if (actorEmail != null && !actorEmail.isBlank() && actorId != null && actorId == userId) {
      return actorEmail;
    }
    io.hearth.auth.UserRecord user = accounts.users.byId(userId);
    return user == null ? "" : user.email();
  }

  private long aiUserId() throws SQLException, Refused {
    // whoever authorised this connection, which is whose session the agent is holding. Searching
    // for "an administrator" would attribute somebody's words to a person who was not involved --
    // and would pick a different one after a role change.
    if (actorId != null && actorId > 0) {
      return actorId;
    }
    throw new Refused("This connection is not acting for anybody, so there is nobody for a post"
        + " to belong to.");
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String text(Map<String, Object> changes, String key, String fallback) {
    Object value = changes.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static Integer number(Map<String, Object> changes, String key, Integer fallback) {
    Object value = changes.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number found) {
      return found.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static java.time.LocalDate date(Map<String, Object> changes, String key,
                                          java.time.LocalDate fallback) {
    Object value = changes.get(key);
    if (value == null) {
      return fallback;
    }
    try {
      return java.time.LocalDate.parse(String.valueOf(value).trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  public List<Map<String, Object>> listPlaceTypes() throws SQLException {
    boolean drafts = mayI(io.hearth.auth.Permission.places_write);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (Places.Type type : accounts.places.allTypes()) {
      if (!drafts && !type.published()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("type", type.slug());
      row.put("label", type.labelOr());
      row.put("plural", type.pluralOr());
      row.put("description", type.description());
      row.put("published", type.published());
      row.put("template", type.templateName());
      ArrayList<Map<String, Object>> fields = new ArrayList<>();
      for (TemplateField field : type.fields()) {
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", field.name());
        spec.put("label", field.labelOr());
        spec.put("type", field.type().name());
        spec.put("required", field.required());
        fields.add(spec);
      }
      row.put("fields", fields);
      row.put("count", accounts.places.countIn(type.slug()));
      out.add(row);
    }
    return out;
  }

  /**
   * Places, with the human-only ones absent rather than refused.
   *
   * The same asymmetry content has, for the same reason: a vendor a community marked human-only is
   * one somebody decided a model should not know about, and a list that said "3 hidden" would have
   * told it anyway.
   */
  public List<Map<String, Object>> listPlaces(String typeSlug, String query) throws SQLException {
    // a draft place is one nobody has put in the book yet
    boolean drafts = mayI(io.hearth.auth.Permission.places_write);
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    List<Places.Place> found = query == null || query.isBlank()
        ? (typeSlug == null || typeSlug.isBlank()
            ? accounts.places.all(MAX_ROWS) : accounts.places.inType(typeSlug, false, MAX_ROWS))
        : accounts.places.search(query, false, MAX_ROWS);
    for (Places.Place place : found) {
      if (place.humanOnly() || (!drafts && !place.published())) {
        continue;
      }
      if (typeSlug != null && !typeSlug.isBlank() && !place.typeSlug().equals(typeSlug)) {
        continue;
      }
      out.add(describe(place));
    }
    return out;
  }

  public Map<String, Object> getPlace(String typeSlug, String slug) throws SQLException {
    Places.Place place = accounts.places.bySlug(typeSlug, slug);
    if (place == null || place.humanOnly()) {
      return null;
    }
    if (!place.published() && !mayI(io.hearth.auth.Permission.places_write)) {
      return null;
    }
    Map<String, Object> out = describe(place);
    out.put("body", place.body());
    return out;
  }

  /**
   * Create or update one.
   *
   * The type has to exist, and the extra fields are filtered to what it declared -- a model that
   * invents `organic: true` for a type with no such field is told, rather than having it quietly
   * dropped and believing it saved.
   */
  public Map<String, Object> savePlace(String typeSlug, String slug, Map<String, Object> changes)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.places_write);
    Places.Type type = accounts.places.typeBySlug(typeSlug);
    if (type == null) {
      throw new Refused("There is no kind of place called '" + typeSlug + "'. Use"
          + " list_place_types to see what this community keeps.");
    }
    String clean = Places.slugify(slug);
    if (clean == null) {
      throw new Refused("A place needs a short name that can live in a URL.");
    }
    Places.Place existing = accounts.places.bySlug(type.slug(), clean);
    if (existing != null && existing.humanOnly()) {
      // said out loud rather than silently: a write that claimed success while doing nothing would
      // teach the model it had succeeded
      throw new Refused("'" + existing.name() + "' is marked human only. A person has to edit it.");
    }

    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    if (existing != null) {
      values.putAll(existing.values());
    }
    Object extras = changes.get("fields");
    if (extras instanceof Map<?, ?> given) {
      java.util.Set<String> declared = new java.util.HashSet<>();
      for (TemplateField field : type.fields()) {
        declared.add(field.name());
      }
      for (Map.Entry<?, ?> entry : given.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (!declared.contains(key)) {
          throw new Refused("'" + key + "' is not something a " + type.labelOr() + " records."
              + " The fields are: " + String.join(", ", declared));
        }
        values.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
      }
    }

    Places.Place place = new Places.Place(existing == null ? 0 : existing.id(), type.slug(), clean,
        str(changes, "name", existing == null ? clean : existing.name()),
        str(changes, "address", existing == null ? "" : existing.address()),
        str(changes, "locality", existing == null ? "" : existing.locality()),
        str(changes, "region", existing == null ? "" : existing.region()),
        str(changes, "postcode", existing == null ? "" : existing.postcode()),
        str(changes, "country", existing == null ? "" : existing.country()),
        existing == null ? null : existing.latitude(), existing == null ? null : existing.longitude(),
        str(changes, "url", existing == null ? "" : existing.url()),
        str(changes, "phone", existing == null ? "" : existing.phone()),
        str(changes, "email", existing == null ? "" : existing.email()),
        Places.valuesToBlob(type.fields(), values),
        str(changes, "body", existing == null ? "" : existing.body()),
        bool(changes, "published", existing != null && existing.published()),
        // a model can never set or clear the bit, exactly as with content
        existing != null && existing.humanOnly(),
        null, null, actorId);
    Places.Place saved = accounts.places.save(place, actorId);
    Map<String, Object> out = describe(saved);
    out.put("created", existing == null);
    return out;
  }

  public Map<String, Object> deletePlace(String typeSlug, String slug)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.places_write);
    Places.Place place = accounts.places.bySlug(typeSlug, slug);
    if (place == null) {
      throw new Refused("There is no '" + slug + "' filed under " + typeSlug + ".");
    }
    if (place.humanOnly()) {
      throw new Refused("'" + place.name() + "' is marked human only. A person has to remove it.");
    }
    accounts.places.delete(place.id(), actorId);
    return Map.of("deleted", true, "type", place.typeSlug(), "slug", place.slug(),
        "name", place.name());
  }

  private Map<String, Object> describe(Places.Place place) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("type", place.typeSlug());
    row.put("slug", place.slug());
    row.put("name", place.name());
    row.put("address", place.oneLine());
    row.put("url", place.url());
    row.put("phone", place.phone());
    row.put("email", place.email());
    row.put("published", place.published());
    row.put("fields", place.values());
    return row;
  }

  private static String str(Map<String, Object> changes, String key, String fallback) {
    Object value = changes.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static boolean bool(Map<String, Object> changes, String key, boolean fallback) {
    Object value = changes.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }

  public AiSurface actingAs(long userId) {
    this.actorId = userId;
    return this;
  }

  /** and under whose name, so the version history says who an agent was acting as */
  public AiSurface actingAs(long userId, String email) {
    this.actorId = userId;
    this.actorEmail = email;
    return this;
  }

  private static boolean contains(String needle, String... haystacks) {
    for (String haystack : haystacks) {
      if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String excerpt(String body, String needle) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    int at = needle.isEmpty() ? 0 : body.toLowerCase(Locale.ROOT).indexOf(needle);
    if (at < 0) {
      at = 0;
    }
    int from = Math.max(0, at - 80);
    int to = Math.min(body.length(), from + SNIPPET);
    return (from > 0 ? "…" : "") + body.substring(from, to) + (to < body.length() ? "…" : "");
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean bool(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private static int intOr(Map<String, Object> map, String key, int fallback) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static List<String> strings(Map<String, Object> map, String key) {
    Object value = map.get(key);
    ArrayList<String> list = new ArrayList<>();
    if (value instanceof List<?> items) {
      for (Object item : items) {
        if (item != null && !String.valueOf(item).isBlank()) {
          list.add(String.valueOf(item).trim());
        }
      }
    } else if (value != null) {
      for (String line : String.valueOf(value).split("\\R")) {
        if (!line.isBlank()) {
          list.add(line.trim());
        }
      }
    }
    return list;
  }
}
