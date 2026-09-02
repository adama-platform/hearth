package io.hearth.js;

import com.caoccao.javet.exceptions.JavetCompilationException;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.exceptions.JavetExecutionException;
import com.caoccao.javet.exceptions.JavetTerminatedException;
import com.caoccao.javet.interop.V8Guard;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V8, for pages whose body is a program rather than a document.
 *
 * <b>Every execution gets its own isolate.</b> A fresh {@code V8Runtime} costs about 0.8ms once the
 * native library is warm, which is cheap enough to buy the property that matters: nothing a page
 * defines can be seen by the next page, or by the next request for the same page. The alternative
 * -- a pooled runtime with globals reset between runs -- is one forgotten global away from one
 * community's page reading another's state, and "reset everything V8 exposes" is not a list anybody
 * can finish.
 *
 * <b>The two APIs are JavaScript, not Java callbacks.</b> {@code render} and {@code meta} are
 * defined by a one-line prologue that runs ahead of the author's code, they accumulate into ordinary
 * arrays and objects, and the whole result comes back as one JSON string. No value crosses the JNI
 * boundary per call, there is no callback API to hold wrong, and the marshalling that is left is
 * Jackson parsing a string. The prologue is exactly one line so a reported error line maps to the
 * author's line by subtracting one.
 *
 * <b>A runaway script is terminated, not waited for.</b> {@link V8Guard} interrupts V8 itself after
 * {@link #TIMEOUT_MS}; the {@code Future} timeout outside it is the backstop for a task wedged
 * somewhere the guard cannot reach. Without the first, {@code while(true){}} in a page body takes a
 * thread out of the pool permanently, and the fourth such page takes the feature down.
 *
 * <b>Nothing is created until somebody writes a dynamic page.</b> The pool and the native library
 * load on first use, so a community that never uses this -- which is most of them -- pays no threads
 * and no memory for it. That is also why this is process-wide rather than per-domain: V8's own host
 * is a singleton, threads are a machine resource rather than a community's, and a per-domain pool on
 * a box hosting six communities would be six pools mostly asleep.
 *
 * <b>What is deliberately absent:</b> no network, no filesystem, no timers, no modules, no
 * {@code require}. The isolate is bare V8 with two functions added. A page cannot reach this server,
 * the database, or the internet, and the way that is guaranteed is that nothing was ever bound --
 * not that something refuses.
 */
public final class JavaScript {
  private static final Logger LOG = LoggerFactory.getLogger(JavaScript.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * How long a page's body may run.
   *
   * A second is a very long time for a page that is only allowed to build a string, and it is short
   * enough that a mistake shows up as a refusal rather than as a server nobody can reach. This is a
   * resource ceiling rather than a product decision, so it lives here rather than in the settings
   * table -- the same rule that keeps request sizes out of the admin section.
   */
  public static final long TIMEOUT_MS = 1000;

  /** the prologue is ONE line on purpose: see the class note about error line numbers */
  private static final String PROLOGUE =
      "var __out=[],__meta={};"
          + "function render(s){__out.push(s==null?'':String(s));}"
          + "function meta(k,v){if(k!=null){__meta[String(k)]=(v==null?'':String(v));}}"
          + "function __call(t,op,a){var r=__data(JSON.stringify({t:t,op:op,a:a}));"
          + "var p=JSON.parse(r);if(p&&p.__error){throw new Error(p.__error);}return p;}";

  /** a newline first, so an author's trailing `// comment` cannot swallow it */
  private static final String EPILOGUE =
      "\n;JSON.stringify({body:__out.join(''),meta:__meta})";

  private static volatile JavaScript shared;

  private final ExecutorService pool;
  private final int threads;

  private JavaScript(int threads) {
    this.threads = threads;
    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "hearth-js-" + counter.incrementAndGet());
      // daemon: a wedged page must never be the reason the process will not exit
      thread.setDaemon(true);
      return thread;
    };
    this.pool = Executors.newFixedThreadPool(threads, factory);
  }

  /**
   * The one engine for this process, built the first time anybody asks.
   *
   * Small on purpose. These threads exist to run page bodies that build a string, at the request
   * rate of a community of a few hundred people; the number that matters is "more than one, so a
   * slow page does not block a fast one", not "as many as the box has".
   */
  public static JavaScript shared() {
    JavaScript instance = shared;
    if (instance == null) {
      synchronized (JavaScript.class) {
        instance = shared;
        if (instance == null) {
          int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
          instance = new JavaScript(threads);
          shared = instance;
          LOG.info("javascript-engine-started threads={}", threads);
        }
      }
    }
    return instance;
  }

  /** how many threads are running page bodies; the admin section prints it */
  public int threads() {
    return threads;
  }

  /**
   * Is there a V8 to run at all?
   *
   * The native library is a per-platform jar and only the ones a server runs on are bundled, so
   * this can be false on a developer's machine. It is asked before a dynamic page is saved, so the
   * refusal happens at the editor rather than at somebody's browser.
   */
  public static boolean available() {
    try {
      return V8Host.getV8Instance().isLibraryLoaded();
    } catch (Throwable ex) {
      // a native library that will not load throws things that are not Exceptions
      return false;
    }
  }

  /**
   * Run one page body.
   *
   * Never throws. Every way this can go wrong -- a syntax error, a thrown error, a timeout, a
   * missing native library, an interrupted thread -- comes back as a {@link Run} carrying the
   * message, because the caller is a request that has to answer something.
   */
  public Run run(String source) {
    return run(source, Page.NONE);
  }

  /**
   * Run one page body, with whatever this page is allowed to reach.
   *
   * {@link Page} carries two things: a line of JavaScript defining the functions this particular
   * page gets, and the Java side that answers when one of them is called. Both are per-request,
   * because which tables exist and what the query string said are both per-request.
   */
  public Run run(String source, Page page) {
    long started = System.nanoTime();
    if (source == null || source.isBlank()) {
      return new Run("", Map.of(), null, 0, System.nanoTime() - started);
    }
    Future<Run> future = pool.submit(() -> execute(source, page));
    try {
      // the guard stops V8 at TIMEOUT_MS; this waits a little longer, so the ordinary case is the
      // guard reporting a termination rather than this cancelling a task that was about to answer
      return future.get(TIMEOUT_MS * 2 + 500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      future.cancel(true);
      return failed("The page did not finish, and V8 could not be interrupted.",
          0, System.nanoTime() - started);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return failed("Interrupted.", 0, System.nanoTime() - started);
    } catch (java.util.concurrent.ExecutionException ex) {
      LOG.error("javascript-run-failed", ex.getCause());
      return failed(String.valueOf(ex.getCause()), 0, System.nanoTime() - started);
    }
  }

  private Run execute(String source, Page page) {
    long started = System.nanoTime();
    try (V8Runtime runtime = V8Host.getV8Instance().createV8Runtime()) {
      // The callback context is removed by hand before the runtime closes, and that is not
      // housekeeping -- it is the difference between this feature being usable and not.
      //
      // A context stays registered on the runtime until V8 collects the function that refers to it,
      // and a runtime closed while it still holds one never gives the memory back. Measured: 600
      // executions grew RSS by 81MB and kept it, against 9MB for the same 600 with nothing bound.
      // On a server answering dynamic pages that is a gigabyte every six thousand requests, which
      // is a leak nobody would find until the box fell over on a busy evening. Removing the handle
      // explicitly brings the growth to zero.
      JavetCallbackContext context = bindData(runtime, page);
      try (V8Guard guard = runtime.getGuard(TIMEOUT_MS)) {
        guard.setDebugModeEnabled(false);
        // still exactly one line before the author's first: page.prologue() is checked for
        // newlines when it is built, because a second line here silently shifts every error
        // somebody is ever shown by one
        String json = runtime.getExecutor(PROLOGUE + page.prologue() + "\n" + source + EPILOGUE)
            .executeString();
        return parse(json, System.nanoTime() - started);
      } finally {
        // in a finally, because a page that threw or overran leaks exactly as much as one that
        // worked -- and those are the pages somebody reloads
        runtime.removeCallbackContext(context.getHandle());
      }
    } catch (JavetTerminatedException ex) {
      return failed("The page took longer than " + TIMEOUT_MS + "ms and was stopped.",
          0, System.nanoTime() - started);
    } catch (JavetCompilationException ex) {
      return fromScriptingError(ex.getScriptingError(), System.nanoTime() - started);
    } catch (JavetExecutionException ex) {
      return fromScriptingError(ex.getScriptingError(), System.nanoTime() - started);
    } catch (JavetException ex) {
      return failed(String.valueOf(ex.getMessage()), 0, System.nanoTime() - started);
    } catch (Throwable ex) {
      // an UnsatisfiedLinkError for a platform whose native jar is not bundled lands here
      LOG.error("javascript-engine-failed", ex);
      return failed("This server has no JavaScript engine for its platform.",
          0, System.nanoTime() - started);
    }
  }

  /**
   * The one function that crosses into Java, and the only one.
   *
   * Everything a page can reach goes through {@code __data(json) -> json}: one string in, one
   * string out, dispatched on the Java side. That keeps the marshalling to something a person can
   * read -- there is no object graph being converted across the boundary and no callback API to
   * hold wrong -- and it means adding a capability is adding a case in a switch rather than a new
   * binding with new lifetime rules.
   *
   * A failure comes back as {@code {"__error": "..."}} and the prologue throws it, so a page that
   * asks for something it may not have gets a JavaScript error on the line that asked, rather than
   * a null it will trip over three lines later.
   */
  private static JavetCallbackContext bindData(V8Runtime runtime, Page page)
      throws JavetException {
    JavetCallbackContext context = new JavetCallbackContext("__data",
        JavetCallbackType.DirectCallNoThisAndResult,
        (IJavetDirectCallable.NoThisAndResult<Exception>) (V8Value... args) -> {
          String request = args.length > 0 && args[0] != null ? args[0].toString() : "";
          String answer;
          try {
            answer = page.host().data(request);
          } catch (RuntimeException ex) {
            LOG.error("javascript-host-failed", ex);
            answer = "{\"__error\":\"that could not be answered\"}";
          }
          return runtime.createV8ValueString(answer == null ? "null" : answer);
        });
    try (V8ValueObject global = runtime.getGlobalObject();
         V8ValueFunction function = runtime.createV8ValueFunction(context)) {
      global.set("__data", function);
    }
    return context;
  }

  private static Run fromScriptingError(
      com.caoccao.javet.exceptions.JavetScriptingError error, long nanos) {
    if (error == null) {
      return failed("The page could not be run.", 0, nanos);
    }
    // the prologue is one line, so V8's line N is the author's line N-1
    int line = Math.max(0, error.getLineNumber() - 1);
    return failed(error.getDetailedMessage(), line, nanos);
  }

  private static Run parse(String json, long nanos) {
    if (json == null) {
      return failed("The page produced nothing at all.", 0, nanos);
    }
    try {
      JsonNode node = JSON.readTree(json);
      LinkedHashMap<String, String> meta = new LinkedHashMap<>();
      JsonNode metaNode = node.path("meta");
      metaNode.fieldNames().forEachRemaining(
          name -> meta.put(name, metaNode.path(name).asText("")));
      return new Run(node.path("body").asText(""), Collections.unmodifiableMap(meta),
          null, 0, nanos);
    } catch (Exception ex) {
      return failed("The page's result could not be read back.", 0, nanos);
    }
  }

  private static Run failed(String message, int line, long nanos) {
    return new Run("", Map.of(), message, line, nanos);
  }

  /** for tests, and for a shutdown that does not wait on somebody's infinite loop */
  public static void stopShared() {
    synchronized (JavaScript.class) {
      if (shared != null) {
        shared.pool.shutdownNow();
        shared = null;
      }
    }
  }

  /**
   * What a page is allowed to reach, for one request.
   *
   * The prologue must be a single line and is checked here rather than trusted, because the error
   * line numbers every author sees are computed by subtracting exactly one.
   */
  public record Page(String prologue, Host host) {
    public static final Page NONE = new Page("", request -> "null");

    public Page {
      if (prologue == null) {
        prologue = "";
      }
      if (prologue.indexOf('\n') >= 0 || prologue.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("a page prologue has to be one line");
      }
      if (host == null) {
        host = request -> "null";
      }
    }
  }

  /** the Java side of {@code __data}: one JSON request in, one JSON answer out */
  public interface Host {
    String data(String requestJson);
  }

  /**
   * What one execution produced.
   *
   * {@code error} is null when it worked. A failed run carries an empty body rather than a partial
   * one: half a page rendered up to the point something threw is a page that looks finished and is
   * not, and the author needs to see the error rather than the fragment.
   */
  public record Run(String body, Map<String, String> meta, String error, int errorLine,
                    long nanos) {
    public boolean failed() {
      return error != null;
    }

    public double millis() {
      return nanos / 1_000_000.0;
    }
  }
}
