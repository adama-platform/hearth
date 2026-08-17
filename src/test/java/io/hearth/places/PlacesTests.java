package io.hearth.places;

import io.hearth.auth.Accounts;
import io.hearth.content.TemplateField;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The address book, with the two scenarios it was built for.
 *
 * A carnivore supper club keeping ranches with grass-finished and cuts-sold; an MS group keeping
 * vendors with a discount and somebody to ask for. Neither of those fields exists in this codebase,
 * which is the point -- so most of what is checked here is that a community can invent a kind of
 * place and everything downstream follows: the editor asks the right questions, the listing grows a
 * column, the search finds a value nobody wrote code for, and a model can fill one in without being
 * able to invent a field.
 */
public class PlacesTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"KC Meat Up\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    member = signIn("member@example.com");
    // the address book is behind approval like the rest of the community
    Accounts accounts = server.auth.forDomain("example.org");
    accounts.users.approve(accounts.users.byEmail("member@example.com").id(),
        accounts.users.byEmail("boss@example.com").id());
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

  // ---- inventing a kind --------------------------------------------------------------------------

  @Test
  public void aCommunityInventsAKindAndTheEditorAsksItsQuestions() throws Exception {
    ranchKind();

    String html = boss.get("/admin/places/new").body();
    assertTrue("the editor for a ranch asks about ranch things",
        html.contains("Grass finished"));
    assertTrue(html.contains("Cuts sold"));
    assertTrue(html.contains("name=\"field_grass_finished\""));
  }

  @Test
  public void changingWhatAKindRecordsChangesEveryEditorForIt() throws Exception {
    ranchKind();
    places().saveType("ranch", "Ranch", "Ranches", "",
        List.of(new TemplateField("delivers", TemplateField.Type.text, "Delivers to", "", false)),
        "", "", true, 0, null);

    String html = boss.get("/admin/places/new").body();
    assertTrue(html.contains("Delivers to"));
    assertFalse("the fields are data, so this took no code change", html.contains("Grass finished"));
  }

  @Test
  public void twoKindsRecordDifferentThings() throws Exception {
    ranchKind();
    vendorKind();

    long ranch = place("ranch", "Oak Hill", Map.of("grass_finished", "yes", "cuts", "beef, pork"));
    long vendor = place("vendor", "Mobility Plus", Map.of("discount", "15%", "ask_for", "Dee"));

    assertEquals("yes", places().byId(ranch).values().get("grass_finished"));
    assertEquals("15%", places().byId(vendor).values().get("discount"));
    assertNull("a ranch does not have a discount", places().byId(ranch).values().get("discount"));
  }

  @Test
  public void aFieldTheKindNeverDeclaredIsDroppedRatherThanStored() throws Exception {
    ranchKind();
    Places.Type type = places().typeBySlug("ranch");
    LinkedHashMap<String, String> given = new LinkedHashMap<>();
    given.put("grass_finished", "yes");
    given.put("invented", "nonsense");

    String blob = Places.valuesToBlob(type.fields(), given);
    assertTrue(blob.contains("grass_finished"));
    assertFalse("a blob that accumulates keys from a form that has changed is unreasonable-about",
        blob.contains("invented"));
  }

  @Test
  public void aRequiredFieldIsRequired() throws Exception {
    places().saveType("venue", "Venue", "Venues", "",
        List.of(new TemplateField("capacity", TemplateField.Type.text, "How many fit", "", true)),
        "", "", true, 0, null);

    LinkedHashMap<String, String> form = placeForm("venue", "The Back Room");
    Browser.Page done = boss.submitToAndFollow("/admin/places", form);
    assertTrue(done.body(), done.contains("required"));
    assertEquals(0, places().count());
  }

  // ---- removing a kind keeps the addresses ------------------------------------------------------

  @Test
  public void removingAKindMovesItsAddressesRatherThanDeletingThem() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));
    publish(place("ranch", "Elm Farm", Map.of("grass_finished", "no")));

    Browser.Page done = boss.submitToAndFollow("/admin/places/kinds",
        Map.of("action", "delete", "slug", "ranch"));
    assertTrue(done.body(), done.contains("2 address"));
    assertTrue("and says nothing was lost", done.contains("nothing was deleted"));

    assertEquals("deleting somebody's forty ranches because they removed a heading is not a thing"
        + " software should do", 2, places().count());
    assertNull(places().typeBySlug("ranch"));
    assertEquals(2, places().countIn(Places.DEFAULT_TYPE));
  }

  @Test
  public void addressesThatMoveComeOffTheListing() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));
    boss.submitToAndFollow("/admin/places/kinds", Map.of("action", "delete", "slug", "ranch"));

    Places.Place moved = places().bySlug(Places.DEFAULT_TYPE, "oak-hill");
    assertNotNull(moved);
    assertFalse("re-listing under a heading nobody chose is worse than putting it aside",
        moved.published());
  }

  @Test
  public void whatTheOldKindRecordedIsKeptRatherThanThrownAway() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes", "cuts", "beef")));
    boss.submitToAndFollow("/admin/places/kinds", Map.of("action", "delete", "slug", "ranch"));

    Places.Place moved = places().bySlug(Places.DEFAULT_TYPE, "oak-hill");
    assertEquals("move it back and it is there again", "yes",
        moved.values().get("grass_finished"));
    assertEquals("beef", moved.values().get("cuts"));
  }

  @Test
  public void theUnsortedKindAlwaysExistsAndRefusesToGo() throws Exception {
    assertNotNull("seeded at boot, so an address whose kind goes always has somewhere",
        places().typeBySlug(Places.DEFAULT_TYPE));

    Browser.Page done = boss.submitToAndFollow("/admin/places/kinds",
        Map.of("action", "delete", "slug", Places.DEFAULT_TYPE));
    assertTrue(done.body(), done.contains("cannot be removed"));
    assertNotNull(places().typeBySlug(Places.DEFAULT_TYPE));
  }

  @Test
  public void unsortedIsNotListedToMembersByDefault() throws Exception {
    assertEquals("nothing is claimed about an unsorted address, so it is not on show",
        404, member.get("/places/unsorted").status());
  }

  // ---- changing a kind ---------------------------------------------------------------------------

  @Test
  public void changingAKindTakesThePlaceOffTheListing() throws Exception {
    ranchKind();
    vendorKind();
    long id = publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));
    assertTrue(places().byId(id).published());

    LinkedHashMap<String, String> form = placeForm("vendor", "Oak Hill");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("published", "on");
    boss.submitToAndFollow("/admin/places", form);

    assertEquals("vendor", places().byId(id).typeSlug());
    assertFalse("what was recorded belonged to the old kind; somebody looks before it goes back on",
        places().byId(id).published());
  }

  @Test
  public void savingWithoutChangingTheKindLeavesItPublished() throws Exception {
    ranchKind();
    long id = publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));

    LinkedHashMap<String, String> form = placeForm("ranch", "Oak Hill Cattle Co");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("published", "on");
    form.put("field_grass_finished", "yes");
    boss.submitToAndFollow("/admin/places", form);

    assertTrue("an ordinary edit is not a kind change", places().byId(id).published());
  }

  @Test
  public void changingAKindKeepsWhatTheOldOneRecorded() throws Exception {
    ranchKind();
    vendorKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes", "cuts", "beef"));

    LinkedHashMap<String, String> form = placeForm("vendor", "Oak Hill");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("field_discount", "10%");
    boss.submitToAndFollow("/admin/places", form);

    Places.Place after = places().byId(id);
    assertEquals("10%", after.values().get("discount"));
    assertEquals("throwing away somebody's typing because a heading moved is the same mistake",
        "yes", after.values().get("grass_finished"));
  }

  @Test
  public void theEditorShipsEveryKindsQuestionsSoTheSwapNeedsNoReload() throws Exception {
    ranchKind();
    vendorKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    String html = boss.get("/admin/places/edit/" + id).body();
    assertTrue("every kind's declarations, so changing one is a swap rather than a page load",
        html.contains("data-shapes="));
    assertTrue(html.contains("grass_finished"));
    assertTrue("including the kind that is not on screen", html.contains("discount"));
    assertTrue("and the values it already holds", html.contains("data-values="));
    assertTrue("carried in a hidden field, so an answer typed and swapped away from survives",
        html.contains("name=\"fields_json\""));
    assertFalse("nothing tells anybody to save to see the new questions",
        html.contains("until you save"));
  }

  @Test
  public void theShapesAreInAnAttributeRatherThanInTheScript() throws Exception {
    ranchKind();
    String html = boss.get("/admin/places/new").body();
    // invariant 22: mustache escapes for HTML and a script block does not decode entities, so a
    // blob interpolated into the script would arrive mangled
    // the layout's own theme script comes first on every page, so this asks about the editor's
    int script = html.indexOf("<script", html.indexOf("data-shapes="));
    int shapes = html.indexOf("data-shapes=");
    assertTrue("the blob is in the markup", shapes > 0);
    assertTrue("and before the script, in an attribute the parser decodes", shapes < script);
  }

  @Test
  public void anEditorStillWorksWithNoScriptAtAll() throws Exception {
    ranchKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    String html = boss.get("/admin/places/edit/" + id).body();
    assertTrue("the server renders the current kind's boxes, so a browser with no script is fine",
        html.contains("name=\"field_grass_finished\""));
    assertTrue("filled in", html.contains("value=\"yes\""));
  }

  @Test
  public void aValueCarriedFromAKindNoLongerOnScreenSurvivesTheSave() throws Exception {
    ranchKind();
    vendorKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    // what the editor's script posts after somebody typed under ranch, switched to vendor, and
    // saved: the ranch answer is carried rather than being in the database
    LinkedHashMap<String, String> form = placeForm("vendor", "Oak Hill");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("field_discount", "10%");
    form.put("fields_json", "{\"grass_finished\":\"grain, actually\",\"discount\":\"10%\"}");
    boss.submitToAndFollow("/admin/places", form);

    Places.Place after = places().byId(id);
    assertEquals("10%", after.values().get("discount"));
    assertEquals("an answer typed and then swapped away from is not in the database yet",
        "grain, actually", after.values().get("grass_finished"));
  }

  @Test
  public void aCarriedBlobIsUntrustedLikeAnyOtherField() throws Exception {
    ranchKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    LinkedHashMap<String, String> form = placeForm("ranch", "Oak Hill");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("field_grass_finished", "yes");
    form.put("fields_json", "this is not json at all");
    boss.submitToAndFollow("/admin/places", form);

    assertEquals("an unreadable blob is no values rather than a failed save",
        "yes", places().byId(id).values().get("grass_finished"));
  }

  @Test
  public void aCarriedBlobCannotInventAFieldName() throws Exception {
    ranchKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    LinkedHashMap<String, String> form = placeForm("ranch", "Oak Hill");
    form.put("id", Long.toString(id));
    form.put("slug", "oak-hill");
    form.put("field_grass_finished", "yes");
    form.put("fields_json", "{\"Not A Name!\":\"x\",\"legitimate\":\"kept\"}");
    boss.submitToAndFollow("/admin/places", form);

    Map<String, String> values = places().byId(id).values();
    assertFalse("it never decides what is declared, only what is remembered",
        values.containsKey("Not A Name!"));
    assertEquals("kept", values.get("legitimate"));
  }

  // ---- the public pages ---------------------------------------------------------------------------

  @Test
  public void theListingGrowsAColumnForWhateverTheKindRecords() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes", "cuts", "beef")));

    Browser.Page page = member.get("/places/ranch");
    assertEquals(200, page.status());
    assertTrue(page.contains("Oak Hill"));
    assertTrue("a field nobody wrote code for, on the page", page.contains("Grass finished"));
    assertTrue(page.contains("yes"));
  }

  @Test
  public void aPlaceHasItsOwnPageAtAStableAddress() throws Exception {
    ranchKind();
    long id = publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));

    Browser.Page page = member.get("/places/ranch/oak-hill");
    assertEquals(200, page.status());
    assertTrue(page.contains("Oak Hill"));

    // renaming the place must not break the link somebody sent round
    Places.Place place = places().byId(id);
    places().save(new Places.Place(place.id(), place.typeSlug(), place.slug(),
        "Oak Hill Cattle Co", place.address(), place.locality(), place.region(), place.postcode(),
        place.country(), null, null, place.url(), place.phone(), place.email(), place.fields(),
        place.body(), true, false, null, null, null), null);
    assertEquals("the slug is the address, not the name", 200,
        member.get("/places/ranch/oak-hill").status());
  }

  @Test
  public void searchingFindsAValueInAFieldThisCodeHasNeverHeardOf() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "grass finished", "cuts", "beef")));
    publish(place("ranch", "Elm Farm", Map.of("grass_finished", "grain finished", "cuts", "pork")));

    Browser.Page page = member.get("/places/ranch?q=grass");
    assertTrue(page.contains("Oak Hill"));
    assertFalse(page.contains("Elm Farm"));
  }

  @Test
  public void anUnpublishedPlaceOrKindIsNotThere() throws Exception {
    ranchKind();
    place("ranch", "Not Yet", Map.of("grass_finished", "yes"));
    assertEquals("a draft is not listed", 404,
        member.get("/places/ranch/not-yet").status());

    places().saveType("hidden", "Hidden", "Hidden", "", List.of(), "", "", false, 0, null);
    assertEquals(404, member.get("/places/hidden").status());
  }

  @Test
  public void theAddressBookNeedsYouSignedIn() throws Exception {
    ranchKind();
    publish(place("ranch", "Oak Hill", Map.of()));
    Browser stranger = new Browser(server.port, "example.org");
    assertEquals("a list of vendors who help people with MS is not for the open web",
        303, stranger.get("/places").status());
  }

  @Test
  public void aKindCanNameATemplateAndEveryPlaceOfThatKindGetsIt() throws Exception {
    ranchKind();
    accounts().site.store().saveTemplate("ranchpage",
        "<html><body><h1>RANCH: {{name}}</h1><p>{{extra.grass_finished}}</p></body></html>",
        "[]", null);
    places().saveType("ranch", "Ranch", "Ranches", "", places().typeBySlug("ranch").fields(),
        "ranchpage", "", true, 0, null);
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));

    Browser.Page page = member.get("/places/ranch/oak-hill");
    assertTrue(page.body(), page.contains("RANCH: Oak Hill"));
    assertTrue("and the template can reach the invented fields by name", page.contains("yes"));
  }

  @Test
  public void aKindNamingATemplateThatIsGoneFallsBackRatherThanBreaking() throws Exception {
    ranchKind();
    places().saveType("ranch", "Ranch", "Ranches", "", places().typeBySlug("ranch").fields(),
        "never-existed", "", true, 0, null);
    publish(place("ranch", "Oak Hill", Map.of("grass_finished", "yes")));

    Browser.Page page = member.get("/places/ranch/oak-hill");
    assertEquals("the built-in page is enough to be useful", 200, page.status());
    assertTrue(page.contains("Oak Hill"));
  }

  // ---- renaming, which content got wrong once -----------------------------------------------------

  @Test
  public void movingAPlaceToAnotherKindMovesItRatherThanCloningIt() throws Exception {
    ranchKind();
    vendorKind();
    long id = place("ranch", "Oak Hill", Map.of("grass_finished", "yes"));

    Places.Place place = places().byId(id);
    places().save(new Places.Place(place.id(), "vendor", place.slug(), place.name(),
        place.address(), place.locality(), place.region(), place.postcode(), place.country(),
        null, null, place.url(), place.phone(), place.email(), "{}", place.body(), false, false,
        null, null, null), null);

    assertEquals("one row, moved", 1, places().count());
    assertNull(places().bySlug("ranch", "oak-hill"));
    assertNotNull(places().bySlug("vendor", "oak-hill"));
  }

  // ---- plumbing -----------------------------------------------------------------------------------

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private Places places() {
    return accounts().places;
  }

  private void ranchKind() throws Exception {
    places().saveType("ranch", "Ranch", "Ranches", "Where the meat comes from",
        List.of(new TemplateField("grass_finished", TemplateField.Type.text, "Grass finished",
                "grass, grain, or both", false),
            new TemplateField("cuts", TemplateField.Type.text, "Cuts sold", "", false)),
        "", "", true, 0, null);
  }

  private void vendorKind() throws Exception {
    places().saveType("vendor", "Vendor", "Vendors", "People who look after us",
        List.of(new TemplateField("discount", TemplateField.Type.text, "The deal", "", false),
            new TemplateField("ask_for", TemplateField.Type.text, "Ask for", "", false)),
        "", "", true, 0, null);
  }

  private static LinkedHashMap<String, String> placeForm(String kind, String name) {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("type_slug", kind);
    form.put("name", name);
    return form;
  }

  private long place(String kind, String name, Map<String, String> fields) throws Exception {
    LinkedHashMap<String, String> form = placeForm(kind, name);
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      form.put("field_" + entry.getKey(), entry.getValue());
    }
    boss.submitToAndFollow("/admin/places", form);
    Places.Place made = places().bySlug(kind, Places.slugify(name));
    assertNotNull("was not created: " + name, made);
    return made.id();
  }

  private long publish(long id) throws Exception {
    Places.Place place = places().byId(id);
    places().save(new Places.Place(place.id(), place.typeSlug(), place.slug(), place.name(),
        place.address(), place.locality(), place.region(), place.postcode(), place.country(),
        place.latitude(), place.longitude(), place.url(), place.phone(), place.email(),
        place.fields(), place.body(), true, place.humanOnly(), null, null, null), null);
    return id;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
