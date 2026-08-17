package io.hearth.places;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The kind swap in the address book editor, driven under node.
 *
 * The promise this script makes is that changing a kind loses nothing, and that promise is entirely
 * behaviour: the markup shows a fresh set of boxes, and whether the answers survived is invisible
 * until somebody saves and finds out. Asserting that the file contains the right words would prove
 * nothing about it -- the same reason `ProofContractTests` exists.
 *
 * So this extracts the script that actually ships, runs it against a document small enough to be
 * obviously right, and checks what a person would check: type an answer, change the kind, change
 * back, is it still there.
 *
 * If node is not installed the test skips. A missing toolchain is not a broken build.
 */
public class KindSwapScriptTests {
  private static final Path TEMPLATE =
      Path.of("src/main/resources/templates/admin/places_form.mustache");
  private static boolean nodeAvailable;
  private static String shipped;

  @BeforeClass
  public static void extract() throws Exception {
    nodeAvailable = which("node") || which("nodejs");
    if (!Files.isRegularFile(TEMPLATE)) {
      return;
    }
    String source = Files.readString(TEMPLATE);
    int open = source.indexOf("<script nonce=");
    int body = open < 0 ? -1 : source.indexOf('>', open) + 1;
    int close = source.indexOf("</script>", body);
    shipped = body > 0 && close > body ? source.substring(body, close) : null;
  }

  /** the smallest document the script can run against, plus a scenario, plus what it printed */
  private static String run(String scenario) throws Exception {
    assumeTrue("node is not installed; skipping", nodeAvailable);
    assumeTrue("the editor template is on disk", shipped != null);

    String harness = """
        function el(tag) {
          return {
            tag, children: [], attrs: {}, _text: '', value: '', hidden: false, id: '', name: '',
            className: '', required: false, options: [],
            setAttribute(k, v) { this.attrs[k] = String(v); },
            getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
            appendChild(c) { this.children.push(c); return c; },
            addEventListener(kind, fn) { (this.listeners ||= {})[kind] = fn; },
            fire(kind) { if (this.listeners && this.listeners[kind]) this.listeners[kind](); },
            get textContent() { return this._text; },
            set textContent(v) { this._text = v; if (v === '') this.children = []; },
            querySelectorAll(sel) { return collect(this, sel); }
          };
        }
        function collect(node, sel) {
          const out = [];
          (function walk(n) {
            if (n.attrs && n.attrs['data-field-name'] !== undefined) out.push(n);
            (n.children || []).forEach(walk);
          })(node);
          return out;
        }
        const host = el('div');
        host.setAttribute('data-shapes', JSON.stringify({
          ranch: [{name: 'grass_finished', label: 'Grass finished', help: '', required: false,
                   multiline: false}],
          vendor: [{name: 'discount', label: 'The deal', help: 'ask nicely', required: true,
                    multiline: true}]
        }));
        host.setAttribute('data-values', JSON.stringify({grass_finished: 'yes'}));
        const carry = el('input');
        const heading = el('h2');
        const kindLabel = el('span');
        const form = el('form');
        const select = el('select');
        select.value = 'ranch';
        select.setAttribute('data-current', 'ranch');
        select.options = [{value: 'ranch', text: 'Ranch'}, {value: 'vendor', text: 'Vendor'}];
        select.form = form;
        const warning = el('p');
        warning.hidden = true;
        const byQuery = {
          '[data-kind]': select, '[data-extras]': host, '[data-carry]': carry,
          '[data-extras-heading]': heading, '[data-kind-label]': kindLabel,
          '[data-kind-warning]': warning
        };
        globalThis.document = {
          querySelector: (sel) => byQuery[sel] || null,
          createElement: (tag) => el(tag),
          createTextNode: (t) => ({ tag: '#text', _text: t, children: [] })
        };
        function boxes() { return host.querySelectorAll('[data-field-name]'); }
        function pick(kind) { select.value = kind; select.fire('change'); }
        function type(value) { boxes()[0].value = value; host.fire('input'); }
        """;

    Path file = Files.createTempFile("hearth-kindswap", ".mjs");
    try {
      Files.writeString(file, harness + "\n" + shipped + "\n" + scenario, StandardCharsets.UTF_8);
      ArrayList<String> command = new ArrayList<>();
      command.add(which("node") ? "node" : "nodejs");
      command.add(file.toString());
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue("node did not finish", process.waitFor(60, TimeUnit.SECONDS));
      assertEquals("node failed: " + output, 0, process.exitValue());
      return output.strip();
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void itShowsTheQuestionsOfTheKindItStartsOn() throws Exception {
    assertEquals("field_grass_finished=yes",
        run("const f = boxes(); console.log(f[0].name + '=' + f[0].value);"));
  }

  @Test
  public void changingTheKindSwapsTheQuestionsWithNoReload() throws Exception {
    assertEquals("field_discount",
        run("pick('vendor'); console.log(boxes().map(b => b.name).join(','));"));
  }

  @Test
  public void anAnswerTypedUnderOneKindIsStillThereWhenYouComeBack() throws Exception {
    // the whole promise of the swap, and the only way to check it is to do it
    assertEquals("grain, actually",
        run("type('grain, actually'); pick('vendor'); pick('ranch');"
            + " console.log(boxes()[0].value);"));
  }

  @Test
  public void everythingTypedIsCarriedInTheHiddenFieldForTheSave() throws Exception {
    // an answer typed under a kind then swapped away from is in no database and in no visible box;
    // if it is not in the carry it is gone, and the save would silently drop it
    assertEquals("grain, actually|10%",
        run("type('grain, actually'); pick('vendor'); type('10%');"
            + " const held = JSON.parse(carry.value);"
            + " console.log(held.grass_finished + '|' + held.discount);"));
  }

  @Test
  public void anAnswerThatNeverFiredAnInputEventIsStillCaught() throws Exception {
    // autofill, some paste paths, and anything programmatic can change a value without an input
    // event. Harvesting on the way out of a kind is what covers those; the input listener alone
    // would drop them, and the loss would be invisible until somebody noticed a blank field.
    assertEquals("filled by something else",
        run("boxes()[0].value = 'filled by something else';"
            + " pick('vendor'); pick('ranch'); console.log(boxes()[0].value);"));
  }

  @Test
  public void theBoxMatchesWhatTheFieldDeclared() throws Exception {
    assertEquals("textarea|true|ask nicely",
        run("pick('vendor'); const b = boxes()[0];"
            + " console.log(b.tag + '|' + b.required + '|' + host.children[0].children[2]._text);"));
  }

  @Test
  public void theWarningFollowsWhetherTheKindActuallyChanged() throws Exception {
    assertEquals("shown|hidden",
        run("pick('vendor'); const a = warning.hidden ? 'hidden' : 'shown';"
            + " pick('ranch'); const b = warning.hidden ? 'hidden' : 'shown';"
            + " console.log(a + '|' + b);"));
  }

  @Test
  public void theHeadingFollowsTheKind() throws Exception {
    assertEquals("Vendor",
        run("pick('vendor'); console.log(kindLabel.textContent);"));
  }

  private static boolean which(String command) {
    try {
      Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception ex) {
      return false;
    }
  }
}
