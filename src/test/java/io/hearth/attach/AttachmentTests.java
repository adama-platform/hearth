package io.hearth.attach;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Uploads: what may be one, who may read one, and what a browser is told about it.
 *
 * The security here is not incidental to the feature, it *is* the feature. A server that will hold
 * a file and hand it back has three ways to be badly wrong: it can serve something a browser will
 * execute on this community's own domain, it can hand a private photograph to whoever guesses a
 * number, and it can become a free image host for a stranger's forum. Everything below is one of
 * those three.
 */
public class AttachmentTests {
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

  // ---- what may be uploaded ---------------------------------------------------------------------

  @Test
  public void aPictureGoesUpAndComesBack() throws Exception {
    long id = upload("supper.jpg", "image/jpeg", bytes(2048), Map.of(
        "description", "the table before anybody arrived", "folder", "suppers/2026-05",
        "tags", "supper table", "public", "on"));

    Attachments.Attachment made = store().byId(id);
    assertEquals("supper.jpg", made.filename());
    assertEquals("jpg", made.extension());
    assertEquals("image/jpeg", made.mime());
    assertEquals(Kinds.Kind.image, made.kind());
    assertEquals(2048, made.bytes());
    assertEquals("suppers/2026-05", made.folder());
    assertEquals("supper table", made.tags());

    Browser.Page fetched = admin.get("/attachment/" + id + ".jpg");
    assertEquals(200, fetched.status());
    assertEquals(2048, fetched.body().getBytes(StandardCharsets.ISO_8859_1).length);
  }

  @Test
  public void theBytesLandWhereTheyAreSupposedTo() throws Exception {
    long id = upload("supper.jpg", "image/jpeg", bytes(64), Map.of());
    String path = server.attachmentFiles.pathOf(id, "jpg");
    assertTrue(path, path.endsWith("/jpg/" + (id % 100) + "/" + id + ".blob"));
    assertNotNull("and the bytes are actually there", server.attachmentFiles.get(id, "jpg"));
  }

  @Test
  public void whatTheBrowserSaysTheFileIsCountsForNothing() throws Exception {
    // the whole thing: a member uploads a picture, declares it text/html, and this community's own
    // domain would then serve attacker-written HTML to members signed in to it
    long id = upload("photo.png", "text/html", "<script>alert(1)</script>".getBytes(), Map.of());
    assertEquals("image/png", store().byId(id).mime());
    try (Http http = new Http()) {
      Http.Response answer = http.send(server.port, "example.org", "GET",
          "/attachment/" + id + ".png", null, "Cookie", cookie());
      assertEquals("image/png", answer.header("content-type"));
      assertEquals("and nothing is to go looking for a better answer in the bytes",
          "nosniff", answer.header("x-content-type-options"));
    }
  }

  @Test
  public void anExtensionThisServerWillNotServeIsRefused() throws Exception {
    assertNull(uploadOrNull("evil.html", "text/html", "<h1>hi</h1>".getBytes()));
    assertNull(uploadOrNull("evil.svg", "image/svg+xml", "<svg/>".getBytes()));
    assertNull(uploadOrNull("run.js", "text/javascript", "alert(1)".getBytes()));
    assertNull("a second extension does not launder the first",
        uploadOrNull("report.pdf.exe", "application/pdf", bytes(10)));
    assertEquals(0, store().count());
  }

  @Test
  public void aCommunityCanNarrowTheList() throws Exception {
    Configs pictures = Configs.dir().domain("pictures.example.org",
        "{\"name\":\"Pictures\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"attachments\":{\"extensions\":[\"jpg\",\"png\"]}}");
    try (TestServer other = TestServer.ofConfigs(pictures.file())) {
      Browser boss = new Browser(other.port, "pictures.example.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@example.com"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      String printed = boss.get("/admin/attachments").body();
      assertTrue(printed.contains("jpg, png"));
      assertFalse("and the ones it did not name are not offered", printed.contains(".mp4"));
    } finally {
      pictures.delete();
    }
  }

  @Test
  public void anExtensionThisServerHasNeverHeardOfStopsTheDomainLoading() throws Exception {
    Configs bad = Configs.dir().domain("bad.example.org",
        "{\"name\":\"Bad\",\"attachments\":{\"extensions\":[\"exe\"]}}");
    try {
      io.hearth.vhost.DomainScanner.scan(bad.file(), io.hearth.common.Verbose.OFF);
      org.junit.Assert.fail("an allow list with something unserveable on it has to be fatal");
    } catch (io.hearth.common.ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("does not know how to serve safely"));
    } finally {
      bad.delete();
    }
  }

  // ---- who may read one -------------------------------------------------------------------------

