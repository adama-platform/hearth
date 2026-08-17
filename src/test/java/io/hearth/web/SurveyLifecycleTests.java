package io.hearth.web;

import io.hearth.people.AnswerSheet;
import io.hearth.people.Question;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Retiring a question, and answering one without losing the last one.
 *
 * Both of these are about data quietly disappearing. A survey is only useful if it can keep
 * changing -- questions get added, reworded, and retired -- and every one of those movements is a
 * chance to destroy answers somebody already gave. So: deleting is soft and the cascade is a
 * separate deliberate act, and saving answers is a merge rather than a replace.
 */
public class SurveyLifecycleTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    member = signIn("member@example.com");
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
    List<Question> questions = server.auth.forDomain("example.org").people.allQuestions();
    return questions.get(questions.size() - 1).id();
  }

  private void settle() {
    assertTrue(server.auth.forDomain("example.org").survey.settle(5000));
  }

  private AnswerSheet sheet() throws Exception {
    var accounts = server.auth.forDomain("example.org");
    return accounts.people.answersOf(accounts.users.byEmail("member@example.com").id());
  }

  // ---- the merge --------------------------------------------------------------------------------

  @Test
  public void answeringANewQuestionDoesNotEraseTheOldOnes() throws Exception {
    // the whole reason saving is a merge. The page shows what is unanswered, so the form somebody
    // submits mentions a fraction of the questions that exist -- and a replace would take that
    // fraction as the new truth
    long first = ask("Why did you join?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + first, "a friend brought me"));
    settle();
    assertEquals("a friend brought me", sheet().answerTo(first));

    long second = ask("What should we do next?");
    member.get("/survey");
    // exactly what the browser sends: only the question that was on screen
    member.submitTo("/survey", Map.of("action", "answers", "q" + second, "a picnic"));
    settle();

    assertEquals("the new answer landed", "a picnic", sheet().answerTo(second));
    assertEquals("and the old one survived", "a friend brought me", sheet().answerTo(first));
    assertEquals(2, sheet().answered());
  }

  @Test
  public void revisingAnAnswerReplacesOnlyThatAnswer() throws Exception {
    long first = ask("Why did you join?");
    long second = ask("What should we do next?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers",
        "q" + first, "a friend", "q" + second, "a picnic"));
    settle();

    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + first, "I read about it"));
    settle();
    assertEquals("I read about it", sheet().answerTo(first));
    assertEquals("a picnic", sheet().answerTo(second));
  }

  @Test
  public void clearingAnAnswerIsStillPossible() throws Exception {
    // a merge must not make erasing impossible: an empty box that was submitted means "take this
    // back", which is different from a box that was never on the page
    long id = ask("Why did you join?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "a friend"));
    settle();
    assertEquals(1, sheet().answered());

    member.submitTo("/survey", Map.of("action", "answers", "q" + id, ""));
    settle();
    assertEquals(0, sheet().answered());
    assertEquals("and it goes back on the list", 1,
        server.auth.forDomain("example.org").survey.remainingFor(
            server.auth.forDomain("example.org").users.byEmail("member@example.com").id()));
  }

  // ---- the survey page --------------------------------------------------------------------------

  @Test
  public void theQuestionsPageLeadsWithWhatIsLeft() throws Exception {
    long answered = ask("Why did you join?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + answered, "a friend"));
    settle();
    ask("What should we do next?");

    Browser.Page page = member.get("/survey");
    assertTrue("what is outstanding is what the page opens on", page.contains("1 to go"));
    assertTrue(page.contains("What should we do next?"));
    assertFalse("and what is already answered is not in the way",
        page.contains("Why did you join?"));
    assertTrue("but it is one link away", page.contains("/survey?all=1"));
  }

  @Test
  public void theOtherViewIsEverythingAnsweredAndEditable() throws Exception {
    long id = ask("Why did you join?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "a friend"));
    settle();

    Browser.Page page = member.get("/survey?all=1");
    assertTrue("the question is there", page.contains("Why did you join?"));
    assertTrue("with what they said in the box", page.contains("a friend"));
    // the flag rides in a hidden field so the save comes back to the same view
    assertTrue(page.contains("name=\"all\""));
  }

  @Test
  public void whenThereIsNothingLeftItSaysSo() throws Exception {
    long id = ask("Why did you join?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "a friend"));
    settle();

    Browser.Page page = member.get("/survey");
    assertTrue(page.contains("All answered"));
    assertTrue("and points at the only thing left to do, which is to change your mind",
        page.contains("read back and change what you said"));
  }

  // ---- soft delete -------------------------------------------------------------------------------

  @Test
  public void retiringAQuestionHidesItWithoutTouchingAnswers() throws Exception {
    long id = ask("Going away?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "my answer"));
    settle();

    admin.submitToAndFollow("/admin/survey", Map.of("action", "delete", "id", Long.toString(id)));
    settle();

    var people = server.auth.forDomain("example.org").people;
    assertTrue("gone from the live list", people.allQuestions().isEmpty());
    assertEquals("waiting for cleanup", 1, people.deletedQuestions().size());
    assertEquals("and the answer is exactly where it was", "my answer", sheet().answerTo(id));
    assertEquals("it stops counting", 0, sheet().countedAgainst(people.publishedQuestions()).answered());
    assertFalse("and members do not see it",
        member.get("/survey").contains("Going away?"));
  }

  @Test
  public void theSurveyPageSaysSomethingIsWaitingToBeCleanedUp() throws Exception {
    long id = ask("Going away?");
    admin.submitToAndFollow("/admin/survey", Map.of("action", "delete", "id", Long.toString(id)));
    Browser.Page page = admin.get("/admin/survey");
    assertTrue(page.contains("1 retired, waiting for cleanup"));
    assertTrue(admin.get("/admin/survey/retired/list").contains("Going away?"));
  }

  @Test
  public void askingItAgainBringsBackTheAnswers() throws Exception {
    // the point of the soft delete: "delete" is the button people press by accident
    long id = ask("Going away?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "my answer"));
    settle();
    admin.submitToAndFollow("/admin/survey", Map.of("action", "delete", "id", Long.toString(id)));
    settle();

    admin.submitToAndFollow("/admin/survey/retired",
        Map.of("action", "restore", "id", Long.toString(id)));
    settle();

    var people = server.auth.forDomain("example.org").people;
    assertEquals(1, people.allQuestions().size());
    assertTrue(people.deletedQuestions().isEmpty());
    assertEquals("nothing was ever lost", "my answer", sheet().answerTo(id));
    assertEquals(1, sheet().countedAgainst(people.publishedQuestions()).answered());
  }

  @Test
  public void committingTheCleanupIsTheCascadeAndItSaysWhatItDid() throws Exception {
    long id = ask("Going away?");
    member.get("/survey");
    member.submitTo("/survey", Map.of("action", "answers", "q" + id, "my answer"));
    settle();
    admin.submitToAndFollow("/admin/survey", Map.of("action", "delete", "id", Long.toString(id)));
    settle();

    Browser.Page done = admin.submitToAndFollow("/admin/survey/retired",
        Map.of("action", "purge", "id", Long.toString(id)));
    settle();

    assertTrue("an irreversible rewrite has to report what it rewrote",
        done.contains("1 answer sheet(s) were rewritten"));
    var people = server.auth.forDomain("example.org").people;
    assertTrue(people.deletedQuestions().isEmpty());
    assertNull("now the answer really is gone", sheet().answerTo(id));
  }

  @Test
  public void aLiveQuestionCannotBePurgedByGuessingTheUrl() throws Exception {
    long id = ask("Still being asked");
    Browser.Page refused = admin.submitToAndFollow("/admin/survey/retired",
        Map.of("action", "purge", "id", Long.toString(id)));
    assertTrue(refused.contains("not waiting to be cleaned up"));
    assertEquals(1, server.auth.forDomain("example.org").people.allQuestions().size());
  }
}
