package io.hearth.calendar;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Who is holding the pen when an event body is written, and which renderer that picks.
 *
 * Invariant 91 says the renderer is chosen by the author and never by the destination:
 * {@code Markdown.toHtml} passes raw HTML through, which is right for somebody who could replace
 * the whole document anyway, and {@code toSafeHtml} is for anything a member typed.
 *
 * An event body was rendered with the operator's renderer — and an event body is not always an
 * operator's. `calendar.suggestions` is on by default and lets any approved member put an event
 * forward, body and all; a reviewer accepting one changes a word on the row rather than rewriting
 * the text, so the member's markup is what gets published. The same body also arrives from
 * {@code IcsRequests}, where it came out of an email.
 *
 * So this is the stored-injection shape the project has already paid for once, on a different
 * surface: written by somebody not yet trusted, read by an administrator who is obliged to open it
 * and then by every member. The Content Security Policy stops a script from running, which is
 * defence in depth and not a reason to stop here — a policy is one header away from being wrong,
 * and defacing the page a reviewer reads does not need script execution.
 */
public class EventInjectionTests {
  private static final String PAYLOAD =
      "<script>window.stolen=1</script><img src=x onerror=\"window.stolen=1\">"
          + "<iframe src=\"https://evil.example/\"></iframe>"
          + "<a href=\"javascript:window.stolen=1\">click</a>";

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    member = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    admin.get("/admin/people");
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private long suggestAnEvent(String body) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "suggest");
    form.put("title", "Book club");
    form.put("starts_on", java.time.LocalDate.now().plusDays(7).toString());
    form.put("body", body);
    member.get("/events");
    member.submitToAndFollow("/events", form);
    for (Calendar.Event event : accounts().calendar.suggestions(50)) {
      if (event.title().equals("Book club")) {
        return event.id();
      }
    }
    throw new IllegalStateException("the suggestion was not written");
  }

  /** the whole point: a member's markup must not reach another member's browser as markup */
  @Test
  public void aMembersSuggestedEventBodyIsNotRenderedAsRawHtml() throws Exception {
    long id = suggestAnEvent(PAYLOAD);
    admin.get("/admin/calendar/suggestions");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "accept", "id", Long.toString(id)));

    String page = member.get("/events/" + id).body();
    assertFalse("a script tag from a member must not survive to the page",
        page.contains("<script>window.stolen"));
    assertFalse("nor an inline event handler", page.contains("onerror"));
    assertFalse("nor a frame pointing somewhere else",
        page.contains("<iframe src=\"https://evil.example/\""));
    assertFalse("nor a javascript: url", page.contains("javascript:window.stolen"));
  }

  /**
   * And the administrator reading it to decide is the person most exposed.
   *
   * This one was already right and is kept under test beside the one that was not: the review
   * screen prints the body with {@code {{body}}}, which escapes, so a reviewer sees the markup as
   * text. The assertion is on the *unescaped* forms, because the escaped ones legitimately appear
   * on that page -- that is what showing somebody what was written looks like.
   */
  @Test
  public void theReviewScreenIsNotInjectedEither() throws Exception {
    suggestAnEvent(PAYLOAD);
    String page = admin.get("/admin/calendar/suggestions").body();
    assertFalse(page, page.contains("<script>window.stolen"));
    assertFalse(page, page.contains("<img src=x onerror"));
    assertTrue("the reviewer still gets to read what was written",
        page.contains("&lt;script&gt;") || page.contains("&lt;img"));
  }

  /** ordinary formatting still works, because the fix must not make the feature useless */
  @Test
  public void ordinaryMarkdownStillRenders() throws Exception {
    long id = suggestAnEvent("We meet at **the Oak**.\n\n- bring a book\n- bring a friend\n\n"
        + "[the pub](https://theoak.example/)");
    admin.get("/admin/calendar/suggestions");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "accept", "id", Long.toString(id)));

    String page = member.get("/events/" + id).body();
    assertTrue(page, page.contains("<strong>the Oak</strong>"));
    assertTrue(page, page.contains("<li>bring a book</li>"));
    assertTrue("a plain link survives", page.contains("https://theoak.example/"));
  }

  /** a link somebody else wrote carries what every member-written link carries */
  @Test
  public void aMemberWrittenLinkIsMarkedUp() throws Exception {
    long id = suggestAnEvent("[somewhere](https://elsewhere.example/)");
    admin.get("/admin/calendar/suggestions");
    admin.submitToAndFollow("/admin/calendar",
        Map.of("action", "accept", "id", Long.toString(id)));

    String page = member.get("/events/" + id).body();
    assertTrue(page, page.contains("nofollow"));
    assertTrue(page, page.contains("noopener"));
  }

  /** the same body, on the address book, which a mailed-in event can also create */
  @Test
  public void aPlaceBodyIsNotRenderedAsRawHtmlEither() throws Exception {
    admin.get("/admin/places/new");
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("type_slug", "unsorted");
    form.put("name", "The Oak");
    form.put("summary", "");
    form.put("body", PAYLOAD);
    form.put("published", "on");
    admin.submitToAndFollow("/admin/places", form);

    String slug = null;
    for (io.hearth.places.Places.Place place : accounts().places.all(50)) {
      if (place.name().equals("The Oak")) {
        slug = place.slug();
      }
    }
    org.junit.Assert.assertNotNull("the place was not written", slug);
    String page = member.get("/places/unsorted/" + slug).body();
    assertFalse(page, page.contains("<script>window.stolen"));
    assertFalse(page, page.contains("onerror"));
  }

  /** and nothing about this changes what a page an operator wrote may contain */
  @Test
  public void anOperatorsPageStillPassesRawHtmlThrough() throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("uri", "/about");
    form.put("title", "About");
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("published", "on");
    form.put("body", "<div class=\"hero\">We meet on Tuesdays.</div>");
    admin.get("/admin/content/new");
    admin.submitToAndFollow("/admin/content", form);

    String page = member.get("/about").body();
    assertTrue("a page is a document its author could replace outright",
        page.contains("<div class=\"hero\">"));
    assertEquals(200, member.get("/about").status());
  }
}
