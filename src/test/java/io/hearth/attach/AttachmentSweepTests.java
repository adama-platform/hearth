package io.hearth.attach;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mark and sweep over the uploads, and the ways it could quietly be wrong.
 *
 * The dangerous failure here is not the obvious one. A sweep that keeps too much wastes disk and
 * somebody notices; a sweep that misses one place a url can hide deletes a photograph that is on a
 * page, and nobody finds out until somebody opens that page in six months and sees a broken image
 * with no way to learn what was there. So most of what is below is about the *marking*, and
 * particularly about the two places a reference hides that nobody thinks of: a page's history, and
 * a suggested edit waiting in a queue.
 */
public class AttachmentSweepTests {
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

  // ---- the marking ---------------------------------------------------------------------------

  @Test
  public void aFileNothingPointsAtIsRubbishAndOneOnAPageIsNot() throws Exception {
    long used = upload("used.jpg");
    long loose = upload("loose.jpg");
    page("/about", "Here we are.\n\n![us](/attachment/" + used + ".jpg)");

    AttachmentSweep.Result swept = sweep();
    assertTrue(swept.referenced().contains(used));
    assertFalse(swept.referenced().contains(loose));
    assertEquals(1, swept.unused().size());
    assertEquals(loose, swept.unused().get(0).id());
    assertTrue("and it says where it found the one it kept",
        swept.usesOf(used).get(0).source().equals("pages"));
  }

