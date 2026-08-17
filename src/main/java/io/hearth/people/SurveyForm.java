package io.hearth.people;

import io.hearth.auth.Accounts;
import io.hearth.web.Forms;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The survey as a form: what the boxes look like, and what a submission means.
 *
 * There are three screens that ask somebody the community's questions -- the survey itself,
 * orientation on the way in, and whatever asks next -- and the rule that matters is not on any of
 * them. It is <b>the merge</b>: a submission mentions the handful of questions that were on screen,
 * so treating it as the new state of the sheet erases last month's answers every time somebody
 * answers a new one, and does it while looking like it worked.
 *
 * That rule is written once, here, because the second copy is the one that gets it wrong.
 */
public final class SurveyForm {
  private SurveyForm() {
  }

  /**
   * How many questions to put in front of somebody at once.
   *
   * <b>Three.</b> A survey is a wall of boxes and a wall of boxes is a thing people close; three is
   * a screen somebody finishes, and finishing one is what makes the next one get answered. The
   * community's whole list can be forty questions long as long as nobody is ever shown forty.
   */
  public static final int CHUNK = 3;

  /** the next few, and no more */
  public static List<Question> chunk(List<Question> outstanding) {
    return outstanding.size() <= CHUNK ? outstanding : outstanding.subList(0, CHUNK);
  }

  /** the questions this person still owes an answer to, in the order they are asked */
  public static List<Question> outstanding(List<Question> questions, AnswerSheet answers) {
    ArrayList<Question> out = new ArrayList<>();
    for (Question question : questions) {
      if (!question.accepts(answers.answerTo(question.id()))) {
        out.add(question);
      }
    }
    return out;
  }

  /** flatten questions and the person's answers into what a template renders */
  public static List<Map<String, Object>> rows(List<Question> questions, AnswerSheet answers) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Question question : questions) {
      String answer = answers.answerTo(question.id());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", question.id());
      row.put("field", "q" + question.id());
      row.put("prompt", question.prompt());
      row.put("help", question.help());
      row.put("anyHelp", question.help() != null && !question.help().isBlank());
      row.put("required", question.required());
      row.put("answered", question.accepts(answer));
      row.put("answer", answer == null ? "" : answer);
      row.put("free", question.kind() == Question.Kind.free);
      row.put("choice", question.kind() == Question.Kind.choice);
      row.put("rating", question.kind() == Question.Kind.rating);
      ArrayList<Map<String, Object>> options = new ArrayList<>();
      for (String option : question.options()) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("value", option);
        item.put("selected", option.equals(answer));
        options.add(item);
      }
      row.put("options", options);
      ArrayList<Map<String, Object>> scale = new ArrayList<>();
      for (int value : question.scale()) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("selected", Integer.toString(value).equals(answer));
        scale.add(item);
      }
      row.put("scale", scale);
      rows.add(row);
    }
    return rows;
  }

  /**
   * Lay a submission over what is already stored, and say how much changed.
   *
   * <b>Presence, not value.</b> A field that was submitted empty means "clear this", and a field
   * that was not submitted at all means "leave it alone" -- and `Forms.get` returns null for both,
   * which is why this asks the map whether the key is there rather than what it holds.
   *
   * `allowErase` is the one difference between the two callers. On the survey, clearing an answer is
   * a legitimate thing to do and puts the question back on the list. During orientation nothing is
   * on screen that was ever answered, so an empty box is somebody skipping a question rather than
   * withdrawing an answer, and treating it as an erasure would let the welcome flow delete what
   * somebody wrote before it.
   */
  public static int merge(Accounts accounts, long userId, Forms form, boolean allowErase)
      throws SQLException {
    LinkedHashMap<Long, String> changes = new LinkedHashMap<>();
    for (Question question : accounts.people.publishedQuestions()) {
      String field = "q" + question.id();
      if (!form.all().containsKey(field)) {
        continue;
      }
      String answer = form.all().get(field);
      if (question.accepts(answer)) {
        changes.put(question.id(), answer.trim());
      } else if (allowErase && (answer == null || answer.isBlank())) {
        changes.put(question.id(), null);
      }
    }
    accounts.people.mergeAnswers(userId, changes);
    // the write emitted an event and the indexer will recount; drop the cached number so the very
    // next page is right rather than one refresh behind
    accounts.survey.forget(userId);
    return changes.size();
  }
}
