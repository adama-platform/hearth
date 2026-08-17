package io.hearth.people;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The first three minutes, and the survey as a place of its own.
 *
 * The two belong in one file because they are one path: somebody arrives with nothing on their
 * account, is asked what to call them, walks into the community's questions, and can come back to
 * those questions for as long as they are a member. The interesting cases are the refusals and the
 * merge -- a welcome flow that quietly erased an answer, or a name that could be skipped, would
 * both look like they worked.
 */
public class OrientationTests {
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

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  private long ask(String prompt) throws Exception {
    admin.get("/admin/survey/new");
    admin.submitTo("/admin/survey", Map.of("action", "save", "prompt", prompt, "kind", "free",
        "options", "", "position", "0", "min", "1", "max", "5", "published", "on"));
    settle();
    var questions = server.auth.forDomain("example.org").people.allQuestions();
    return questions.get(questions.size() - 1).id();
  }

  private void settle() {
    assertTrue(server.auth.forDomain("example.org").survey.settle(5000));
  }

  private AnswerSheet sheetOf(String email) throws Exception {
    var accounts = server.auth.forDomain("example.org");
    return accounts.people.answersOf(accounts.users.byEmail(email).id());
  }

  private ProfileRecord profileOf(String email) throws Exception {
    var accounts = server.auth.forDomain("example.org");
    return accounts.people.profileOf(accounts.users.byEmail(email).id());
  }

  // ---- the welcome ------------------------------------------------------------------------------

  @Test
  public void somebodyBrandNewIsSentToTheWelcomeAndAskedTheirName() throws Exception {
    Browser newcomer = new Browser(server.port, "example.org");
    newcomer.get("/register");
    newcomer.submit(Map.of("email", "ana@example.com"));
    Browser.Page done = newcomer.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("/welcome", done.location());

    Browser.Page welcome = newcomer.get("/welcome");
    assertEquals(200, welcome.status());
    assertTrue(welcome.contains("What should we call you?"));
    assertTrue(welcome.contains("display_name"));
  }

  @Test
  public void theNameIsRefusedWhenItIsBlankRatherThanSavedEmpty() throws Exception {
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    Browser.Page refused = newcomer.submitTo("/welcome",
        Map.of("action", "name", "display_name", "  ", "location", "", "about", ""));

    assertEquals("still on step one", 200, refused.status());
    assertTrue(refused.contains("A name is the one thing we do need"));
    assertTrue("and nothing was stored", profileOf("ana@example.com").displayName().isBlank());
  }

  @Test
  public void theNameLandsAndTheNextStepIsTheCommunitysQuestions() throws Exception {
    long id = ask("Why did you join?");
    Browser newcomer = signIn("ana@example.com");

    newcomer.get("/welcome");
    Browser.Page named = newcomer.submitTo("/welcome", Map.of("action", "name",
        "display_name", "Ana", "location", "Austin", "about", "I make bread."));
    assertEquals(303, named.status());
    assertEquals("/welcome?step=2", named.location());
    assertEquals("Ana", profileOf("ana@example.com").displayName());
    assertEquals("Austin", profileOf("ana@example.com").location());

    Browser.Page questions = newcomer.get("/welcome?step=2");
    assertTrue("the survey is what step two is", questions.contains("Why did you join?"));

    Browser.Page answered = newcomer.submitTo("/welcome",
        Map.of("action", "answers", "q" + id, "a friend brought me"));
    assertEquals("/welcome?step=3", answered.location());
    settle();
    assertEquals("a friend brought me", sheetOf("ana@example.com").answerTo(id));
  }

