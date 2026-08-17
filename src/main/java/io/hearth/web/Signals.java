package io.hearth.web;

/**
 * What the browser did while the form was open.
 *
 * The page counts mouse, keyboard, touch, pointer, scroll and focus events and posts the totals. A
 * submission with none of any kind did not come from somebody sitting at a computer, and that is the
 * only claim being made here -- these counts are trivially forgeable by anybody who reads this file,
 * so they are a filter for the cheap end of the traffic, not an authentication mechanism. Treating
 * them as more than that would be the mistake.
 *
 * The value is kept on the account row regardless. A wave of registrations that all scored exactly
 * the minimum, or all reported the same elapsed time, is a shape you can only see afterwards, and
 * only if you wrote it down.
 *
 * Wire format is deliberately small and boring: "m:14|k:31|t:0|p:9|s:2|f:3|e:8410".
 */
public record Signals(int mouse, int key, int touch, int pointer, int scroll, int focus, long elapsedMillis) {
  public static final Signals NONE = new Signals(0, 0, 0, 0, 0, 0, 0);
  /** a count above this is nonsense or an attempt to overflow something; clamp rather than reject */
  public static final int MAX_COUNT = 100_000;
  public static final long MAX_ELAPSED = 24 * 60 * 60 * 1000L;

  /** every event the page saw, of any kind */
  public int total() {
    return mouse + key + touch + pointer + scroll + focus;
  }

  /**
   * Did a human plausibly touch this? Zero of everything means no, which is the whole test.
   *
   * Deliberately not a threshold. Somebody arriving with a password manager, tabbing to submit, and
   * never moving the mouse is a real person with a low score, and locking them out to raise the bar
   * on a bot that will just emit fake numbers is a bad trade.
   */
  public boolean plausible() {
    return total() > 0;
  }

  @Override
  public String toString() {
    return "m:" + mouse + "|k:" + key + "|t:" + touch + "|p:" + pointer
        + "|s:" + scroll + "|f:" + focus + "|e:" + elapsedMillis;
  }

  /**
   * Parse what the page posted. Anything malformed becomes {@link #NONE} rather than an error --
   * a broken or absent value and a hostile one deserve the same answer, and it is the caller's job
   * to decide what to do about a zero score.
   */
  public static Signals parse(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > 160) {
      return NONE;
    }
    int mouse = 0;
    int key = 0;
    int touch = 0;
    int pointer = 0;
    int scroll = 0;
    int focus = 0;
    long elapsed = 0;
    for (String part : raw.split("\\|")) {
      int colon = part.indexOf(':');
      if (colon <= 0 || colon == part.length() - 1) {
        continue;
      }
      String key0 = part.substring(0, colon);
      long value;
      try {
        value = Long.parseLong(part.substring(colon + 1));
      } catch (NumberFormatException ex) {
        continue;
      }
      if (value < 0) {
        continue;
      }
      switch (key0) {
        case "m" -> mouse = clamp(value);
        case "k" -> key = clamp(value);
        case "t" -> touch = clamp(value);
        case "p" -> pointer = clamp(value);
        case "s" -> scroll = clamp(value);
        case "f" -> focus = clamp(value);
        case "e" -> elapsed = Math.min(value, MAX_ELAPSED);
        default -> {
          // an unknown key is a newer page or a hopeful bot; neither is worth failing over
        }
      }
    }
    return new Signals(mouse, key, touch, pointer, scroll, focus, elapsed);
  }

  private static int clamp(long value) {
    return (int) Math.min(value, MAX_COUNT);
  }

  /** a short human summary for the boot report and future audit pages */
  public String describe() {
    return total() + " events over " + (elapsedMillis / 1000) + "s"
        + " (mouse " + mouse + ", key " + key + ", touch " + touch + ")";
  }
}
