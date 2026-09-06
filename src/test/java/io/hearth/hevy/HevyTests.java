package io.hearth.hevy;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The gym: a key somebody handed over, and Hevy reached on their behalf.
 *
 * Nothing here touches the network. {@link StubHevy} answers in Hevy's own shapes, which is enough
 * to prove the parts this server is responsible for: that the key is per person and never
 * reachable by anybody else, that the enums are checked before a request goes out rather than
 * after, and that a refusal says which of the three things went wrong.
 *
 * What is deliberately not proved here is that Hevy accepts what this sends -- that needs their
 * server, and it is written down under "not verified" rather than faked with a mock that agrees
 * with whatever the code happens to do.
 */
public class HevyTests {
  private Configs configs;
  private TestServer server;
  private Browser me;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    me = signIn("boss@example.com");
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

  private UserKeys keys() {
    return server.auth.forDomain("example.org").userKeys;
  }

  private long meId() throws Exception {
    return server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
  }

  // ---- the key ---------------------------------------------------------------------------------

  @Test
  public void aKeyIsSavedFromTheOwnPageAndNeverShownBack() throws Exception {
    me.get("/self?tab=keys");
    me.submitTo("/self", Map.of("action", "hevy_key", "api_key", "hevy-secret-abcd1234"));

    assertTrue(keys().has(meId(), UserKeys.Service.hevy));
    assertEquals("hevy-secret-abcd1234", keys().secretFor(meId(), UserKeys.Service.hevy));

    String page = me.get("/self?tab=keys").body();
    assertFalse("the key is never printed back", page.contains("hevy-secret-abcd1234"));
    assertTrue("only enough to tell two keys apart", page.contains("1234"));
  }

