package io.hearth.mcp;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The last thousand things a model did here.
 *
 * The access log answers "what was requested" and the event bus answers "what changed". Neither
 * answers the question somebody actually asks after handing an assistant the keys to their website,
 * which is *what did it do, in words, and was that reasonable*. A row in the access log saying
 * `POST /mcp 200` is useless for that; the interesting part is entirely in the body.
 *
 * So each action keeps its arguments and its result as JSON, and renders them indented for reading.
 * Storing the raw JSON rather than a formatted string is deliberate: the pretty form is a view, and
 * a log that threw away the structure could never be filtered, diffed, or exported later.
 *
 * In memory, like the access log and the event bus. A restart loses it, which is the right trade for
 * something you look at rather than keep -- and every action that changed a row also emitted a
 * mutation event, so the durable record of *what changed* is elsewhere and survives.
 */
public class AiLog {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectWriter PRETTY = pretty();
  /** arguments and results are model output; a runaway one must not eat the heap */
  private static final int MAX_JSON = 8_000;

  private final int capacity;
  private final Action[] ring;
  private final AtomicLong sequence = new AtomicLong();
  private int next;
  private int size;

  public AiLog() {
    this(1000);
  }

  public AiLog(int capacity) {
    this.capacity = Math.max(1, capacity);
    this.ring = new Action[this.capacity];
  }

  /** how an action ended, which is the first thing anybody scanning the log looks for */
  public enum Outcome {
    /** it did what it asked to do */
    ok,
    /** the server said no: a rule, not a bug */
    refused,
    /** something broke */
    failed
  }

  /**
   * One thing an agent did.
   *
   * `subject` is the thing acted on -- a uri, a template name, a question -- kept separate from the
   * arguments so the log can be scanned down a column instead of read as prose.
   */
  public record Action(long seq, long atMillis, String domain, String agent, long userId,
                       String email, String tool, String subject, Outcome outcome, String detail,
                       String argumentsJson, String resultJson, long millis) {

    public String prettyArguments() {
      return prettify(argumentsJson);
    }

    public String prettyResult() {
      return prettify(resultJson);
    }

    public boolean changedSomething() {
      return outcome == Outcome.ok && tool != null && WRITES.stream().anyMatch(tool::startsWith);
    }
  }

  /** the tool name prefixes that mean a write happened, for the "what did it change" filter */
  private static final List<String> WRITES =
      List.of("content_save", "content_delete", "template_save", "template_delete",
          "survey_ask", "survey_update", "survey_delete");

  /** record an action; returns what was recorded so a caller can log it verbosely too */
  public synchronized Action record(String domain, String agent, long userId, String email,
                                    String tool, String subject, Outcome outcome, String detail,
                                    Object arguments, Object result, long millis) {
    Action action = new Action(sequence.incrementAndGet(), System.currentTimeMillis(),
        domain, agent, userId, email, tool, subject, outcome, detail,
        asJson(arguments), asJson(result), millis);
    ring[next] = action;
    next = (next + 1) % capacity;
    if (size < capacity) {
      size++;
    }
    return action;
  }

  /** newest last, the way a stream reads */
  public synchronized List<Action> recent(int limit) {
    ArrayList<Action> actions = new ArrayList<>(Math.min(limit, size));
    int start = (next - size + capacity) % capacity;
    for (int k = 0; k < size; k++) {
      actions.add(ring[(start + k) % capacity]);
    }
    if (actions.size() > limit) {
      return actions.subList(actions.size() - limit, actions.size());
    }
    return actions;
  }

  /** what the admin page filters on: text anywhere, outcome, and writes-only */
  public synchronized List<Action> search(String text, Outcome outcome, boolean writesOnly, int limit) {
    String needle = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    ArrayList<Action> found = new ArrayList<>();
    for (Action action : recent(capacity)) {
      if (outcome != null && action.outcome() != outcome) {
        continue;
      }
      if (writesOnly && !action.changedSomething()) {
        continue;
      }
      if (!needle.isEmpty() && !matches(action, needle)) {
        continue;
      }
      found.add(action);
    }
    if (found.size() > limit) {
      return found.subList(found.size() - limit, found.size());
    }
    return found;
  }

  private static boolean matches(Action action, String needle) {
    return contains(action.tool(), needle) || contains(action.subject(), needle)
        || contains(action.agent(), needle) || contains(action.email(), needle)
        || contains(action.detail(), needle) || contains(action.argumentsJson(), needle);
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  public long recorded() {
    return sequence.get();
  }

  public int capacity() {
    return capacity;
  }

  public synchronized int size() {
    return size;
  }

  /** how many of the recent actions changed something, for the dashboard */
  public synchronized long writeCount() {
    return recent(capacity).stream().filter(Action::changedSomething).count();
  }

  private static String asJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      String json = value instanceof JsonNode ? value.toString() : JSON.writeValueAsString(value);
      return json.length() <= MAX_JSON ? json : json.substring(0, MAX_JSON) + "…";
    } catch (Exception ex) {
      return null;
    }
  }

  /** indent for reading; anything that will not parse is shown as it arrived rather than dropped */
  static String prettify(String json) {
    if (json == null || json.isBlank()) {
      return "";
    }
    try {
      return PRETTY.writeValueAsString(JSON.readTree(json));
    } catch (Exception ex) {
      return json;
    }
  }

  private static ObjectWriter pretty() {
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    // the default indents objects but leaves arrays on one line, which reads badly for a list of
    // uris -- which is most of what this log contains
    printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    return JSON.writer(printer);
  }
}
