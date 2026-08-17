package io.hearth.web;

import io.hearth.auth.Tokens;
import io.hearth.testkit.Browser;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The server and the shipped JavaScript have to compute the same proof, for every nonce, forever.
 *
 * This test exists because the previous one did not do this. It compared {@code FormMint.proofOf}
 * with a reimplementation of the same algorithm in the test client -- two Java functions, agreeing
 * with each other and with nothing a browser runs. The script itself used a 32-bit FNV-1a, which
 * JavaScript cannot compute: numbers are doubles, so the multiply overflowed past 2^53 and lost the
 * low bits while Java's int multiply wrapped exactly. Every real browser was told its JavaScript was
 * switched off, and every test passed.
 *
 * So this one takes the function out of the template that actually ships, runs it under node, and
 * compares. It is the only test in the suite that executes the client-side code, and it is the only
 * one that could have caught that.
 *
 * If node is not installed the test skips rather than failing -- a missing toolchain is not a broken
 * server -- but the build that matters has it.
 */
public class ProofContractTests {
  /** pull the whole `function proof(...) { ... }` out of the template, brace-matched */
  private static final Pattern PROOF_START = Pattern.compile("\\n(\\s*)function proof\\(");

  private static String shippedSource;
  private static boolean nodeAvailable;

  @BeforeClass
  public static void loadTheShippedScript() throws Exception {
    shippedSource = extractProof(templateSource());
    assertNotNull("the template must still contain a proof function", shippedSource);
    nodeAvailable = which("node") || which("nodejs");
  }

  /** the template exactly as it is packaged into the jar */
  private static String templateSource() throws IOException {
    try (InputStream in = ProofContractTests.class.getClassLoader()
        .getResourceAsStream("templates/minted.mustache")) {
      assertNotNull("templates/minted.mustache must be on the classpath", in);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** brace-match the function body, so this keeps working when the code around it changes */
  static String extractProof(String template) {
    Matcher matcher = PROOF_START.matcher(template);
    if (!matcher.find()) {
      return null;
    }
    int start = matcher.start() + 1;
    int open = template.indexOf('{', matcher.end());
    int depth = 0;
    for (int k = open; k < template.length(); k++) {
      char ch = template.charAt(k);
      if (ch == '{') {
        depth++;
      } else if (ch == '}') {
        depth--;
        if (depth == 0) {
          return template.substring(start, k + 1);
        }
      }
    }
    return null;
  }

  private static boolean which(String command) {
    try {
      Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception ex) {
      return false;
    }
  }

  /** run the shipped function under node for a list of nonces */
  private static List<String> runShipped(List<String> nonces) throws Exception {
    StringBuilder script = new StringBuilder(shippedSource);
    script.append("\nfor (const n of process.argv.slice(2)) console.log(proof(n));\n");
    Path file = Files.createTempFile("hearth-proof", ".js");
    try {
      Files.writeString(file, script.toString(), StandardCharsets.UTF_8);
      ArrayList<String> command = new ArrayList<>();
      command.add(which("node") ? "node" : "nodejs");
      command.add(file.toString());
      command.addAll(nonces);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue("node did not finish", process.waitFor(60, TimeUnit.SECONDS));
      assertEquals("node failed: " + output, 0, process.exitValue());
      return List.of(output.strip().split("\n"));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void assertAgrees(List<String> nonces) throws Exception {
    assumeTrue("node is not installed; skipping the cross-language check", nodeAvailable);
    List<String> fromScript = runShipped(nonces);
    assertEquals(nonces.size(), fromScript.size());
    for (int k = 0; k < nonces.size(); k++) {
      assertEquals("the shipped script and the server disagree on '" + nonces.get(k) + "'",
          FormMint.proofOf(nonces.get(k)), fromScript.get(k));
    }
  }

  @Test
  public void theShippedScriptAgreesWithTheServerOnRealNonces() throws Exception {
    // exactly the shape FormMint mints: 24 characters of base64url
    ArrayList<String> nonces = new ArrayList<>();
    for (int k = 0; k < 200; k++) {
      nonces.add(Tokens.newHandle());
    }
    assertAgrees(nonces);
  }

  @Test
  public void theyAgreeOnTheAwkwardCases() throws Exception {
    assertAgrees(List.of(
        "",
        "A",
        "ab",
        "abc",
        // the two that were live when the bug was found
        "Togcs3yP4v8H37gNOkOp_M1U",
        "d_JWXcP59Tdxibj9ngvqC1hL",
        // long enough that a 32-bit accumulator would have wrapped many times over
        "a".repeat(200),
        "____________------------",
        "0000000000000000",
        "zzzzzzzzzzzzzzzzzzzzzzzz"));
  }

  @Test
  public void theTestClientAgreesToo() {
    // a third implementation, and the reason it is not enough on its own
    for (int k = 0; k < 500; k++) {
      String nonce = Tokens.newHandle();
      assertEquals(FormMint.proofOf(nonce), Browser.proofOf(nonce));
    }
  }

  @Test
  public void theScriptStaysWithinArithmeticJavaScriptCanDoExactly() throws Exception {
    // The failure mode is silent and only shows up in a browser, so the shape of the arithmetic is
    // pinned here: no 32-bit wraparound, no shifts, no reliance on Math.imul being remembered.
    assertTrue("the proof function should not have been rewritten to multiply by a 32-bit constant",
        !shippedSource.contains("0x01000193") && !shippedSource.contains("16777619"));
    assertTrue("nor to lean on bit shifting", !shippedSource.contains("<<") && !shippedSource.contains(">>>"));
    assertTrue("it should take a modulus, which is what keeps every intermediate small",
        shippedSource.contains("%"));
  }

  @Test
  public void theExtractionActuallyFoundTheFunction() {
    assertTrue(shippedSource.startsWith("  function proof(nonce)"));
    assertTrue(shippedSource.trim().endsWith("}"));
    assertTrue(shippedSource.contains("charCodeAt"));
  }
}
