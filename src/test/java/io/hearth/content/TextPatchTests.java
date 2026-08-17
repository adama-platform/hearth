package io.hearth.content;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The patch algorithm, which the whole version history rests on.
 *
 * If `apply` ever disagrees with `diff`, an old version of somebody's page silently becomes a page
 * that never existed -- and they find out weeks later, if at all. So the bar here is not "the
 * examples work": it is that the round trip holds for every input anybody could produce, checked
 * against thousands of generated cases rather than the dozen a person thinks of.
 *
 * The hand-written cases below exist for a different reason: when the property test fails it fails
 * on a random blob, and these are the small readable cases that say *what* broke.
 */
public class TextPatchTests {

  /** the one property that matters, stated once and used everywhere */
  private static void roundTrips(String before, String after) {
    String patch = TextPatch.diff(before, after);
    String replayed = TextPatch.apply(before, patch);
    assertEquals("patch did not reproduce the text\n--- before ---\n" + before
        + "\n--- after ---\n" + after + "\n--- patch ---\n" + patch, after, replayed);
  }

  // ---- the shapes an edit actually takes -----------------------------------------------------------

  @Test
  public void nothingChanged() {
    roundTrips("one\ntwo\nthree", "one\ntwo\nthree");
    assertEquals("an unchanged document is one copy instruction", "=3\n",
        TextPatch.diff("one\ntwo\nthree", "one\ntwo\nthree"));
  }

  @Test
  public void aLineChangedInTheMiddle() {
    roundTrips("one\ntwo\nthree", "one\nTWO\nthree");
  }

  @Test
  public void linesAppended() {
    roundTrips("one\ntwo", "one\ntwo\nthree\nfour");
  }

  @Test
  public void linesPrepended() {
    roundTrips("three\nfour", "one\ntwo\nthree\nfour");
  }

  @Test
  public void linesRemovedFromTheMiddle() {
    roundTrips("one\ntwo\nthree\nfour", "one\nfour");
  }

  @Test
  public void everythingReplaced() {
    roundTrips("one\ntwo\nthree", "alpha\nbeta");
  }

  @Test
  public void fromNothingToSomething() {
    roundTrips("", "one\ntwo");
  }

  @Test
  public void fromSomethingToNothing() {
    roundTrips("one\ntwo", "");
  }

  @Test
  public void bothEmpty() {
    roundTrips("", "");
    assertEquals("", TextPatch.diff("", ""));
  }

  // ---- the cases that quietly lose data ------------------------------------------------------------

  @Test
  public void aTrailingNewlineIsPartOfTheDocument() {
    // "a\n" and "a" are different documents. A history that loses the difference loses a blank line
    // at the end of somebody's page and cannot say why.
    roundTrips("a", "a\n");
    roundTrips("a\n", "a");
    assertFalse("and the patch is not empty", TextPatch.diff("a", "a\n").isEmpty());
  }

  @Test
  public void blankLinesAreRealLines() {
    roundTrips("one\n\ntwo", "one\n\n\ntwo");
    roundTrips("\n\n\n", "\n\n");
  }

  @Test
  public void repeatedLinesAreNotConfusedForEachOther() {
    // the classic diff trap: identical lines everywhere, so a naive matcher pairs the wrong ones
    roundTrips("x\nx\nx\nx", "x\nx");
    roundTrips("x\nx", "x\nx\nx\nx");
    roundTrips("a\nx\na\nx\na", "a\nx\na");
  }

  @Test
  public void movedBlocksStillReproduce() {
    // a diff does not have to *notice* a move; it has to reproduce the result
    roundTrips("header\nbody\nfooter", "footer\nheader\nbody");
  }

  @Test
  public void aLineThatLooksLikeAPatchInstruction() {
    // content is arbitrary text, and some of it looks exactly like the format
    roundTrips("=5\n-3\n+2", "=5\n+2");
    roundTrips("normal", "=12\n-7\n+1\nsmuggled");
    roundTrips("+3\nfake\nfake\nfake", "nothing to see");
  }

  @Test
  public void unicodeAndTabsSurvive() {
    roundTrips("café\n\tindented\n→ arrow", "café\n\t\tindented\n→ arrow\n🔥");
  }

  @Test
  public void aVeryLongSingleLine() {
    roundTrips("x".repeat(50_000), "y".repeat(50_000));
  }

  // ---- the property, over generated documents ------------------------------------------------------

  @Test
  public void theRoundTripHoldsForThousandsOfRandomEdits() {
    // The real test. A person thinks of a dozen cases; an edit is whatever somebody typed.
    Random random = new Random(20260731L);
    for (int trial = 0; trial < 2000; trial++) {
      String before = document(random, random.nextInt(30));
      String after = edited(random, before);
      roundTrips(before, after);
    }
  }

  @Test
  public void theRoundTripHoldsForUnrelatedDocuments() {
    // the worst case for a common-subsequence diff: nothing in common at all
    Random random = new Random(11L);
    for (int trial = 0; trial < 500; trial++) {
      roundTrips(document(random, random.nextInt(40)), document(random, random.nextInt(40)));
    }
  }

  @Test
  public void theRoundTripHoldsForDocumentsOfMostlyRepeatedLines() {
    // a tiny alphabet means many equally-long common subsequences, which is where an off-by-one in
    // the walk-back shows up
    Random random = new Random(7L);
    for (int trial = 0; trial < 1000; trial++) {
      roundTrips(repetitive(random, random.nextInt(25)), repetitive(random, random.nextInt(25)));
    }
  }

