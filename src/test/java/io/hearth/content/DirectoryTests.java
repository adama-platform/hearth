package io.hearth.content;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A template that publishes a listing, which is what lets the content table behave like a blog.
 *
 * Nothing here is a blog feature. Writing a post is writing a page; the index is a property of the
 * template's shape, so it cannot fall out of step with what is actually published.
 */
public class DirectoryTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
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

  private ContentStore store() {
    return server.auth.forDomain("example.org").site.store();
  }

  /** a template with an index, and its listing markup */
  private void blog(String pattern, int pageSize) throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "post",
        "directory", "on", "directory_path", "/blog", "directory_pattern", pattern,
        "directory_page_size", Integer.toString(pageSize), "directory_order", "newest",
        "body", "<html><body>"
            + "{{#directory}}<h1>The blog</h1>"
            + "{{#entries}}<article><a href=\"{{uri}}\">{{title}}</a><p>{{excerpt}}</p></article>{{/entries}}"
            + "<nav>page {{page}} of {{pages}}"
            + "{{#hasPrev}}<a rel=\"prev\" href=\"{{prevUrl}}\">back</a>{{/hasPrev}}"
            + "{{#hasNext}}<a rel=\"next\" href=\"{{nextUrl}}\">more</a>{{/hasNext}}</nav>"
            + "{{/directory}}"
            + "{{^directory}}<h1>{{title}}</h1>{{{body}}}{{/directory}}"
            + "</body></html>"));
  }

  private void post(String uri, String title, String body) throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "post", "published", "on", "uri", uri, "title", title, "body", body));
  }

  @Test
  public void tickingTheBoxPublishesAListingOfEverythingUsingTheTemplate() throws Exception {
    blog("", 10);
    post("/blog/one", "The first one", "Hello from the first post.");
    post("/blog/two", "The second one", "And the second.");

    try (Http http = new Http()) {
      Http.Response index = http.get(server.port, "example.org", "/blog");
      assertEquals(200, index.status);
      assertTrue(index.bodyContains("The first one"));
      assertTrue(index.bodyContains("The second one"));
      assertTrue("with a plain excerpt of each", index.bodyContains("Hello from the first post."));
      assertTrue(index.bodyContains("page 1 of 1"));
      // and the pages themselves still render through the same template
      assertTrue(http.get(server.port, "example.org", "/blog/one").bodyContains("The first one"));
    }
  }

  @Test
  public void anIndexIsASecondTemplateWithItsOwnMarkup() throws Exception {
    // one body cannot be both a document and a list, and a file that opens with {{#directory}} is
    // a file nobody can edit six months later
    admin.get("/admin/templates/new");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "post",
        "body", "<h1>{{title}}</h1>{{{body}}}", "directory", "on",
        "directory_path", "/notes", "directory_page_size", "10", "directory_order", "newest"));
    io.hearth.content.TemplateRecord template = store().templateByName("post");
    assertTrue("ticking the box gets a working listing rather than a second empty box",
        template.hasOwnIndex());

    post("/notes/one", "The first note", "Words.");
    try (Http http = new Http()) {
      assertTrue(http.get(server.port, "example.org", "/notes").bodyContains("The first note"));
    }

    Browser.Page saved = admin.submitToAndFollow("/admin/templates/directories",
        Map.of("action", "save", "name", "post",
        "directory", "on", "directory_path", "/notes", "directory_page_size", "10",
        "newest", "on",
        "directory_body", "<main>MY OWN INDEX{{#entries}}[{{title}}]{{/entries}}</main>"));
    assertTrue(saved.body(), saved.contains("publishes its index"));
    assertTrue("the body is what was typed",
        store().templateByName("post").directoryBody().contains("MY OWN INDEX"));
    try (Http http = new Http()) {
      Http.Response index = http.get(server.port, "example.org", "/notes");
      assertTrue(index.body, index.bodyContains("MY OWN INDEX"));
      assertTrue(index.body, index.bodyContains("[The first note]"));
    }
  }

  @Test
  public void aTemplateWrittenTheOldWayIsLeftAlone() throws Exception {
    // it branches on {{#directory}} internally, which is exactly the shape the second body
    // replaces -- breaking it to make a point would be a poor trade
    blog("", 10);
    assertFalse(store().templateByName("post").hasOwnIndex());
    post("/blog/one", "Still working", "Yes.");
    try (Http http = new Http()) {
      assertTrue(http.get(server.port, "example.org", "/blog").bodyContains("Still working"));
    }
  }

  @Test
  public void savingThePageTemplateDoesNotSwitchTheIndexOff() throws Exception {
    blog("", 10);
    admin.get("/admin/templates/edit/post");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "post",
        "body", "<h1>changed</h1>{{{body}}}"));
    assertTrue("a form that does not mention the index keeps it; the alternative is somebody"
        + " finding a 404 where their blog used to be",
        store().templateByName("post").publishesDirectory());
    assertEquals("/blog", store().templateByName("post").directoryPath());
  }

  @Test
  public void theDirectoriesScreenListsWhoPublishesWhat() throws Exception {
    blog("", 10);
    Browser.Page page = admin.get("/admin/templates/directories");
    assertEquals(200, page.status());
    assertTrue(page.contains("post"));
    assertTrue(page.contains("/blog"));
  }

  @Test
  public void aListingIsPaginatedAndPageOneHasOneAddress() throws Exception {
    blog("", 2);
    for (int k = 1; k <= 5; k++) {
      post("/blog/" + k, "Post " + k, "Body " + k);
    }
    try (Http http = new Http()) {
      Http.Response first = http.get(server.port, "example.org", "/blog");
      assertTrue(first.bodyContains("page 1 of 3"));
      assertTrue(first.bodyContains("/blog/page/2"));
      assertFalse("page one is the bare path, never /page/1", first.bodyContains("/blog/page/1"));

      Http.Response second = http.get(server.port, "example.org", "/blog/page/2");
      assertEquals(200, second.status);
      assertTrue(second.bodyContains("page 2 of 3"));
      assertTrue("and back goes to the bare path", second.bodyContains("href=\"/blog\""));

      // past the end there is no listing; the request falls through to the site's catch-all
      // rather than rendering an empty page that looks like the blog ran out
      assertFalse(http.get(server.port, "example.org", "/blog/page/9").bodyContains("page 9 of"));
    }
  }

  @Test
  public void thePatternDecidesHowPageTwoIsAddressed() throws Exception {
    blog("/blog?page={page}", 2);
    for (int k = 1; k <= 4; k++) {
      post("/blog/" + k, "Post " + k, "Body " + k);
    }
    try (Http http = new Http()) {
      // mustache escapes '=' inside an attribute, so the href ships as /blog?page&#61;2 -- which
      // is the same address once a browser has parsed it
      assertTrue(http.get(server.port, "example.org", "/blog").bodyContains("/blog?page&#61;2"));
      Http.Response second = http.get(server.port, "example.org", "/blog?page=2");
      assertEquals(200, second.status);
      assertTrue(second.bodyContains("page 2 of 2"));
    }
  }

  @Test
  public void newestFirstIsBySomethingThatDoesNotMoveWhenSomebodyFixesATypo() throws Exception {
    blog("", 10);
    post("/blog/old", "Older", "First written.");
    post("/blog/new", "Newer", "Written later.");
    // editing the older one must not push it to the top: a blog that reshuffles when somebody
    // fixes a typo is one nobody can find anything in
    post("/blog/old", "Older", "First written, corrected.");

    try (Http http = new Http()) {
      String body = http.get(server.port, "example.org", "/blog").body;
      assertTrue(body.indexOf("Newer") < body.indexOf("Older"));
    }
  }

  @Test
  public void aListingCarriesOnlyWhatIsPublishedAndNotWhatIsLockedAwayFromModels() throws Exception {
    blog("", 10);
    post("/blog/live", "Published", "Everybody sees this.");
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "post", "uri", "/blog/draft", "title", "A draft", "body", "not yet"));
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "post", "published", "on", "human_only", "on",
        "uri", "/blog/private", "title", "Human only", "body", "ours"));

    try (Http http = new Http()) {
      String body = http.get(server.port, "example.org", "/blog").body;
      assertTrue(body.contains("Published"));
      assertFalse("a draft is not on the listing", body.contains("A draft"));
      assertFalse("nor is a page locked away", body.contains("Human only"));
    }
  }

  @Test
  public void aRealPageAtTheIndexAddressWins() throws Exception {
    blog("", 10);
    post("/blog/one", "The first one", "Hello.");
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "", "published", "on", "uri", "/blog", "title", "About the blog",
        "body", "Somebody wrote this on purpose."));
    try (Http http = new Http()) {
      // the page is the thing somebody wrote; the listing is a property of a setting
      assertTrue(http.get(server.port, "example.org", "/blog")
          .bodyContains("Somebody wrote this on purpose."));
    }
  }

  @Test
  public void aTemplateWithNoListingMarkupStillShowsOne() throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "post",
        "directory", "on", "directory_path", "/notes", "directory_page_size", "10",
        "body", "<html><body>{{{body}}}</body></html>"));
    post("/notes/one", "A note", "Something.");
    try (Http http = new Http()) {
      // the day somebody ticks the box before writing the listing half, they get a plain list
      // rather than a blank page
      assertTrue(http.get(server.port, "example.org", "/notes").bodyContains("A note"));
    }
  }

  @Test
  public void anIndexNeedsAnAddressAndAPatternNeedsTheToken() throws Exception {
    assertTrue(admin.submitToAndFollow("/admin/templates", Map.of("action", "save",
        "name", "broken", "directory", "on", "directory_path", "blog", "body", "x"))
        .contains("starting with"));
    assertTrue(admin.submitToAndFollow("/admin/templates", Map.of("action", "save",
        "name", "broken", "directory", "on", "directory_path", "/blog",
        "directory_pattern", "/blog/page/2", "body", "x"))
        .contains("{page}"));
  }

  @Test
  public void theSettingsSurviveASaveAndShowUpOnTheListing() throws Exception {
    blog("/blog/p/{page}", 4);
    TemplateRecord template = store().templateByName("post");
    assertTrue(template.directory());
    assertEquals("/blog", template.directoryPath());
    assertEquals("/blog/p/{page}", template.directoryPattern());
    assertEquals(4, template.pageSize());
    assertTrue(template.newestFirst());
    assertEquals("/blog", template.urlFor(1));
    assertEquals("/blog/p/3", template.urlFor(3));
    assertTrue(admin.get("/admin/templates").contains("/blog"));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
