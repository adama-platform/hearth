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