  @Test
  public void aChainOfEditsReplaysInOrder() {
    // how the history actually reconstructs a version: a snapshot plus every patch since
    Random random = new Random(99L);
    String current = document(random, 20);
    List<String> patches = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    String walking = current;
    for (int k = 0; k < 50; k++) {
      String next = edited(random, walking);
      patches.add(TextPatch.diff(walking, next));
      expected.add(next);
      walking = next;
    }
    String replayed = current;
    for (int k = 0; k < patches.size(); k++) {
      replayed = TextPatch.apply(replayed, patches.get(k));
      assertEquals("step " + k + " of the chain", expected.get(k), replayed);
    }
  }

  // ---- refusals ------------------------------------------------------------------------------------

  @Test
  public void aPatchAppliedToTheWrongTextIsRefused() {
    // a half-applied patch produces a document that never existed, which is worse than an error
    String patch = TextPatch.diff("one\ntwo\nthree", "one\nTWO\nthree");
    try {
      TextPatch.apply("completely different", patch);
      fail("expected a refusal");
    } catch (TextPatch.PatchException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("past the end")
          || ex.getMessage().contains("did not consume"));
    }
  }

  @Test
  public void aTruncatedPatchIsRefused() {
    try {
      TextPatch.apply("a\nb\nc", "=1\n+2\nonly one line");
      fail("expected a refusal");
    } catch (TextPatch.PatchException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("more lines than it carries"));
    }
  }

  @Test
  public void aMalformedPatchIsRefused() {
    for (String bad : new String[]{"?5\n", "=abc\n", "=-1\n"}) {
      try {
        TextPatch.apply("a\nb", bad);
        fail("expected a refusal for '" + bad + "'");
      } catch (TextPatch.PatchException expected) {
        assertTrue(expected.getMessage(), !expected.getMessage().isEmpty());
      }
    }
  }

  @Test
  public void aPatchThatLeavesTextBehindIsRefused() {
    // "=1" against a three line document silently dropped two lines before this check existed
    try {
      TextPatch.apply("a\nb\nc", "=1\n");
      fail("expected a refusal");
    } catch (TextPatch.PatchException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("did not consume"));
    }
  }

  @Test
  public void somethingTooLargeToDiffSaysSoRatherThanTakingForever() {
    String huge = ("line\n").repeat(TextPatch.MAX_LINES + 10);
    assertFalse(TextPatch.canDiff(huge, "small"));
    try {
      TextPatch.diff(huge, "small");
      fail("expected a refusal");
    } catch (TextPatch.PatchException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("snapshot"));
    }
    assertTrue("and something normal is fine", TextPatch.canDiff("a\nb", "a\nc"));
  }

  // ---- the patch stays small for a small edit --------------------------------------------------------

  @Test
  public void aOneLineEditInALongDocumentMakesATinyPatch() {
    // the whole reason for storing patches rather than snapshots
    StringBuilder before = new StringBuilder();
    for (int k = 0; k < 500; k++) {
      before.append("this is line ").append(k).append('\n');
    }
    String after = before.toString().replace("this is line 250", "this line changed");
    String patch = TextPatch.diff(before.toString(), after);
    assertTrue("a patch for one changed line should be small, was " + patch.length(),
        patch.length() < 100);
    roundTrips(before.toString(), after);
  }

  // ---- generators -------------------------------------------------------------------------------------

  private static String document(Random random, int lines) {
    StringBuilder text = new StringBuilder();
    for (int k = 0; k < lines; k++) {
      text.append(switch (random.nextInt(6)) {
        case 0 -> "";
        case 1 -> "# heading " + random.nextInt(5);
        case 2 -> "=" + random.nextInt(9);
        case 3 -> "\t" + random.nextInt(100);
        case 4 -> "word ".repeat(1 + random.nextInt(4)).trim();
        default -> "line " + random.nextInt(20);
      });
      if (k < lines - 1 || random.nextBoolean()) {
        text.append('\n');
      }
    }
    return text.toString();
  }

  /** a tiny alphabet, so common subsequences are ambiguous */
  private static String repetitive(Random random, int lines) {
    StringBuilder text = new StringBuilder();
    for (int k = 0; k < lines; k++) {
      text.append((char) ('a' + random.nextInt(3)));
      if (k < lines - 1 || random.nextBoolean()) {
        text.append('\n');
      }
    }
    return text.toString();
  }

  /** the kinds of change a person makes: retype a line, add some, delete some, reorder */
  private static String edited(Random random, String before) {
    List<String> lines = new ArrayList<>(TextPatch.lines(before));
    int edits = 1 + random.nextInt(4);
    for (int k = 0; k < edits; k++) {
      if (lines.isEmpty()) {
        lines.add("added " + random.nextInt(10));
        continue;
      }
      int at = random.nextInt(lines.size());
      switch (random.nextInt(4)) {
        case 0 -> lines.set(at, "changed " + random.nextInt(10));
        case 1 -> lines.add(at, "inserted " + random.nextInt(10));
        case 2 -> lines.remove(at);
        default -> {
          if (lines.size() > 1) {
            int other = random.nextInt(lines.size());
            String moved = lines.remove(at);
            lines.add(Math.min(other, lines.size()), moved);
          }
        }
      }
    }
    return TextPatch.join(lines);
  }
}
