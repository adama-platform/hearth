package io.hearth.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One person's answers, as the blob they are stored as plus the counts the bubble reads.
 *
 * Keyed by question id, so a question being reworded does not orphan an answer, and a question
 * being deleted leaves an answer that is simply no longer counted rather than a dangling row.
 */
public record AnswerSheet(long userId, Map<String, String> answers, int answered, int remaining) {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static AnswerSheet empty(long userId) {
    return new AnswerSheet(userId, Map.of(), 0, 0);
  }

  public static AnswerSheet parse(long userId, String blob, int answered, int remaining) {
    LinkedHashMap<String, String> answers = new LinkedHashMap<>();
    try {
      JsonNode node = JSON.readTree(blob == null || blob.isBlank() ? "{}" : blob);
      node.fields().forEachRemaining(entry -> {
        if (entry.getValue().isTextual()) {
          answers.put(entry.getKey(), entry.getValue().textValue());
        }
      });
    } catch (Exception ex) {
      // an unreadable blob reads as "answered nothing", which is recoverable; throwing would make
      // one bad row break somebody's whole profile page
      answers.clear();
    }
    return new AnswerSheet(userId, answers, answered, remaining);
  }

  public String answerTo(long questionId) {
    return answers.get(Long.toString(questionId));
  }

  public boolean hasAnswered(long questionId) {
    String answer = answerTo(questionId);
    return answer != null && !answer.isBlank();
  }

  /** the blob to store */
  public String toBlob() {
    ObjectNode node = JSON.createObjectNode();
    answers.forEach(node::put);
    return node.toString();
  }

  /**
   * Count this sheet against the current question set.
   *
   * The counts are what the notification bubble shows, and they only mean anything relative to the
   * questions that exist right now -- which is why they are recomputed rather than incremented.
   * An answer to a question that has since been unpublished stays in the blob and stops counting.
   */
  public AnswerSheet countedAgainst(List<Question> questions) {
    int done = 0;
    int left = 0;
    for (Question question : questions) {
      if (!question.published()) {
        continue;
      }
      if (question.accepts(answerTo(question.id()))) {
        done++;
      } else {
        left++;
      }
    }
    return new AnswerSheet(userId, answers, done, left);
  }

  public AnswerSheet with(long questionId, String answer) {
    LinkedHashMap<String, String> updated = new LinkedHashMap<>(answers);
    if (answer == null || answer.isBlank()) {
      updated.remove(Long.toString(questionId));
    } else {
      updated.put(Long.toString(questionId), answer.length() > 4000 ? answer.substring(0, 4000) : answer);
    }
    return new AnswerSheet(userId, updated, answered, remaining);
  }

  public boolean anythingLeft() {
    return remaining > 0;
  }
}