  @Test
  public void aPagesHistoryCountsEvenWhenTheCurrentVersionDoesNot() throws Exception {
    // the one nobody thinks of: delete this and restoring March produces a broken page, which
    // makes a history that cannot be restored, which is not a history
    long id = upload("was-on-it.jpg");
    long page = page("/about", "![it](/attachment/" + id + ".jpg)");
    admin.get("/admin/content/edit/" + page);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(page),
        "uri", "/about", "title", "About", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "we took the picture out", "published", "on"));

    assertFalse("the page itself no longer mentions it",
        store().byId(page) != null && content().byId(page).body().contains("/attachment/"));
    AttachmentSweep.Result swept = sweep();
    assertTrue("but its history does", swept.referenced().contains(id));
    assertTrue(swept.usesOf(id).stream().anyMatch(use -> use.source().equals("page history")));
  }

  @Test
  public void aSuggestedEditCountsBeforeAnybodyHasAcceptedIt() throws Exception {
    long id = upload("proposed.jpg");
    long page = page("/about", "nothing here yet");
    Browser writer = memberWho("writer@example.com", io.hearth.auth.Permission.content_propose);
    writer.get("/admin/content/edit/" + page);
    writer.submitTo("/admin/content", Map.of("action", "suggest", "id", Long.toString(page),
        "uri", "/about", "title", "About", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "how about ![this](/attachment/" + id + ".jpg)",
        "note", "a picture"));

    AttachmentSweep.Result swept = sweep();
    assertTrue("an edit waiting in the queue is a reference before the fact",
        swept.referenced().contains(id));
  }

  @Test
  public void everywhereElseAReferenceCanHide() throws Exception {
    long onBoard = upload("board.jpg");
    long inComment = upload("comment.jpg");
    long onPlace = upload("place.jpg");
    long onEvent = upload("event.jpg");
    long inProfile = upload("profile.jpg");
    long inTemplate = upload("template.jpg");
    long inFields = upload("fields.jpg");

    admin.get("/board");
    admin.submitTo("/board", Map.of("action", "post", "title", "Saturday",
        "body", "look: ![it](/attachment/" + onBoard + ".jpg)"));
    long post = server.auth.forDomain("example.org").board.all(10).get(0).id();
    admin.get("/board/" + post);
    admin.submitTo("/board", Map.of("action", "reply", "post", Long.toString(post),
        "body", "and ![this](/attachment/" + inComment + ".jpg)"));

    admin.get("/admin/places/new");
    admin.submitTo("/admin/places", Map.of("action", "save", "type_slug", "unsorted",
        "name", "The Oak", "slug", "the-oak", "address", "1 High Street",
        "body", "![outside](/attachment/" + onPlace + ".jpg)", "published", "on"));

    java.time.LocalDate day = java.time.LocalDate.now().plusDays(3);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Supper",
        "body", "![poster](/attachment/" + onEvent + ".jpg)", "location", "", "place_id", "",
        "starts_on", day.toString(), "ends_on", day.toString(), "start_time", "",
        "capacity", "", "published", "on"));

    admin.get("/self");
    admin.submitTo("/self", Map.of("action", "profile", "display_name", "The Boss",
        "headline", "", "about", "me: ![face](/attachment/" + inProfile + ".jpg)",
        "location", "", "links", ""));

    admin.get("/admin/templates/new");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "wrapper",
        "body", "<header><img src=\"/attachment/" + inTemplate + ".jpg\" alt=\"\"></header>"
            + "{{{body}}}"));

    // a template field's value, which lives in a JSON blob on the content row
    content().save(new io.hearth.content.ContentRecord(0, "/fields", "Fields",
        io.hearth.content.ContentRecord.Kind.markdown, null, "",
        "{\"hero\":\"/attachment/" + inFields + ".jpg\"}", "words", true, false, null, null, null),
        null);

    AttachmentSweep.Result swept = sweep();
    for (long id : new long[]{onBoard, inComment, onPlace, onEvent, inProfile, inTemplate,
        inFields}) {
      assertTrue("nothing pointing at " + id + " was found", swept.referenced().contains(id));
    }
    assertEquals("and nothing at all is rubbish", 0, swept.unused().size());
  }

  @Test
  public void aReferenceInAnyShapeCounts() throws Exception {
    long markdown = upload("a.jpg");
    long html = upload("b.mp4");
    long link = upload("c.pdf");
    page("/all", "![a](/attachment/" + markdown + ".jpg)\n"
        + "<video src=\"/attachment/" + html + ".mp4\"></video>\n"
        + "[the menu](/attachment/" + link + ".pdf)");
    AttachmentSweep.Result swept = sweep();
    assertEquals(3, swept.referenced().size());
    assertEquals(0, swept.unused().size());
  }

  // ---- the sweeping --------------------------------------------------------------------------

  @Test
  public void nothingUploadedTodayIsEverOffered() throws Exception {
    // a file uploaded twenty minutes ago is very likely on somebody's clipboard, on its way into a
    // page that does not exist yet
    upload("just-now.jpg");
    assertEquals(0, AttachmentSweep.run(accounts(), AttachmentSweep.GRACE, Instant.now())
        .unused().size());
    assertEquals("and it is offered once it is old enough",
        1, AttachmentSweep.run(accounts(), Duration.ZERO, Instant.now().plusSeconds(60))
            .unused().size());
  }

  @Test
  public void theScreenListsThemAndTheButtonTakesThemAway() throws Exception {
    long used = upload("used.jpg");
    long loose = upload("loose.jpg");
    page("/about", "![us](/attachment/" + used + ".jpg)");
    age(loose);

    Browser.Page screen = admin.get("/admin/attachments/unused");
    assertEquals(200, screen.status());
    assertTrue(screen.contains("loose.jpg"));
    assertFalse("what is on a page is not on this list", screen.contains("used.jpg"));

    Browser.Page done = admin.submitToAndFollow("/admin/attachments/unused",
        Map.of("action", "sweep", "confirm", "delete"));
    assertTrue(done.body(), done.contains("1 file(s) deleted"));
    assertNull(store().byId(loose));
    assertNull("the file goes with the row", server.attachmentFiles.get(loose, "jpg"));
    assertNotNull("and what was on a page is untouched", store().byId(used));
  }

  @Test
  public void nothingHappensWithoutTypingTheWord() throws Exception {
    long loose = upload("loose.jpg");
    age(loose);
    Browser.Page done = admin.submitToAndFollow("/admin/attachments/unused",
        Map.of("action", "sweep", "confirm", ""));
    assertTrue(done.body(), done.contains("Type delete"));
    assertNotNull(store().byId(loose));
  }

  @Test
  public void theScanRunsAgainWhenTheButtonIsPressed() throws Exception {
    // the screen was drawn a minute ago; somebody has put the picture into a page since, and
    // deleting it now would be exactly the failure this whole screen exists to avoid
    long id = upload("racing.jpg");
    age(id);
    assertTrue(admin.get("/admin/attachments/unused").contains("racing.jpg"));

    page("/late", "![just in time](/attachment/" + id + ".jpg)");
    Browser.Page done = admin.submitToAndFollow("/admin/attachments/unused",
        Map.of("action", "sweep", "confirm", "delete"));
    assertTrue(done.body(), done.contains("Nothing was unreferenced"));
    assertNotNull(store().byId(id));
  }

  @Test
  public void theScreenSaysWhereItLookedAndWhatItCannotSee() throws Exception {
    Browser.Page screen = admin.get("/admin/attachments/unused");
    assertTrue("page history", screen.contains("page history"));
    assertTrue(screen.contains("suggested edits"));
    assertTrue(screen.contains("profiles"));
    assertTrue("it finds addresses rather than intentions, and says so",
        screen.contains("not intentions"));
  }

  @Test
  public void somebodyWithoutThePermissionSeesNoDoorAtAll() throws Exception {
    Browser member = signIn("ana@example.com");
    assertEquals(404, member.get("/admin/attachments/unused").status());
    assertEquals(404, member.submitToAndFollow("/admin/attachments/unused",
        Map.of("action", "sweep", "confirm", "delete")).status());
  }

  // ---- plumbing ------------------------------------------------------------------------------

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private Attachments store() {
    return accounts().attachments;
  }

  private io.hearth.content.ContentStore content() {
    return accounts().site.store();
  }

  private AttachmentSweep.Result sweep() throws Exception {
    // zero grace and a moment in the future, because these tests are about the marking rather than
    // about waiting a day
    return AttachmentSweep.run(accounts(), Duration.ZERO, Instant.now().plusSeconds(60));
  }

  /** make one look old enough for the real grace period to let it through */
  private void age(long id) throws Exception {
    try (java.sql.Connection connection = accounts().store.connection();
         java.sql.PreparedStatement statement = connection.prepareStatement(
             "UPDATE attachments SET created_at = ? WHERE id = ?")) {
      statement.setTimestamp(1, new java.sql.Timestamp(
          System.currentTimeMillis() - Duration.ofDays(3).toMillis()));
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  private long upload(String filename) throws Exception {
    admin.get("/admin/attachments");
    admin.uploadTo("/attachment/upload", filename, "image/jpeg", new byte[64], Map.of());
    return store().all(1).get(0).id();
  }

  private long page(String uri, String body) throws Exception {
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", "A page",
        "kind", "markdown", "template_name", "", "nav_folder", "", "body", body,
        "published", "on"));
    return content().byUri(uri).id();
  }

  private Browser memberWho(String email, io.hearth.auth.Permission... permissions)
      throws Exception {
    Browser browser = signIn(email);
    io.hearth.auth.Accounts accounts = accounts();
    accounts.roleDefs.save("helper", "Helper", "", java.util.Set.of(permissions), "", null);
    long id = accounts.users.byEmail(email).id();
    accounts.users.approve(id, null);
    accounts.roles.grant(id, "helper", null);
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
