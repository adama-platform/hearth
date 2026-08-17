package io.hearth.content;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff, and the patch that replays it.
 *
 * This is the floor the whole version history stands on: if {@link #apply} ever disagrees with
 * {@link #diff}, somebody's page silently becomes a different page, and they find out weeks later
 * when they look at an old version. So the design goal is not compactness or cleverness -- it is
 * that the round trip is *obviously* correct and exhaustively tested.
 *
 * Hence line-based rather than character-based, and an explicit longest-common-subsequence rather
 * than a heuristic. Character diffs are smaller and Myers' algorithm is faster, but neither is
 * something you can convince yourself of by reading it, and both fail in ways that are hard to see
 * in a test.
 *
 * The format is deliberately readable, because it is stored in a database that somebody will
 * eventually query by hand:
 *
 * <pre>
 *   =12        copy the next 12 lines unchanged
 *   -3         drop the next 3 lines
 *   +2         insert the 2 lines that follow
 *   the first inserted line
 *   the second inserted line
 * </pre>
 *
 * Two properties hold for every input, and both are tested against thousands of generated cases:
 * `apply(before, diff(before, after))` equals `after`, and a patch never depends on anything but the
 * text it was made from.
 */
public final class TextPatch {
  /**
   * Beyond this many lines on either side the quadratic table is not worth it.
   *
   * The caller's answer to a refusal is to store a snapshot instead, which is always correct and
   * merely larger -- so this bound trades disk for a guarantee that no edit can make a save slow.
   */
  public static final int MAX_LINES = 5000;

  private TextPatch() {
  }

  /** raised when a patch does not fit the text it is applied to; always a bug, never user input */
  public static class PatchException extends RuntimeException {
    public PatchException(String message) {
      super(message);
    }
  }

  /** would diffing these two be bounded work? */
  public static boolean canDiff(String before, String after) {
    return countLines(before) <= MAX_LINES && countLines(after) <= MAX_LINES;
  }

  /**
   * The patch that turns `before` into `after`.
   *
   * Never returns null. An empty patch is the empty string, which applies cleanly to anything it
   * came from.
   */
  public static String diff(String before, String after) {
    List<String> from = lines(before);
    List<String> to = lines(after);
    if (from.size() > MAX_LINES || to.size() > MAX_LINES) {
      throw new PatchException("too many lines to diff; store a snapshot instead");
    }

    // Pair each kept line with the exact line it survives as. A matrix of "is this pair part of
    // some common subsequence" cannot answer "is line a kept, and as which line" -- and reading it
    // as though it could made every edit delete and re-insert the whole tail of the document. The
    // patches were correct and useless, which is the kind of wrong a round-trip test cannot see.
    int[] matchOf = pairings(from, to);

    StringBuilder patch = new StringBuilder();
    int a = 0;
    int b = 0;
    int copying = 0;
    for (int pa = 0; pa < from.size(); pa++) {
      if (matchOf[pa] < 0) {
        continue;
      }
      int pb = matchOf[pa];
      // everything before this pair on either side is a deletion or an insertion
      if (pa > a || pb > b) {
        copying = flushCopy(patch, copying);
        if (pa > a) {
          patch.append('-').append(pa - a).append('\n');
          a = pa;
        }
        if (pb > b) {
          patch.append('+').append(pb - b).append('\n');
          for (int k = b; k < pb; k++) {
            patch.append(to.get(k)).append('\n');
          }
          b = pb;
        }
      }
      copying++;
      a++;
      b++;
    }
    copying = flushCopy(patch, copying);
    if (a < from.size()) {
      patch.append('-').append(from.size() - a).append('\n');
    }
    if (b < to.size()) {
      patch.append('+').append(to.size() - b).append('\n');
      for (int k = b; k < to.size(); k++) {
        patch.append(to.get(k)).append('\n');
      }
    }
    return patch.toString();
  }

  /**
   * The same comparison, for a person rather than for storage.
   *
   * {@link #diff} throws away what it deleted -- it only records *how many* lines went, because the
   * text is already in the document being patched and storing it twice would double every history
   * entry. A diff on a screen needs the opposite: the removed lines are the whole point of looking.
   *
   * So this walks the identical pairing and keeps both sides. Sharing {@link #pairings} rather than
   * comparing again is what stops the screen and the storage ever telling different stories about
   * the same edit -- two implementations of "what changed" would eventually disagree, and the one
   * somebody is looking at would be the one they believed.
   */
  public static List<Change> changes(String before, String after) {
    List<String> from = lines(before);
    List<String> to = lines(after);
    if (from.size() > MAX_LINES || to.size() > MAX_LINES) {
      throw new PatchException("too many lines to compare");
    }
    int[] matchOf = pairings(from, to);

    ArrayList<Change> changes = new ArrayList<>();
    int a = 0;
    int b = 0;
    for (int pa = 0; pa < from.size(); pa++) {
      if (matchOf[pa] < 0) {
        continue;
      }
      int pb = matchOf[pa];
      while (a < pa) {
        changes.add(new Change(Kind.removed, from.get(a), a + 1, 0));
        a++;
      }
      while (b < pb) {
        changes.add(new Change(Kind.added, to.get(b), 0, b + 1));
        b++;
      }
      changes.add(new Change(Kind.same, from.get(a), a + 1, b + 1));
      a++;
      b++;
    }
    while (a < from.size()) {
      changes.add(new Change(Kind.removed, from.get(a), a + 1, 0));
      a++;
    }
    while (b < to.size()) {
      changes.add(new Change(Kind.added, to.get(b), 0, b + 1));
      b++;
    }
    return changes;
  }

  /** what happened to one line */
  public enum Kind {
    same, added, removed
  }

  /**
   * One line of a comparison.
   *
   * The line numbers are the ones on each side, and zero means "this line is not on that side" --
   * which is what makes a diff readable at a glance rather than a wall of text with markers.
   */
  public record Change(Kind kind, String text, int beforeLine, int afterLine) {
  }

  /**
   * Replay a patch.
   *
   * Every mismatch is an exception rather than a best effort. A patch that half-applies produces a
   * page that never existed, which is worse than an error somebody can see.
   */
  public static String apply(String before, String patch) {
    List<String> from = lines(before);
    ArrayList<String> out = new ArrayList<>();
    int at = 0;

    String[] instructions = patch.isEmpty() ? new String[0] : patch.split("\n", -1);
    int k = 0;
    while (k < instructions.length) {
      String instruction = instructions[k++];
      if (instruction.isEmpty()) {
        // the trailing newline of the last operation
        continue;
      }
      char op = instruction.charAt(0);
      int count = number(instruction);
      switch (op) {
        case '=' -> {
          if (at + count > from.size()) {
            throw new PatchException("patch copies past the end of the text");
          }
          for (int n = 0; n < count; n++) {
            out.add(from.get(at++));
          }
        }
        case '-' -> {
          if (at + count > from.size()) {
            throw new PatchException("patch deletes past the end of the text");
          }
          at += count;
        }
        case '+' -> {
          if (k + count > instructions.length) {
            throw new PatchException("patch inserts more lines than it carries");
          }
          for (int n = 0; n < count; n++) {
            out.add(instructions[k++]);
          }
        }
        default -> throw new PatchException("unknown patch operation '" + op + "'");
      }
    }
    if (at != from.size()) {
      throw new PatchException("patch did not consume the whole text: "
          + at + " of " + from.size() + " lines");
    }
    return join(out);
  }

  /** how big the patch is, so a caller can decide whether a snapshot is cheaper */
  public static int size(String patch) {
    return patch == null ? 0 : patch.length();
  }

  // ---- the diff itself ---------------------------------------------------------------------------

  /** run out a pending copy instruction; returns the reset counter so callers read as one line */
  private static int flushCopy(StringBuilder patch, int copying) {
    if (copying > 0) {
      patch.append('=').append(copying).append('\n');
    }
    return 0;
  }

  /**
   * For each line of `from`, the line of `to` it survives as, or -1 when it does not.
   *
   * The textbook longest-common-subsequence dynamic program: a table of match lengths, then one
   * walk back through it recording pairs. O(n*m) in time and memory, which is why
   * {@link #MAX_LINES} exists. Chosen over Myers' algorithm because the point of this class is that
   * you can read it and believe it, and the bound makes the cost a non-question.
   *
   * The pairs are strictly increasing on both sides, which is what makes the emit loop above a
   * single forward pass with no lookahead.
   */
  private static int[] pairings(List<String> from, List<String> to) {
    int n = from.size();
    int m = to.size();
    int[][] length = new int[n + 1][m + 1];
    for (int a = n - 1; a >= 0; a--) {
      for (int b = m - 1; b >= 0; b--) {
        length[a][b] = from.get(a).equals(to.get(b))
            ? length[a + 1][b + 1] + 1
            : Math.max(length[a + 1][b], length[a][b + 1]);
      }
    }
    int[] matchOf = new int[n];
    java.util.Arrays.fill(matchOf, -1);
    int a = 0;
    int b = 0;
    while (a < n && b < m) {
      if (from.get(a).equals(to.get(b))) {
        matchOf[a] = b;
        a++;
        b++;
      } else if (length[a + 1][b] >= length[a][b + 1]) {
        a++;
      } else {
        b++;
      }
    }
    return matchOf;
  }

  // ---- lines -------------------------------------------------------------------------------------

  /**
   * Split into lines, preserving exactly what was there.
   *
   * A trailing newline matters: "a\n" and "a" are different documents, and a version history that
   * loses the difference will eventually lose a blank line at the end of somebody's page and they
   * will not be able to say why. Splitting with a negative limit keeps the empty final element,
   * and {@link #join} puts it back.
   */
  static List<String> lines(String text) {
    if (text == null || text.isEmpty()) {
      return new ArrayList<>();
    }
    return new ArrayList<>(List.of(text.split("\n", -1)));
  }

  static String join(List<String> lines) {
    return String.join("\n", lines);
  }

  private static int countLines(String text) {
    return text == null || text.isEmpty() ? 0 : text.split("\n", -1).length;
  }

  private static int number(String instruction) {
    try {
      int value = Integer.parseInt(instruction.substring(1));
      if (value < 0) {
        throw new PatchException("negative count in patch: " + instruction);
      }
      return value;
    } catch (NumberFormatException ex) {
      throw new PatchException("malformed patch operation '" + instruction + "'");
    }
  }
}