  @Test
  public void anEmptyBoxRemovesIt() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "hevy-secret-abcd1234");
    me.get("/self?tab=keys");
    me.submitTo("/self", Map.of("action", "hevy_key", "api_key", ""));
    assertFalse(keys().has(meId(), UserKeys.Service.hevy));
    assertNull(keys().secretFor(meId(), UserKeys.Service.hevy));
  }

  @Test
  public void thePageCarriesHevysOwnDisclaimer() throws Exception {
    String page = me.get("/self?tab=keys").body();
    assertTrue(page, page.contains("use it at your own risk"));
    assertTrue("and where to get one", page.contains("hevy.com/settings?developer"));
    assertTrue("and that it is a paid feature of theirs", page.contains("Hevy Pro"));
  }

  /**
   * One person's key is not another person's.
   *
   * The surface takes no argument for whose key to use -- every call is the actor's -- so this
   * asserts the storage keeps them apart, which is the half that could be got wrong quietly.
   */
  @Test
  public void keysAreOnePerPerson() throws Exception {
    Browser friend = signIn("ana@example.com");
    friend.get("/self?tab=keys");
    friend.submitTo("/self", Map.of("action", "hevy_key", "api_key", "ana-key-9999"));
    keys().save(meId(), UserKeys.Service.hevy, "boss-key-1111");

    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    assertEquals("ana-key-9999", keys().secretFor(anaId, UserKeys.Service.hevy));
    assertEquals("boss-key-1111", keys().secretFor(meId(), UserKeys.Service.hevy));
  }

  @Test
  public void erasingSomebodyTakesTheirKeyWithThem() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "hevy-secret-abcd1234");
    keys().forget(meId());
    assertNull("a credential held after they are gone is the plainest breach there is",
        keys().secretFor(meId(), UserKeys.Service.hevy));
  }

  // ---- reaching Hevy ---------------------------------------------------------------------------

  @Test
  public void withNoKeyTheRefusalSaysWhereToGetOne() throws Exception {
    Hevy hevy = new Hevy(keys(), StubHevy.answering(200, "{}"));
    try {
      hevy.workouts(meId(), 1, 10);
      org.junit.Assert.fail("should have refused");
    } catch (Hevy.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("no Hevy key"));
      assertTrue(refused.getMessage(), refused.getMessage().contains("hevy.com/settings"));
    }
  }

  @Test
  public void theKeyTravelsInTheApiKeyHeaderAndNowhereElse() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "the-secret");
    StubHevy stub = StubHevy.answering(200, "{\"workouts\":[]}");
    new Hevy(keys(), stub).workouts(meId(), 1, 10);

    assertEquals("the-secret", stub.lastHeader("api-key"));
    assertTrue("and the host is fixed", stub.lastUri().startsWith("https://api.hevyapp.com/"));
    assertFalse("never in the query string", stub.lastUri().contains("the-secret"));
  }

  @Test
  public void aRejectedKeySaysSoRatherThanReportingAnOutage() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "stale");
    try {
      new Hevy(keys(), StubHevy.answering(401, "nope")).workouts(meId(), 1, 10);
      org.junit.Assert.fail("should have refused");
    } catch (Hevy.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("refused the key"));
    }
  }

  @Test
  public void anIdThatIsNotAnIdNeverReachesTheWire() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "k");
    StubHevy stub = StubHevy.answering(200, "{}");
    try {
      new Hevy(keys(), stub).workout(meId(), "../../v1/user/info");
      org.junit.Assert.fail("should have refused");
    } catch (IllegalArgumentException expected) {
      assertNull("nothing was sent at all", stub.lastUri());
    }
  }

  // ---- the tools a model gets -------------------------------------------------------------------

  @Test
  public void theGymToolsAreOfferedAndDocumented() throws Exception {
    McpClient grok = new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");
    String tools = grok.listTools().body();

    for (String tool : new String[]{"gym_workouts", "gym_exercises", "gym_exercise_create",
        "gym_routine_create", "gym_routine_update", "gym_folders"}) {
      assertTrue(tool + " is not offered", tools.contains(tool));
    }
    assertTrue("the enums a model has to choose from are in the description",
        tools.contains("bodyweight_reps"));
    assertTrue(tools.contains("resistance_band"));
    assertTrue("and it is told to invent rather than approximate",
        tools.contains("Do not"));
  }

  @Test
  public void anAgentWithNoKeyIsToldWhatToDo() throws Exception {
    McpClient grok = new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");
    String refusal = grok.call("gym_workouts").refusal();
    assertTrue(refusal, refusal.contains("no Hevy key"));
  }

  /**
   * An enum is checked here rather than by Hevy.
   *
   * A refusal that names the field and lists what is allowed costs a model one turn; a 400 from
   * somebody else's server costs it several, and the message it gets back is not ours to write.
   */
  @Test
  public void aBadEnumIsRefusedBeforeAnythingIsSent() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "k");
    McpClient grok = new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");

    String refusal = grok.call("gym_exercise_create", "title", "90/90 Hip Switch",
        "exercise_type", "stretching", "equipment_category", "none",
        "muscle_group", "glutes").refusal();
    assertTrue(refusal, refusal.contains("exercise_type"));
    assertTrue("and says what is allowed", refusal.contains("duration"));
  }

  @Test
  public void aRoutineWithAnExerciseAndNoSetsIsRefused() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "k");
    McpClient grok = new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");

    String refusal = grok.call("gym_routine_create", "title", "Mobility",
        "exercises", java.util.List.of(Map.of("exercise_template_id", "ABC123"))).refusal();
    assertTrue(refusal, refusal.contains("no sets"));
  }

  @Test
  public void aRoutineExerciseWithoutATemplateIdIsRefusedWithWhereToGetOne() throws Exception {
    keys().save(meId(), UserKeys.Service.hevy, "k");
    McpClient grok = new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");

    String refusal = grok.call("gym_routine_create", "title", "Mobility",
        "exercises", java.util.List.of(Map.of("sets", java.util.List.of(
            Map.of("type", "normal", "reps", 10))))).refusal();
    assertTrue(refusal, refusal.contains("exercise_template_id"));
    assertTrue("and points at the tool that makes one", refusal.contains("gym_exercise_create"));
  }
}