  @Test
  public void itIsSkippableAtTheQuestionsAndNotAtTheName() throws Exception {
    ask("Why did you join?");
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));

    Browser.Page questions = newcomer.get("/welcome?step=2");
    assertTrue("a way past the questions", questions.contains("Skip for now"));
    // "Skip to the content" is in every layout, so this asks about the button rather than the word
    assertFalse("but no way past the name",
        newcomer.get("/welcome?step=1").contains("Skip for now"));
  }

  @Test
  public void theWelcomeDoesNotEraseAnAnswerSomebodyAlreadyGave() throws Exception {
    // every box on the welcome is a question nobody has answered, so an empty one is somebody
    // skipping. Treating it as an erasure would let the flow delete what was said before it.
    long id = ask("Why did you join?");
    Browser member = signIn("ana@example.com");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "a friend"));
    settle();

    member.get("/welcome?step=2");
    member.submitTo("/welcome", Map.of("action", "answers", "q" + id, ""));
    settle();
    assertEquals("a friend", sheetOf("ana@example.com").answerTo(id));
  }

  @Test
  public void signingInAgainAfterwardsGoesWhereItAlwaysDid() throws Exception {
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    newcomer.forgetCookies();

    newcomer.get("/login");
    newcomer.submit(Map.of("email", "ana@example.com"));
    Browser.Page again = newcomer.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("unapproved, so their own page -- but never the welcome twice",
        "/self", again.location());
  }

  @Test
  public void theWelcomeNeedsASession() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/welcome");
    assertEquals(303, page.status());
    assertTrue(page.location(), page.location().startsWith("/login"));
  }

  // ---- the survey as a page ----------------------------------------------------------------------

  @Test
  public void theSurveyNeedsASessionAndComesBackToItselfAfterwards() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/survey");
    assertEquals(303, page.status());
    assertEquals("/login?next=%2Fsurvey", page.location());
  }

  @Test
  public void theTwoViewsAreWhatIsLeftAndWhatYouSaid() throws Exception {
    long first = ask("Why did you join?");
    long second = ask("What should we do next?");
    Browser member = signIn("ana@example.com");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + first, "a friend"));
    settle();

    Browser.Page todo = member.get("/survey");
    assertTrue("what is outstanding", todo.contains("What should we do next?"));
    assertFalse("and only that", todo.contains("Why did you join?"));

    Browser.Page all = member.get("/survey?all=1");
    assertTrue(all.contains("Why did you join?"));
    assertTrue(all.contains("What should we do next?"));
    assertTrue("with the answer in the box", all.contains("a friend"));
    assertNotNull(second);
  }

  @Test
  public void savingFromTheAllViewComesBackToTheAllView() throws Exception {
    long id = ask("Why did you join?");
    Browser member = signIn("ana@example.com");
    member.get("/survey?all=1");
    Browser.Page saved = member.submitTo("/survey",
        Map.of("action", "answers", "all", "1", "q" + id, "a friend"));
    assertEquals(303, saved.status());
    assertTrue(saved.location(), saved.location().startsWith("/survey?all=1"));
  }

  @Test
  public void savingNothingSaysNothingRatherThanThankingYou() throws Exception {
    ask("Why did you join?");
    Browser member = signIn("ana@example.com");
    member.get("/survey");
    Browser.Page saved = member.submitTo("/survey", Map.of("action", "answers"));
    Browser.Page back = member.follow(saved);
    assertTrue(back.contains("Nothing to save."));
  }

  @Test
  public void theQuestionsAreInTheBarWithTheirNumberOnceThereAreAny() throws Exception {
    Browser member = signIn("ana@example.com");
    assertFalse("a community that asks nothing has no entry for it",
        member.get("/self").contains("href=\"/survey\""));

    ask("Why did you join?");
    settle();
    Browser.Page page = member.get("/self");
    assertTrue("and one that does, does", page.contains("href=\"/survey\""));
    assertTrue("with the number waiting on it", page.contains("<span class=\"bubble\">1</span>"));
  }

  @Test
  public void aCommunityCanSwitchTheWholeThingOff() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.example.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"],\"disabled\":[\"survey\"]}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      Browser member = new Browser(other.port, "quiet.example.org");
      member.get("/register");
      member.submit(Map.of("email", "ana@example.com"));
      member.submit(Map.of("code", other.mail().lastCodeFor("ana@example.com")));

      Browser.Page page = member.get("/survey");
      assertEquals("a switched-off surface must still answer -- a handler that writes nothing"
          + " holds the connection open, which is invisible in a log",
          404, page.status());
      assertFalse("nor is it in the bar", page.contains("href=\"/survey\""));

      // ...and the welcome still asks the one thing that is not part of the survey
      assertTrue(member.get("/welcome").contains("display_name"));
    } finally {
      quiet.delete();
    }
  }

  @Test
  public void aFormWithNoTokenChangesNothingAndLandsBackOnThePage() throws Exception {
    // the double-submit check, on both new pages. A refusal here is silent and lands somewhere
    // sensible, because there is nothing worth an error screen about an expired form.
    long id = ask("Why did you join?");
    Browser member = signIn("ana@example.com");
    member.get("/survey");

    Browser.Page survey = member.submitRaw("/survey",
        Map.of("action", "answers", "q" + id, "smuggled"));
    assertEquals(303, survey.status());
    assertEquals("/survey", survey.location());
    settle();
    assertEquals("and nothing was stored", null, sheetOf("ana@example.com").answerTo(id));

    Browser.Page welcome = member.submitRaw("/welcome",
        Map.of("action", "name", "display_name", "Smuggled"));
    assertEquals(303, welcome.status());
    assertEquals("/welcome", welcome.location());
    assertTrue(profileOf("ana@example.com").displayName().isBlank());
  }

  @Test
  public void anImpossibleStepIsTheFirstOne() throws Exception {
    Browser member = signIn("ana@example.com");
    assertTrue("a step that is not a number", member.get("/welcome?step=nonsense")
        .contains("What should we call you?"));
    assertTrue("or one past the end", member.get("/welcome?step=99").contains("That is everything"));
    assertTrue("or below the start", member.get("/welcome?step=0")
        .contains("What should we call you?"));
  }

  @Test
  public void anActionTheWelcomeDoesNotHaveIsARedirectRatherThanAnError() throws Exception {
    Browser member = signIn("ana@example.com");
    member.get("/welcome");
    Browser.Page odd = member.submitTo("/welcome", Map.of("action", "invent"));
    assertEquals(303, odd.status());
    assertEquals("/welcome", odd.location());
  }

  @Test
  public void aNonsenseCountInTheUrlSaysNothingAtAll() throws Exception {
    // the number rides in the redirect, so it is somebody else's input by the time it is read
    ask("Why did you join?");
    Browser member = signIn("ana@example.com");
    Browser.Page page = member.get("/survey?done=lots");
    assertEquals(200, page.status());
    assertFalse(page.contains("answers saved"));
    assertFalse(page.contains("Nothing to save."));
  }

  @Test
  public void thereIsNothingToAnswerBeforeAnybodyHasAsked() throws Exception {
    Browser member = signIn("ana@example.com");
    Browser.Page page = member.get("/survey");
    assertEquals(200, page.status());
    assertTrue(page.contains("Nothing is being asked yet."));

    Browser.Page welcome = member.get("/welcome?step=2");
    assertTrue("and the welcome says so rather than showing an empty form",
        welcome.contains("Nothing is being asked at the moment."));
  }

  @Test
  public void itRemembersHowFarSomebodyGotAndPicksThemUpThere() throws Exception {
    ask("Why did you join?");
    Browser newcomer = signIn("ana@example.com");
    assertEquals("nobody has started", 0, profileOf("ana@example.com").orientationStep());

    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    assertEquals(1, profileOf("ana@example.com").orientationStep());

    // they close the tab and come back later: the questions, not the name box they already filled
    Browser.Page back = newcomer.get("/welcome");
    assertTrue(back.contains("A few questions"));
    assertTrue(back.contains("Picking up where you left off."));
    assertFalse("and not the step they finished", back.contains("What should we call you?"));
  }

  @Test
  public void reachingTheEndIsWhatFinishesIt() throws Exception {
    ask("Why did you join?");
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    assertFalse(profileOf("ana@example.com").oriented());

    // skipping the questions is a decision rather than a failure to finish, so the last screen
    // counts however somebody arrived at it
    newcomer.get("/welcome?step=3");
    assertTrue(profileOf("ana@example.com").oriented());
    assertEquals(3, profileOf("ana@example.com").orientationStep());

    // ...and coming back lands on the last screen rather than starting over. The wording differs
    // -- somebody returning is thanked rather than told it is everything -- so this asks about the
    // screen rather than about the sentence.
    Browser.Page again = newcomer.get("/welcome");
    assertTrue("and it stays finished", again.contains("Go to Example"));
    assertTrue(again.contains("Thank you"));
  }

  @Test
  public void answeringTheQuestionsCountsAsTheSecondStep() throws Exception {
    long id = ask("Why did you join?");
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    newcomer.get("/welcome?step=2");
    newcomer.submitTo("/welcome", Map.of("action", "answers", "q" + id, "a friend"));
    assertEquals(2, profileOf("ana@example.com").orientationStep());
  }

  @Test
  public void progressOnlyGoesForwards() throws Exception {
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    newcomer.get("/welcome?step=3");
    assertEquals(3, profileOf("ana@example.com").orientationStep());

    // going back and saving the name again must not take them back to step one -- the number is
    // only worth having because it says what somebody actually did
    newcomer.get("/welcome?step=1");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana Rivera",
        "location", "", "about", ""));
    assertEquals(3, profileOf("ana@example.com").orientationStep());
    assertEquals("Ana Rivera", profileOf("ana@example.com").displayName());
  }

  @Test
  public void thereAreNeverMoreThanThreeQuestionsOnAScreen() throws Exception {
    // a wall of boxes is a thing people close
    for (int k = 1; k <= 7; k++) {
      ask("Question number " + k + "?");
    }
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));

    Browser.Page page = newcomer.get("/welcome?step=2");
    assertTrue(page.contains("Question number 1?"));
    assertTrue(page.contains("Question number 3?"));
    assertFalse("the fourth waits its turn", page.contains("Question number 4?"));
    assertTrue("and it says how many are behind it", page.contains("4 more after these"));
    assertTrue("with a way straight past all of it", page.contains("Skip for now"));
  }

  @Test
  public void answeringThreeBringsTheNextThreeRatherThanTheFinishLine() throws Exception {
    long first = ask("One?");
    long second = ask("Two?");
    long third = ask("Three?");
    ask("Four?");
    Browser newcomer = signIn("ana@example.com");
    newcomer.get("/welcome");
    newcomer.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));

    newcomer.get("/welcome?step=2");
    Browser.Page next = newcomer.submitTo("/welcome", Map.of("action", "answers",
        "q" + first, "a", "q" + second, "b", "q" + third, "c"));
    assertEquals("/welcome?step=2", next.location());
    assertTrue("somebody who just answered three is the likeliest person to answer three more",
        newcomer.get("/welcome?step=2").contains("Four?"));
  }

  @Test
  public void aReturningMemberIsAskedWhatTheCommunityHasSinceWantedToKnow() throws Exception {
    Browser ana = signIn("ana@example.com");
    ana.get("/welcome");
    ana.submitTo("/welcome", Map.of("action", "name", "display_name", "Ana",
        "location", "", "about", ""));
    ana.get("/welcome?step=3");
    assertTrue(profileOf("ana@example.com").oriented());

    // the community asks something new while they are away
    ask("What would you like more of?");
    ana.forgetCookies();
    ana.get("/login");
    ana.submit(Map.of("email", "ana@example.com"));
    Browser.Page back = ana.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("signing in is where the wizard comes back", "/welcome?step=2", back.location());

    Browser.Page asked = ana.get("/welcome?step=2");
    assertTrue(asked.contains("Welcome back"));
    assertTrue(asked.contains("What would you like more of?"));

    // and once they have answered, signing in is ordinary again
    ana.submitTo("/welcome", Map.of("action", "answers",
        "q" + server.auth.forDomain("example.org").people.allQuestions().get(0).id(), "cake"));
    settle();
    ana.forgetCookies();
    ana.get("/login");
    ana.submit(Map.of("email", "ana@example.com"));
    assertEquals("/self", ana.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com"))).location());
  }

  @Test
  public void everyPageSaysHowManyAnswersAreOwedUntilThereAreNone() throws Exception {
    long id = ask("Why did you join?");
    Browser ana = signIn("ana@example.com");
    settle();
    assertTrue("one line, above everything, only when there is something to do",
        ana.get("/self").contains("There is a question waiting for you."));

    ana.get("/survey");
    ana.submitTo("/survey", Map.of("action", "answers", "q" + id, "a friend"));
    settle();
    assertFalse("and gone the moment it is done",
        ana.get("/self").contains("waiting for you"));
  }

  @Test
  public void theOldTabAddressStillGoesSomewhereUseful() throws Exception {
    Browser member = signIn("ana@example.com");
    Browser.Page moved = member.get("/self?tab=questions");
    assertEquals(303, moved.status());
    assertEquals("/survey", moved.location());
  }

  // ---- the name, downstream ---------------------------------------------------------------------

  @Test
  public void anInvitationCannotBeSentBySomebodyWithNoName() throws Exception {
    Browser.Page refused = admin.submitToAndFollow("/admin/invites",
        Map.of("action", "create", "email", "newcomer@example.com", "send", "on"));
    assertTrue(refused.contains("add your name to your profile first"));
    assertEquals("and nothing was written down", 0,
        server.auth.forDomain("example.org").invites.all(10).size());

    admin.get("/self");
    admin.submitTo("/self", Map.of("action", "profile", "display_name", "The Boss",
        "headline", "", "about", "", "location", "", "links", ""));
    admin.submitToAndFollow("/admin/invites",
        Map.of("action", "create", "email", "newcomer@example.com", "send", "on"));
    assertEquals(1, server.auth.forDomain("example.org").invites.all(10).size());
    assertTrue("and the message says who it is from",
        server.mail().inviteSubjects().get(0).contains("The Boss"));
  }

  @Test
  public void theProfileRefusesToSaveWithoutAName() throws Exception {
    Browser member = signIn("ana@example.com");
    member.get("/self");
    Browser.Page refused = member.submitTo("/self", Map.of("action", "profile",
        "display_name", "", "headline", "", "about", "I make bread.", "location", "",
        "links", ""));
    assertEquals(400, refused.status());
    assertTrue(refused.contains("A name is needed"));
    assertTrue("nothing at all was written", profileOf("ana@example.com").about().isBlank());
  }

  @Test
  public void anInviteLinkFillsInTheAddressItWasSentTo() throws Exception {
    admin.get("/self");
    admin.submitTo("/self", Map.of("action", "profile", "display_name", "The Boss",
        "headline", "", "about", "", "location", "", "links", ""));
    admin.submitToAndFollow("/admin/invites",
        Map.of("action", "create", "email", "newcomer@example.com", "send", "on"));
    Invites.Invite invite = server.auth.forDomain("example.org").invites.all(10).get(0);

    Browser invited = new Browser(server.port, "example.org");
    Browser.Page form = invited.get("/register?invite=" + invite.token());
    assertEquals(200, form.status());
    assertTrue("the address they already proved they can read is filled in",
        form.contains("newcomer@example.com"));

    // and a token nobody issued fills in nothing rather than failing the page
    Browser.Page plain = invited.get("/register?invite=nonsense");
    assertEquals(200, plain.status());
    assertFalse(plain.contains("newcomer@example.com"));
  }
}
