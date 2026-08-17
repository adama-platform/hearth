package io.hearth.live;

/**
 * One thing that happened, said in as few bytes as possible.
 *
 * <b>A signal carries no content, ever.</b> It says "post 4 moved" and nothing about what was said
 * in it. Everything downstream of that is a normal, authorised fetch by a client that already has a
 * session -- so the live channel never becomes a second, weaker path to the same data, and a bug in
 * the fan-out leaks a row id at worst.
 *
 * It also makes the stream cheap enough to be boring: a busy evening in a five-hundred-person
 * community is a few hundred bytes a minute per connection, and a client that misses some catches
 * up with one request rather than a replay.
 *
 * There is deliberately no addressing. Every signal names a row every member could already fetch,
 * so "who may hear this" is a question with one answer -- and a channel with no audience list is a
 * channel with nowhere for a mistake about audiences to live. The private thing this once carried
 * was direct messages, and they are gone.
 */
public record Signal(long seq, Kind kind, String scope, String meta) {
  public enum Kind {
    /** a row moved: re-fetch whatever is on screen that depends on it */
    updated,
    /** somebody arrived or went quiet; meta is the user id */
    presence,
    /** the connection is alive and has nothing to say */
    ping
  }

  /** the wire form: one JSON object, no whitespace, no content */
  public String json() {
    StringBuilder out = new StringBuilder(96);
    out.append("{\"seq\":").append(seq).append(",\"kind\":\"").append(kind.name()).append('"');
    if (scope != null) {
      out.append(",\"scope\":\"").append(escape(scope)).append('"');
    }
    if (meta != null) {
      out.append(",\"meta\":\"").append(escape(meta)).append('"');
    }
    return out.append('}').toString();
  }

  /**
   * Escaping for a JSON string that is written by hand.
   *
   * Every value that reaches here is a table name, a row id or a user id -- all of them built by
   * this server rather than typed by anybody. This exists so that stays true by construction rather
   * than by everyone remembering it.
   */
  public static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      switch (ch) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (ch < 0x20) {
            out.append(String.format("\\u%04x", (int) ch));
          } else {
            out.append(ch);
          }
        }
      }
    }
    return out.toString();
  }
}
