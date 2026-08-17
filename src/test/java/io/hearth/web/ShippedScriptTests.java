package io.hearth.web;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The JavaScript this server actually ships.
 *
 * Java has a compiler standing between a typo and a deploy; the script inside a mustache template
 * has nothing. A missing brace in an admin editor is a page that loads, looks right, and quietly
 * does none of what it was written to do -- and no Java test would notice, because every one of them
 * asserts on the HTML the server produced rather than on what a browser does with it.
 *
 * So: extract every script block that ships and run it past a real parser. This is the same argument
 * as {@link ProofContractTests}, one step weaker -- that test proves the shipped function computes
 * what the server computes, this one only proves the file is JavaScript. Both exist because code
 * that lives in a template is code nothing else is checking.
 */
public class ShippedScriptTests {
  /** a script block, with its attributes, so a JSON payload can be told from executable code */
  private static final Pattern SCRIPT = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL);
  /** any interpolation at all, escaped or raw */
  private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\{?[^{}]*}}}?");
  /** the escaped kind, which is the one that cannot be used inside a script */
  private static final Pattern ESCAPED =
      Pattern.compile("(?<!\\{)\\{\\{(?!\\{)[#^/!>&]?[^{}]*}}(?!})");
  private static final Path TEMPLATES = Path.of("src/main/resources/templates");
  /** the scripts that ship as files rather than inside a page */
  private static final Path SCRIPTS = Path.of("src/main/resources/live");

  private static List<Path> templates() throws IOException {
    try (var walk = Files.walk(TEMPLATES)) {
      return walk.filter(path -> path.toString().endsWith(".mustache")).sorted().toList();
    }
  }

  /**
   * The two files served from `/~live`.
   *
   * They are not inside a template, so the sweep below would never see them -- and they are the
   * largest scripts this server ships and the only ones a page has no compiler standing between.
   * A typo in either is a chat window that silently does nothing.
   */
  @Test
  public void theLiveScriptsParse() throws Exception {
    Assume.assumeTrue("scripts are on disk", Files.isDirectory(SCRIPTS));
    Assume.assumeTrue("node is available", hasNode());
    try (var walk = Files.walk(SCRIPTS)) {
      List<Path> scripts = walk.filter(path -> path.toString().endsWith(".js")).sorted().toList();
      org.junit.Assert.assertFalse("something should be there", scripts.isEmpty());
      for (Path script : scripts) {
        String problem = parseProblem(Files.readString(script));
        org.junit.Assert.assertNull(script + ": " + problem, problem);
      }
    }
  }

  /**
   * And that neither of them is in strict-mode trouble or reaching for something that is not there.
   *
   * `node --check` parses; this runs the file with a stub for the handful of globals it touches,
   * which is what catches a typo in a name rather than in the grammar.
   */
  @Test
  public void theSharedClientRunsWithoutABrowser() throws Exception {
    Assume.assumeTrue("scripts are on disk", Files.isDirectory(SCRIPTS));
    Assume.assumeTrue("node is available", hasNode());
    String stub = "global.window = {addEventListener: function(){}, EventSource: null,"
        + " location: {pathname: '/chat'}, history: {}};\n"
        + "global.document = {currentScript: {getAttribute: function(){return '/~live';}},"
        + " querySelectorAll: function(){return [];}, getElementById: function(){return null;},"
        + " addEventListener: function(){}, hidden: false};\n"
        + "global.BroadcastChannel = function(){ this.postMessage = function(){}; };\n"
        + "global.setInterval = function(){}; global.setTimeout = function(){};\n"
        + "global.fetch = function(){ return Promise.resolve({status: 204}); };\n";
    String out = node(stub + Files.readString(SCRIPTS.resolve("live.js"))
        + "\nif (!global.window.hearthLive) { throw new Error('nothing was exported'); }\n");
    org.junit.Assert.assertEquals("", out);
  }

  private static String node(String program) throws Exception {
    Path temp = Files.createTempFile("hearth-script", ".js");
    try {
      Files.writeString(temp, program);
      Process process = new ProcessBuilder("node", temp.toString())
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes());
      process.waitFor();
      return process.exitValue() == 0 ? "" : output.trim();
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  public void everyShippedScriptParses() throws Exception {
    Assume.assumeTrue("templates are on disk", Files.isDirectory(TEMPLATES));
    Assume.assumeTrue("node is available", hasNode());

    int checked = 0;
    List<String> broken = new ArrayList<>();
    for (Path template : templates()) {
      String body = Files.readString(template);
      Matcher matcher = SCRIPT.matcher(body);
      int block = 0;
      while (matcher.find()) {
        block++;
        if (!isExecutable(matcher.group(1))) {
          // a <script type="application/json"> block is a payload, not code; node would refuse it
          continue;
        }
        // whatever mustache would have substituted becomes a string literal; the shape of the code
        // around it is what is being checked
        String js = MUSTACHE.matcher(matcher.group(2)).replaceAll("\"X\"");
        String problem = parseProblem(js);
        if (problem != null) {
          broken.add(template.getFileName() + " block " + block + ": " + problem);
        }
        checked++;
      }
    }
    assertTrue("no scripts were found, so this test proved nothing", checked > 0);
    if (!broken.isEmpty()) {
      fail("shipped javascript does not parse:\n  " + String.join("\n  ", broken));
    }
  }

  @Test
  public void noScriptReadsAnEscapedMustacheValue() throws Exception {
    // Mustache escapes for HTML, and HTML entities are NOT decoded inside a script block -- so
    // {{url}} holding "/x?a=1" arrives in the script as "/x?a&#61;1" and silently does not work.
    // That is exactly how the admin's live buttons broke: they fetched "?fragment&#61;1", the flag
    // never parsed, and the entire page rendered inside the panel.
    //
    // Two things are allowed. A triple-brace {{{blob}}} is raw, so it does not have this problem --
    // it is how a server-built JSON blob gets in, and the mint blob on the account pages uses it.
    // And a data- attribute, which the HTML parser decodes on the way in, which is what the admin
    // uses for URLs and configuration. What is banned is the escaped kind, which looks correct and
    // is not.
    Assume.assumeTrue("templates are on disk", Files.isDirectory(TEMPLATES));

    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      Matcher matcher = SCRIPT.matcher(Files.readString(template));
      while (matcher.find()) {
        if (!isExecutable(matcher.group(1))) {
          continue;
        }
        Matcher value = ESCAPED.matcher(matcher.group(2));
        while (value.find()) {
          offenders.add(template.getFileName() + " has " + value.group()
              + " inside a script -- use a data- attribute, or {{{raw}}} for a JSON blob");
        }
      }
    }
    assertTrue(String.join("\n  ", offenders), offenders.isEmpty());
  }

  @Test
  public void noPageAsksTheNetworkForAnything() throws Exception {
    // The resource budget, as a test. What is forbidden is *somebody else's server* -- a page that
    // fetches from a CDN has told them a member was reading it, and stops working when they have a
    // bad day. A same-origin /3rd/ path is a file inside this jar; it costs a request and nothing
    // else, which is the budget the rule was protecting.
    Assume.assumeTrue("templates are on disk", Files.isDirectory(TEMPLATES));
    Pattern url = Pattern.compile("(?:src|href)=\"([^\"]*)\"");
    for (Path template : templates()) {
      String body = Files.readString(template);
      // An <img> is allowed for exactly one thing: a file somebody in this community uploaded,
      // served from this server at /attachment/. That is the point of attachments, and it is not
      // what invariant 18 was protecting -- the budget was about *somebody else's server* and about
      // asset files sitting beside the jar, and an upload is neither. A hard-coded image path is
      // still refused, because that would be an asset file.
      Matcher images = Pattern.compile("<img[^>]*src=\"([^\"]*)\"").matcher(body);
      while (images.find()) {
        String source = images.group(1);
        assertTrue(template + " has an image from " + source + "; the only images a page may"
                + " request are uploads, served from this server",
            source.startsWith("{{") || source.startsWith("/attachment/"));
      }
      assertFalse(template + " reaches out for a font", body.contains("@import"));
      Matcher matcher = url.matcher(body);
      while (matcher.find()) {
        String target = matcher.group(1);
        boolean offsite = target.startsWith("http://") || target.startsWith("https://")
            || target.startsWith("//");
        assertFalse(template + " loads " + target + " from another server", offsite);
      }
      // and the only same-origin assets a page may pull are the vendored ones, which are pinned by
      // version in the path so they can never change under a cached page
      Matcher tags = Pattern.compile("<(?:script|link)[^>]*(?:src|href)=\"([^\"]+)\"")
          .matcher(body);
      while (tags.find()) {
        String target = tags.group(1);
        // a data: URI is bytes already in the page (the favicon), and a {{placeholder}} is one
        // the server fills with them; neither is a request
        if (target.startsWith("data:") || target.startsWith("{{")) {
          continue;
        }
        // /3rd is a vendored library; the manifest and the worker are routes this server generates.
        // Anything else pulled into a page is a file somebody added, which is what the rule is for.
        boolean allowed = target.startsWith("/3rd/")
            || target.equals("/manifest.webmanifest") || target.equals("/sw.js")
            // the light/dark switch, which has to be a file rather than an inline script because
            // inline needs a nonce and every page needs this one
            || target.equals("/~theme.js")
            // the menu's manners: closing on an outside click and on escape. Deferred, and the
            // menu opens and closes without it.
            || target.equals("/~menu.js")
            // the rest timer's ticking. The page is already honest without it -- the server
            // renders how long it has been, in words -- so this only makes the number move.
            || target.equals("/~rest.js")
            // the home screen icon, which iOS reads from the page and not from the manifest. It is
            // drawn by this server in the community's own colours -- a route, not an asset file,
            // which is the distinction this rule is actually about.
            || target.startsWith("/~app/icon-");
        assertTrue(template + " pulls " + target + ", which is neither a vendored /3rd/ asset nor"
            + " a route this server serves", allowed);
      }
    }
  }

  /** does this script tag hold code the browser will run, or a payload it will only read? */
  private static boolean isExecutable(String attributes) {
    return !attributes.contains("type=") || attributes.contains("javascript");
  }

  private static boolean hasNode() {
    try {
      return new ProcessBuilder("node", "--version").start().waitFor() == 0;
    } catch (Exception ex) {
      return false;
    }
  }

  /** run node's parser over a snippet; null when it is valid javascript */
  private static String parseProblem(String js) throws Exception {
    File file = File.createTempFile("shipped-", ".js");
    file.deleteOnExit();
    Files.writeString(file.toPath(), js, StandardCharsets.UTF_8);
    Process node = new ProcessBuilder("node", "--check", file.getAbsolutePath())
        .redirectErrorStream(true).start();
    String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return node.waitFor() == 0 ? null : output.lines().filter(line -> line.contains("Error"))
        .findFirst().orElse(output.strip());
  }
}