  @Test
  public void aPrivateFileIsNotThereForAStranger() throws Exception {
    long id = upload("private.jpg", "image/jpeg", bytes(32), Map.of());
    assertFalse("private is the default, because guessing wrong the other way is a photograph"
        + " everybody outside can see", store().byId(id).isPublic());
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/attachment/" + id + ".jpg");
      assertEquals("a 404 rather than a 401: whether it exists is itself private", 404,
          answer.status);
    }
    assertEquals("and a member has it", 200, admin.get("/attachment/" + id + ".jpg").status());
  }

  @Test
  public void aPublicFileIsThereForAnybody() throws Exception {
    long id = upload("poster.jpg", "image/jpeg", bytes(32), Map.of("public", "on"));
    try (Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.org", "/attachment/" + id + ".jpg").status);
    }
  }

  @Test
  public void theExtensionHasToBeTheOneOnTheRow() throws Exception {
    long id = upload("clip.mp4", "video/mp4", bytes(64), Map.of("public", "on"));
    try (Http http = new Http()) {
      assertEquals("a page must not be able to dress a video up as a picture",
          404, http.get(server.port, "example.org", "/attachment/" + id + ".jpg").status);
      assertEquals(200, http.get(server.port, "example.org", "/attachment/" + id + ".mp4").status);
    }
  }

  @Test
  public void nothingAboutTheAddressIsAPath() throws Exception {
    try (Http http = new Http()) {
      assertEquals(404, http.get(server.port, "example.org", "/attachment/1.jpg").status);
      assertEquals(404, http.get(server.port, "example.org", "/attachment/0.jpg").status);
      assertEquals(404, http.get(server.port, "example.org", "/attachment/-1.jpg").status);
      assertEquals(404, http.get(server.port, "example.org", "/attachment/abc.jpg").status);
      assertEquals(404, http.get(server.port, "example.org", "/attachment/1.jpg.jpg").status);
    }
    assertNull(AttachmentRoutes.parse("/attachment/../../etc/passwd"));
    assertNull(AttachmentRoutes.parse("/attachment/1/2.jpg"));
    assertNull(AttachmentRoutes.parse("/attachment/1."));
  }

  // ---- somebody else's page ---------------------------------------------------------------------

  @Test
  public void somebodyElsesSiteDoesNotGetToEmbedThese() throws Exception {
    long id = upload("poster.jpg", "image/jpeg", bytes(32), Map.of("public", "on"));
    try (Http http = new Http()) {
      Http.Response hotlinked = http.send(server.port, "example.org", "GET",
          "/attachment/" + id + ".jpg", null, "Referer", "https://someone-elses-forum.example/x");
      assertEquals("without this a community's server is a free image host", 403, hotlinked.status);

      Http.Response ours = http.send(server.port, "example.org", "GET",
          "/attachment/" + id + ".jpg", null, "Referer", "https://example.org/about");
      assertEquals(200, ours.status);

      assertEquals("and no referrer at all is honoured, because browsers omit it constantly",
          200, http.get(server.port, "example.org", "/attachment/" + id + ".jpg").status);
    }
  }

  @Test
  public void aCommunityCanNameOtherSitesThatMayEmbed() {
    java.util.Set<String> allowed = java.util.Set.of("example.org", "partner.example");
    assertTrue(AttachmentRoutes.refererIsOurs(request("https://example.org/x"), allowed));
    assertTrue("a subdomain of ours is ours",
        AttachmentRoutes.refererIsOurs(request("https://www.example.org/x"), allowed));
    assertTrue(AttachmentRoutes.refererIsOurs(request("https://partner.example/x"), allowed));
    assertFalse(AttachmentRoutes.refererIsOurs(request("https://elsewhere.example/x"), allowed));
    assertFalse("and one nobody can parse is not a page on this site",
        AttachmentRoutes.refererIsOurs(request("not a url"), allowed));
  }

  // ---- what a browser is told --------------------------------------------------------------------

  @Test
  public void browsersMayCacheAndProxiesMayNot() throws Exception {
    long id = upload("poster.jpg", "image/jpeg", bytes(32), Map.of("public", "on"));
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/attachment/" + id + ".jpg");
      String cache = answer.header("cache-control");
      assertTrue(cache, cache.startsWith("private,"));
      assertTrue("these are frequently a photograph of somebody's children", cache.contains("private"));
      assertFalse(cache.contains("public"));
      assertTrue("the url carries an id, so the bytes at it never change", cache.contains("immutable"));
      assertNotNull(answer.header("etag"));
    }
  }

  @Test
  public void aPictureIsShownAndEverythingElseIsDownloaded() throws Exception {
    long picture = upload("poster.jpg", "image/jpeg", bytes(32), Map.of("public", "on"));
    long document = upload("menu.pdf", "application/pdf", bytes(32), Map.of("public", "on"));
    try (Http http = new Http()) {
      assertTrue(http.get(server.port, "example.org", "/attachment/" + picture + ".jpg")
          .header("content-disposition").startsWith("inline"));
      assertTrue("anything this server cannot render safely is a download",
          http.get(server.port, "example.org", "/attachment/" + document + ".pdf")
              .header("content-disposition").startsWith("attachment"));
    }
  }

  // ---- the cache ---------------------------------------------------------------------------------

  @Test
  public void theSecondRequestComesOutOfMemory() throws Exception {
    long id = upload("poster.jpg", "image/jpeg", bytes(4096), Map.of("public", "on"));
    admin.get("/attachment/" + id + ".jpg");
    long hits = server.attachments.cache().stats().hits();
    admin.get("/attachment/" + id + ".jpg");
    assertEquals("one photograph and forty browsers is one read of the disk",
        hits + 1, server.attachments.cache().stats().hits());
  }

  @Test
  public void theCacheIsBoundedByBytesAndKeepsWhatIsBeingAskedFor() {
    BlobCache cache = new BlobCache(1000);
    cache.put(1, "jpg", new byte[200]);
    cache.put(2, "jpg", new byte[200]);
    cache.put(3, "jpg", new byte[200]);
    assertNotNull(cache.get(1, "jpg"));   // touching 1 makes it the warmest
    cache.put(4, "jpg", new byte[200]);
    cache.put(5, "jpg", new byte[200]);
    cache.put(6, "jpg", new byte[200]);   // over the budget: something has to go
    assertNotNull("the one being asked for survives", cache.get(1, "jpg"));
    assertNull("and the coldest does not", cache.get(2, "jpg"));
    assertTrue(cache.stats().heldBytes() <= 1000);

    assertNull("one blob larger than a share of the budget is never admitted at all",
        put(cache, 9, new byte[900]));
  }

  @Test
  public void deletingTakesTheBytesAndTheCachedCopy() throws Exception {
    long id = upload("poster.jpg", "image/jpeg", bytes(32), Map.of("public", "on"));
    admin.get("/attachment/" + id + ".jpg");
    admin.get("/admin/attachments");
    admin.submitToAndFollow("/admin/attachments",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertNull(store().byId(id));
    assertNull("the file goes with the row", server.attachmentFiles.get(id, "jpg"));
    assertNull("and so does what was cached of it", server.attachments.cache().get(id, "jpg"));
    assertEquals(404, admin.get("/attachment/" + id + ".jpg").status());
  }

  // ---- finding one again --------------------------------------------------------------------------

  @Test
  public void thingsAreFoundByFolderByKindAndByWord() throws Exception {
    upload("cake.jpg", "image/jpeg", bytes(16), Map.of(
        "description", "the cake", "folder", "suppers/2026-05", "tags", "cake supper"));
    upload("hall.jpg", "image/jpeg", bytes(16), Map.of(
        "description", "the hall", "folder", "places", "tags", "venue"));
    upload("talk.mp3", "audio/mpeg", bytes(16), Map.of(
        "description", "the talk", "folder", "suppers/2026-05", "tags", "recording"));

    assertEquals(2, store().search("suppers", null, null, 50).size());
    assertEquals("a folder covers what is under it",
        2, store().search("suppers/2026-05", null, null, 50).size());
    assertEquals(1, store().search(null, "audio", null, 50).size());
    assertEquals(1, store().search(null, null, "cake", 50).size());
    assertEquals("and it searches what somebody said it is, not only the filename",
        1, store().search(null, null, "hall", 50).size());
    assertTrue(store().folders().contains("suppers"));
    assertTrue("every parent of a folder is a folder", store().folders().contains("suppers/2026-05"));
  }

  @Test
  public void aFolderIsAPathAndNothingElse() {
    assertEquals("suppers/2026-05", Attachments.folderOf(" Suppers/2026-05 "));
    assertEquals("a folder is never a way out of anywhere", "etc/passwd",
        Attachments.folderOf("../../etc/passwd"));
    assertEquals("last-summer", Attachments.folderOf("Last Summer"));
    assertEquals("", Attachments.folderOf("///"));
  }

  @Test
  public void movingAFolderMovesWhatIsUnderIt() throws Exception {
    upload("a.jpg", "image/jpeg", bytes(16), Map.of("folder", "suppers/may"));
    upload("b.jpg", "image/jpeg", bytes(16), Map.of("folder", "suppers"));
    admin.get("/admin/attachments");
    admin.submitToAndFollow("/admin/attachments",
        Map.of("action", "move", "from", "suppers", "to", "dinners"));
    assertEquals(2, store().search("dinners", null, null, 50).size());
    assertEquals(0, store().search("suppers", null, null, 50).size());
  }

  // ---- putting one in a page -----------------------------------------------------------------------

  @Test
  public void theEditorOffersAPickerAndTheLineToPaste() throws Exception {
    long id = upload("cake.jpg", "image/jpeg", bytes(16), Map.of("description", "the cake"));
    Browser.Page editor = admin.get("/admin/content/new");
    assertTrue("a button that opens the picker", editor.contains("data-pick="));

    Browser.Page picker = admin.get("/admin/attachments/list?pick=1");
    assertTrue(picker.body(), picker.contains("data-insert=\"![the cake](/attachment/" + id + ".jpg)\""));
  }

  @Test
  public void whatToPasteDependsOnWhatItIs() throws Exception {
    long clip = upload("clip.mp4", "video/mp4", bytes(16), Map.of("description", "the toast"));
    long song = upload("song.mp3", "audio/mpeg", bytes(16), Map.of());
    String panel = admin.get("/admin/attachments/list").body();
    assertTrue("markdown has no way to say video", panel.contains("&lt;video controls"));
    assertTrue(panel.contains("&lt;audio controls"));
    assertTrue(clip > 0 && song > 0);
  }

  // ---- who may upload -------------------------------------------------------------------------------

  @Test
  public void somebodyWithoutThePermissionCannotUploadOrSeeTheScreen() throws Exception {
    Browser member = signIn("ana@example.com");
    assertEquals("a 403 would confirm what is behind it", 404,
        member.get("/admin/attachments").status());
    assertEquals("and the upload path does not confirm its own existence either",
        404, member.uploadTo("/attachment/upload", "x.jpg", "image/jpeg", bytes(16), Map.of()));
    assertEquals(0, store().count());
  }

  @Test
  public void anOversizeUploadIsRefusedFromTheRequestLine() throws Exception {
    // the pipeline reads the declared length and refuses before the body is buffered at all
    try (Http http = new Http()) {
      byte[] big = new byte[3 * 1024 * 1024];
      Http.Response answer = http.send(server.port, "example.org", "POST", "/",
          big, "Content-Type", "application/x-www-form-urlencoded");
      assertEquals(413, answer.status);
    }
  }

  @Test
  public void erasingSomebodyLeavesTheirPhotographsAndTakesTheirName() throws Exception {
    Browser ana = signIn("ana@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    server.auth.forDomain("example.org").users.approve(id, null);
    server.auth.forDomain("example.org").roles.grant(id, "admin", null);
    long file = uploadAs(ana, "summer.jpg", "image/jpeg", bytes(16), Map.of());

    io.hearth.people.Erasure.erase(server.auth.forDomain("example.org"), null,
        server.auth.forDomain("example.org").users.byId(id), null, false);
    Attachments.Attachment kept = store().byId(file);
    assertNotNull("a photograph of last summer is part of what everybody remembers", kept);
    assertEquals("", kept.uploadedByEmail());
    assertNull(kept.uploadedBy());
  }

  // ---- plumbing ---------------------------------------------------------------------------------------

  private Attachments store() {
    return server.auth.forDomain("example.org").attachments;
  }

  private static byte[] bytes(int howMany) {
    byte[] made = new byte[howMany];
    java.util.Arrays.fill(made, (byte) 'x');
    return made;
  }

  private static byte[] put(BlobCache cache, long id, byte[] bytes) {
    cache.put(id, "jpg", bytes);
    return cache.get(id, "jpg");
  }

  private static io.netty.handler.codec.http.FullHttpRequest request(String referer) {
    io.netty.handler.codec.http.FullHttpRequest req =
        new io.netty.handler.codec.http.DefaultFullHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            io.netty.handler.codec.http.HttpMethod.GET, "/attachment/1.jpg");
    req.headers().set("Referer", referer);
    return req;
  }

  private String cookie() {
    return admin.cookieHeader();
  }

  private long upload(String filename, String type, byte[] body, Map<String, String> fields)
      throws Exception {
    Long id = uploadOrNull(filename, type, body, fields);
    assertNotNull("expected " + filename + " to be accepted", id);
    return id;
  }

  private Long uploadOrNull(String filename, String type, byte[] body) throws Exception {
    return uploadOrNull(filename, type, body, Map.of());
  }

  private Long uploadOrNull(String filename, String type, byte[] body, Map<String, String> fields)
      throws Exception {
    long before = store().count();
    uploadAsRaw(admin, filename, type, body, fields);
    java.util.List<Attachments.Attachment> all = store().all(10);
    return store().count() > before ? all.get(0).id() : null;
  }

  private long uploadAs(Browser who, String filename, String type, byte[] body,
                        Map<String, String> fields) throws Exception {
    uploadAsRaw(who, filename, type, body, fields);
    return store().all(1).get(0).id();
  }

  private void uploadAsRaw(Browser who, String filename, String type, byte[] body,
                           Map<String, String> fields) throws Exception {
    who.get("/admin/attachments");
    who.uploadTo("/attachment/upload", filename, type, body, fields);
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
