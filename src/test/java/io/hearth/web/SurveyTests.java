package io.hearth.web;

import io.hearth.people.AnswerSheet;
import io.hearth.people.Question;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import io.hearth.store.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Profiles, questions, answers, and the number that tells somebody there is something to do.
 *
 * Driven through the real pages: an admin asks a question at /admin/survey, a member answers it
 * at /self, and the bubble in the navigation is what closes the loop. The interesting part is the
 * join -- one person's action changes what a different person sees -- and only an end-to-end test
 * proves the event flowed and the indexer ran.
 */
public class SurveyTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\",\"member@example.com\"]}");
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
    Browser client = new Browser(server.port, "example.org");
    client.get("/register");
    client.submit(Map.of("email", email));
    client.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return client;
  }

  private void ask(String prompt, String kind, String options) throws Exception {
    admin.get("/admin/survey/new");
    admin.submitTo("/admin/survey", Map.of("action", "save", "prompt", prompt, "kind", kind,
        "options", options, "position", "0", "min", "1", "max", "5", "published", "on"));
    settle();
  }

  private void settle() {
    assertTrue("the indexer should drain", server.auth.forDomain("example.org").survey.settle(5000));
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  /** what the navigation bubble would say, which is the number a person actually sees */
  private int bubbleFor(String email) throws Exception {
    return server.auth.forDomain("example.org").survey.remainingFor(idOf(email));
  }

  private AnswerSheet sheetOf(String email) throws Exception {
    return server.auth.forDomain("example.org").people.answersOf(idOf(email));
  }

  /** load the page, then post it -- a form has to exist before it can be submitted */
  private Browser.Page post(Browser client, String path, Map<String, String> fields) throws Exception {
    client.get(path);
    return client.submitTo(path.contains("?") ? path.substring(0, path.indexOf('?')) : path, fields);
  }

  private Browser.Page saveProfile(Browser client, Map<String, String> fields) throws Exception {
    return post(client, "/self", fields);
  }

  private Browser.Page answer(Browser client, Map<String, String> fields) throws Exception {
    return post(client, "/survey", fields);
  }

  private List<Question> questions() throws Exception {
    return server.auth.forDomain("example.org").people.publishedQuestions();
  }

  // ---- profiles --------------------------------------------------------------------------------

  @Test
  public void somebodyCanWriteTheirOwnProfile() throws Exception {
    Browser.Page page = member.get("/self");
    assertEquals(200, page.status());
    assertTrue(page.contains("display_name"));

    Browser.Page saved = saveProfile(member, Map.of("action", "profile",
        "display_name", "Jane", "headline", "builds things", "about", "# About me\n\nHello.",
        "location", "Austin", "links", "https://example.com"));
    assertEquals(303, saved.status());

    Browser.Page back = member.get("/self");
    assertTrue(back.contains("Jane"));
    assertTrue(back.contains("builds things"));
    assertTrue(back.contains("Austin"));
  }

  @Test
  public void aProfileIsWhatAnAdminReadsWhenApproving() throws Exception {
    Browser newcomer = new Browser(server.port, "example.org");
    newcomer.get("/register");
    newcomer.submit(Map.of("email", "newcomer@example.com"));
    newcomer.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));
    // unapproved, but they can still write a profile -- which is the point, since it is what the
    // approval decision is made on
    newcomer.get("/self");
    saveProfile(newcomer, Map.of("action", "profile", "display_name", "Newcomer",
        "headline", "found you through a friend", "about", "I make **furniture**.",
        "location", "Portland", "links", ""));

    long id = idOf("newcomer@example.com");
    Browser.Page review = admin.get("/admin/people/review/" + id);
    assertEquals(200, review.status());
    assertTrue(review.contains("Newcomer"));
    assertTrue(review.contains("found you through a friend"));
    assertTrue("the about is markdown, rendered", review.contains("<strong>furniture</strong>"));
    assertTrue(review.contains("Portland"));
  }

  @Test
  public void theListSaysWhoHasWrittenAProfile() throws Exception {
    saveProfile(member, Map.of("action", "profile", "display_name", "Jane",
        "headline", "", "about", "", "location", "", "links", ""));
    assertTrue("the listing says who has written one",
        admin.get("/admin/people/list").contains("written"));
  }

  @Test
  public void anEmptyProfileSaysSoRatherThanShowingNothing() throws Exception {
    Browser.Page review = admin.get("/admin/people/review/" + idOf("member@example.com"));
    assertTrue(review.contains("Nothing yet"));
  }

  @Test
  public void signedOutPeopleAreSentToSignIn() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/self");
    assertEquals(303, page.status());
    assertEquals("carrying where they were going, so signing in finishes the journey",
        "/login?next=%2Fself", page.location());
  }

  // ---- asking and answering -----------------------------------------------------------------------

  @Test
  public void anAdminAsksAndEverybodySeesIt() throws Exception {
    ask("What brings you here?", "free", "");
    Browser.Page page = member.get("/survey");
    assertEquals(200, page.status());
    assertTrue(page.contains("What brings you here?"));
    assertTrue(page.contains("<textarea"));
  }

  @Test
  public void everyKindRenders() throws Exception {
    ask("Free text?", "free", "");
    ask("Pick one", "choice", "Alpha\nBeta");
    ask("How keen?", "rating", "");
    Browser.Page page = member.get("/survey");
    assertTrue("free text is a textarea", page.contains("<textarea"));
    assertTrue("a choice is a dropdown", page.contains("<option value=\"Alpha\""));
    assertTrue("a rating is a row of radios", page.contains("type=\"radio\""));
  }

  @Test
  public void answeringIsStoredAndCounted() throws Exception {
    ask("What brings you here?", "free", "");
    long questionId = questions().get(0).id();

    Browser.Page saved = answer(member, Map.of("action", "answers",
        "q" + questionId, "a friend told me"));
    assertEquals(303, saved.status());
    settle();

    AnswerSheet sheet = sheetOf("member@example.com");
    assertEquals("a friend told me", sheet.answerTo(questionId));
    assertEquals(1, sheet.answered());
    assertEquals(0, sheet.remaining());
  }

  @Test
  public void aRatingOutsideTheScaleIsNotStored() throws Exception {
    ask("How keen?", "rating", "");
    long questionId = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + questionId, "99"));
    settle();
    assertFalse("a client that made up a value is not a valid answer",
        sheetOf("member@example.com").hasAnswered(questionId));
  }

  @Test
  public void anOptionNobodyOfferedIsNotStored() throws Exception {
    ask("Pick one", "choice", "Alpha\nBeta");
    long questionId = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + questionId, "Gamma"));
    settle();
    assertFalse(sheetOf("member@example.com").hasAnswered(questionId));
  }

  @Test
  public void clearingAnAnswerPutsTheQuestionBack() throws Exception {
    ask("What brings you here?", "free", "");
    long questionId = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + questionId, "an answer"));
    settle();
    assertEquals(0, sheetOf("member@example.com").remaining());

    answer(member, Map.of("action", "answers", "q" + questionId, ""));
    settle();
    assertEquals(1, sheetOf("member@example.com").remaining());
  }

  // ---- the bubble ---------------------------------------------------------------------------------

  @Test
  public void theBubbleAppearsWhenThereIsSomethingToAnswer() throws Exception {
    assertFalse("nothing asked yet", member.get("/").contains("class=\"bubble\""));
    ask("What brings you here?", "free", "");
    server.auth.forDomain("example.org").survey.forget(idOf("member@example.com"));
    Browser.Page page = member.get("/");
    assertTrue("the nav should say there is something to do", page.contains("class=\"bubble\">1<"));
  }

  @Test
  public void theBubbleClearsOnceEverythingIsAnswered() throws Exception {
    ask("What brings you here?", "free", "");
    long questionId = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + questionId, "an answer"));
    settle();
    assertFalse(member.get("/").contains("class=\"bubble\""));
  }

  // ---- the async indexer ---------------------------------------------------------------------------

  @Test
  public void addingAQuestionRecountsEverybody() throws Exception {
    ask("First question", "free", "");
    long first = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + first, "done"));
    settle();
    assertEquals(0, sheetOf("member@example.com").remaining());

    // an admin asking one more makes everybody behind by one, without anybody touching their page
    ask("Second question", "free", "");
    assertEquals("the recount has to reach people who did nothing",
        1, sheetOf("member@example.com").remaining());
    assertEquals(1, sheetOf("member@example.com").answered());
  }

  @Test
  public void unpublishingAQuestionRemovesItFromEverybodysCount() throws Exception {
    ask("A question", "free", "");
    settle();
    // Submitting the form with nothing filled in writes nothing, so there is no answer sheet at
    // all -- which is exactly the person the bubble exists for, and why the count is computed
    // rather than read on a cache miss.
    answer(member, Map.of("action", "answers"));
    settle();
    assertEquals(1, bubbleFor("member@example.com"));

    long id = questions().get(0).id();
    admin.get("/admin/survey/edit/" + id);
    admin.submitTo("/admin/survey", Map.of("action", "save", "id", Long.toString(id),
        "prompt", "A question", "kind", "free", "options", "", "position", "0", "min", "1", "max", "5"));
    settle();
    assertEquals("a draft is not something somebody is behind on",
        0, bubbleFor("member@example.com"));
  }

  @Test
  public void deletingAQuestionRecountsWithoutLosingTheAnswer() throws Exception {
    ask("Going away", "free", "");
    long id = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + id, "kept"));
    settle();

    admin.submitTo("/admin/survey", Map.of("action", "delete", "id", Long.toString(id)));
    settle();

    AnswerSheet sheet = sheetOf("member@example.com");
    assertEquals(0, sheet.remaining());
    assertEquals(0, sheet.answered());
    assertEquals("the answer stays in the blob, uncounted", "kept", sheet.answerTo(id));
  }

  @Test
  public void everyMutationIsAnnouncedOnTheBus() throws Exception {
    ask("A question", "free", "");
    saveProfile(member, Map.of("action", "profile", "display_name", "Jane",
        "headline", "", "about", "", "location", "", "links", ""));
    assertTrue(server.events.recent(50).stream().anyMatch(event -> event.touches(Schema.QUESTIONS)));
    assertTrue(server.events.recent(50).stream().anyMatch(event -> event.touches(Schema.PROFILES)));
    long id = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + id, "yes"));
    assertTrue(server.events.recent(50).stream().anyMatch(event -> event.touches(Schema.ANSWERS)));
  }

  @Test
  public void sweepsCoalesceWhenTheyArriveFasterThanTheyRun() throws Exception {
    // the case this protects against is building a survey: a burst of question changes must not
    // cost a full re-index each. Requested in a burst, not one HTTP round trip at a time, because
    // sequential requests genuinely do drain between them.
    var indexer = server.auth.forDomain("example.org").survey;
    long before = indexer.sweepCount();
    for (int k = 0; k < 50; k++) {
      indexer.requestSweep();
    }
    settle();
    long sweeps = indexer.sweepCount() - before;
    assertTrue("50 requests should not be 50 sweeps, got " + sweeps, sweeps < 50);
  }

  @Test
  public void everybodyIsBehindByAsManyQuestionsAsWereAsked() throws Exception {
    for (int k = 0; k < 6; k++) {
      ask("Q" + k, "free", "");
    }
    settle();
    assertEquals(6, sheetOf("member@example.com").countedAgainst(questions()).remaining());
  }

  // ---- the admin questions page ---------------------------------------------------------------------

  @Test
  public void theQuestionsPageListsAndEdits() throws Exception {
    ask("Original wording", "free", "");
    Browser.Page list = admin.get("/admin/survey");
    assertTrue(list.contains("Original wording"));
    assertFalse("a listing is not a form", list.contains("name=\"prompt\""));

    long id = questions().get(0).id();
    Browser.Page editing = admin.get("/admin/survey/edit/" + id);
    assertTrue(editing.contains("Original wording"));
    assertTrue("the question gets room to be written in", editing.contains("<textarea id=\"q-prompt\""));

    admin.submitTo("/admin/survey", Map.of("action", "save", "id", Long.toString(id),
        "prompt", "Better wording", "kind", "free", "options", "", "position", "0",
        "min", "1", "max", "5", "published", "on"));
    settle();
    assertEquals("Better wording", questions().get(0).prompt());
  }

  @Test
  public void aDropdownNeedsOptions() throws Exception {
    admin.get("/admin/survey/new");
    Browser.Page bad = admin.submitToAndFollow("/admin/survey", Map.of("action", "save",
        "prompt", "Pick one", "kind", "choice", "options", "", "position", "0",
        "min", "1", "max", "5", "published", "on"));
    assertTrue(bad.contains("at least one option"));
  }

  @Test
  public void aQuestionNeedsAPrompt() throws Exception {
    admin.get("/admin/survey/new");
    Browser.Page bad = admin.submitToAndFollow("/admin/survey", Map.of("action", "save",
        "prompt", "", "kind", "free", "options", "", "position", "0", "min", "1", "max", "5"));
    assertTrue(bad.contains("something to ask"));
  }

  @Test
  public void theListingSaysHowManyPeopleAnsweredEachQuestion() throws Exception {
    // the count comes from the indexer's sweep rather than a query per row: the survey page is what
    // an admin stares at while writing questions, and a query per question per load is the kind of
    // thing that is fine at ten and awful at forty
    ask("Answered by everybody", "free", "");
    ask("Answered by nobody", "free", "");
    long first = questions().get(0).id();

    answer(member, Map.of("action", "answers", "q" + first, "here is mine"));
    settle();

    Browser other = signIn("second@example.com");
    answer(other, Map.of("action", "answers", "q" + first, "and mine"));
    settle();

    Browser.Page list = admin.get("/admin/survey/list");
    assertTrue(list.contains("Answered by everybody"));
    assertTrue("two people answered the first one", list.contains("\"num\">2</td>"));
    assertTrue("and nobody answered the second", list.contains("\"num\">0</td>"));
  }

  @Test
  public void clearingAnAnswerTakesItBackOutOfTheCount() throws Exception {
    // the tally moves by a delta rather than a full re-read, so this is the case that would rot:
    // adding is obvious, removing is where a naive increment-only counter silently drifts upward
    ask("Say something", "free", "");
    long id = questions().get(0).id();

    answer(member, Map.of("action", "answers", "q" + id, "something"));
    settle();
    assertTrue(admin.get("/admin/survey/list").contains("\"num\">1</td>"));

    answer(member, Map.of("action", "answers", "q" + id, ""));
    settle();
    assertTrue("an answer taken back is an answer not counted",
        admin.get("/admin/survey/list").contains("\"num\">0</td>"));

    // and re-answering does not double count
    answer(member, Map.of("action", "answers", "q" + id, "again"));
    settle();
    answer(member, Map.of("action", "answers", "q" + id, "reworded"));
    settle();
    assertTrue("re-wording moves nothing", admin.get("/admin/survey/list").contains("\"num\">1</td>"));
  }

  @Test
  public void theSurveyListingFiltersByTextAndByKind() throws Exception {
    ask("What brings you here?", "free", "");
    ask("Pick a colour", "choice", "red\nblue");

    Browser.Page searched = admin.get("/admin/survey/list?q=colour");
    assertTrue(searched.contains("Pick a colour"));
    assertFalse(searched.contains("What brings you here?"));

    Browser.Page dropdowns = admin.get("/admin/survey/list?kind=choice");
    assertTrue(dropdowns.contains("Pick a colour"));
    assertFalse(dropdowns.contains("What brings you here?"));
  }

  @Test
  public void everyKindSaysWhatItActuallyDoes() throws Exception {
    // the primary readers of this page are an admin and an AI, and "rating" alone says less than
    // "a number between the bounds you set"
    Browser.Page form = admin.get("/admin/survey/new");
    assertTrue(form.contains("Free text"));
    assertTrue(form.contains("Dropdown"));
    assertTrue(form.contains("Rating"));
    assertTrue(form.contains("A number between the bounds you set"));
    assertTrue("and the editor shows only the settings the chosen kind has",
        form.contains("data-for=\"choice\"") && form.contains("data-for=\"rating\""));
  }

  @Test
  public void ordinaryMembersCannotAskQuestions() throws Exception {
    Browser newcomer = new Browser(server.port, "example.org");
    newcomer.get("/register");
    newcomer.submit(Map.of("email", "nobody@example.com"));
    newcomer.submit(Map.of("code", server.mail().lastCodeFor("nobody@example.com")));
    assertEquals(404, newcomer.get("/admin/survey").status());
  }

  @Test
  public void answersShowUpInTheApprovalReview() throws Exception {
    ask("What brings you here?", "free", "");
    long id = questions().get(0).id();
    answer(member, Map.of("action", "answers", "q" + id, "a friend told me"));
    settle();

    Browser.Page review = admin.get("/admin/people/review/" + idOf("member@example.com"));
    assertTrue(review.contains("What brings you here?"));
    assertTrue("this is the social check the whole feature exists for",
        review.contains("a friend told me"));
  }

  @Test
  public void selfPagePostsNeedACsrfToken() throws Exception {
    member.get("/self");
    Browser.Page forged = member.submitRaw("/self", Map.of("action", "profile", "display_name", "X"));
    assertEquals(400, forged.status());
  }

  @Test
  public void theIconsAreInlineSvgWithNoImageRequests() throws Exception {
    Browser.Page page = admin.get("/admin");
    assertTrue("icons are drawn inline", page.contains("<svg class=\"icon\""));
    assertTrue("and inherit the text colour", page.contains("stroke=\"currentColor\""));
    assertFalse("no image requests at all", page.contains("<img"));
    assertNotNull(io.hearth.web.Icons.of("home"));
    assertEquals("", io.hearth.web.Icons.of("nosuchicon"));
  }
}
