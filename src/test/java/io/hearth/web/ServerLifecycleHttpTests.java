package io.hearth.web;

import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that need their own server: a different configs directory, a different limit, or a
 * lifecycle transition. Each one boots and tears down its own listener on an ephemeral port.
 */
public class ServerLifecycleHttpTests {
  @Test
  public void anEmptyTreeRefusesEverything() throws Exception {
    try (TestServer server = TestServer.empty(); Http http = new Http()) {
      assertEquals(404, http.get(server.port, "example.com", "/").status);
      assertEquals(404, http.get(server.port, "localhost", "/").status);
    }
  }

  @Test
  public void anEmptyConfigsDirectoryRefusesEverything() throws Exception {
    Configs configs = Configs.dir();
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      assertEquals(404, http.get(server.port, "example.com", "/").status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void theCheckedInConfigsDirectoryIsServable() throws Exception {
    File configs = new File("configs");
    if (!configs.isDirectory()) {
      return; // surefire runs from the project basedir; don't fail if something else doesn't
    }
    try (TestServer server = TestServer.ofConfigs(configs); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "localhost", "/").status);
      assertEquals(200, http.get(server.port, "example.org", "/").status);
      assertEquals(200, http.get(server.port, "junior.example.org", "/").status);
      assertEquals(200, http.get(server.port, "www.example.org", "/").status);
      assertEquals(404, http.get(server.port, "api.localhost", "/").status);
      assertEquals(404, http.get(server.port, "nope.org", "/").status);
      // "com" is only a junction in the tree; it serves nothing
      assertEquals(404, http.get(server.port, "com", "/").status);
      assertTrue(http.get(server.port, "localhost", "/").bodyContains("Localhost Community"));
      assertTrue(http.get(server.port, "junior.example.org", "/").bodyContains("Example Community Junior"));
      assertTrue(http.get(server.port, "www.example.org", "/").bodyContains("Example Community"));
    }
  }

  @Test
  public void verboseChangesDiagnosticsNotStatuses() throws Exception {
    Configs configs = Configs.standard();
    Verbose.Captured captured = Verbose.capturing();
    try (TestServer server = TestServer.ofConfigs(configs.file(), captured.verbose); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.com", "/").status);
      assertEquals(404, http.get(server.port, "nope.org", "/").status);
      assertEquals(410, http.get(server.port, "example.com", "/wp-login.php").status);
      // the one thing verbose does change in a response: the config path, for debugging
      assertTrue(http.get(server.port, "example.com", "/").bodyContains(".cfg"));
    } finally {
      configs.delete();
    }
    String narration = captured.text();
    assertTrue(narration.contains("loaded example.com from example.com.cfg"));
    assertTrue(narration.contains("serving example.com"));
    assertTrue(narration.contains("no configuration for nope.org"));
    assertTrue(narration.contains("shield blocked /wp-login.php"));
  }

  @Test
  public void verboseNarratesTheDescent() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example Community\",\"wildcard\":true}");
    Verbose.Captured captured = Verbose.capturing();
    try (TestServer server = TestServer.ofConfigs(configs.file(), captured.verbose); Http http = new Http()) {
      // the descent still happens; what it resolves to now sends the browser to the real name
      assertEquals(308, http.get(server.port, "a.b.example.org", "/").status);
    } finally {
      configs.delete();
    }
    String narration = captured.text();
    assertTrue(narration, narration.contains("descend org -> junction, no config here"));
    assertTrue(narration, narration.contains("descend example.org -> config (wildcard) example.org.cfg"));
    assertTrue(narration, narration.contains("descend b.example.org -> no such branch"));
    assertTrue(narration, narration.contains("most specific match: example.org"));
  }

  @Test
  public void verboseIsSilentWhenOff() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    Verbose off = new Verbose(false, new PrintStream(buffer, true, StandardCharsets.UTF_8));
    Configs configs = Configs.standard();
    try (TestServer server = TestServer.ofConfigs(configs.file(), off); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.com", "/").status);
      assertEquals(404, http.get(server.port, "nope.org", "/").status);
    } finally {
      configs.delete();
    }
    assertTrue("verbose off must write nothing", buffer.toString(StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  public void shutdownStopsAccepting() throws Exception {
    Configs configs = Configs.standard();
    TestServer server = TestServer.ofConfigs(configs.file());
    int port = server.port;
    try (Http http = new Http()) {
      assertEquals(200, http.get(port, "example.com", "/").status);
    }
    server.close();
    assertFalse(server.isAccepting());
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
      // a connect may still succeed against a lingering backlog, but no answer should come back
      socket.setSoTimeout(1000);
      socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes("ISO-8859-1"));
      socket.getOutputStream().flush();
      assertTrue("closed listener answered a request", socket.getInputStream().read() < 0);
    } catch (java.io.IOException expected) {
      // refused or reset; both mean the listener is gone
    } finally {
      configs.delete();
    }
  }

  @Test
  public void oversizedBodyIsRejectedByTheAggregator() throws Exception {
    Configs configs = Configs.standard();
    try (TestServer server = TestServer.of(configs.file(), TestServer.config(1024, 30))) {
      StringBuilder body = new StringBuilder();
      while (body.length() < 8192) {
        body.append('x');
      }
      String raw = Http.raw(server.port, "POST / HTTP/1.1\r\nHost: example.com\r\n"
          + "Content-Length: " + body.length() + "\r\n\r\n" + body);
      // the aggregator sits ahead of the handler, so this never becomes a 405
      assertTrue("expected 413, got: " + raw.lines().findFirst().orElse("<nothing>"),
          raw.startsWith("HTTP/1.1 413"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void bodyUnderTheLimitStillReachesTheMethodCheck() throws Exception {
    Configs configs = Configs.standard();
    try (TestServer server = TestServer.of(configs.file(), TestServer.config(1024, 30)); Http http = new Http()) {
      assertEquals(405, http.send(server.port, "example.com", "POST", "/", new byte[64]).status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void idleConnectionsAreClosed() throws Exception {
    Configs configs = Configs.standard();
    try (TestServer server = TestServer.of(configs.file(), TestServer.config(1024 * 1024, 1))) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", server.port), 5000);
        socket.setSoTimeout(6000);
        // say nothing; the read idle handler should hang up on us
        assertTrue("idle socket was not closed", socket.getInputStream().read() < 0);
      }
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aWildcardTopLevelDomainServesEverythingBeneathIt() throws Exception {
    Configs configs = Configs.dir().domain("com", "{\"name\":\"All Of Dot Com\",\"wildcard\":true}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "com", "/").status);
      assertEquals(308, http.get(server.port, "anything.com", "/").status);
      assertEquals("http://com/", http.get(server.port, "anything.com", "/").header("Location"));
      assertEquals("http://com/",
          http.get(server.port, "deep.nested.anything.com", "/").header("Location"));
      assertEquals(404, http.get(server.port, "anything.org", "/").status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aNonWildcardParentDoesNotBlockAWildcardGrandparent() throws Exception {
    Configs configs = Configs.dir()
        .domain("org", "{\"name\":\"Dot Org\",\"wildcard\":true}")
        .domain("example.org", "{\"name\":\"Only Example Community\",\"wildcard\":false}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      assertTrue(http.get(server.port, "example.org", "/").bodyContains("Only Example Community"));
      assertEquals("resolved by the wildcard grandparent, and sent to the name it belongs to",
          "http://org/", http.get(server.port, "www.example.org", "/").header("Location"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aJunctionLabelIsNotServable() throws Exception {
    // example.org.cfg puts "com" in the tree, but nothing configured it, so it serves nothing
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example Community\",\"wildcard\":true}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.org", "/").status);
      assertEquals(404, http.get(server.port, "com", "/").status);
      assertEquals(404, http.get(server.port, "elsewhere.com", "/").status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void contentDirectoriesBesideTheConfigsAreNotDomains() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example Community\",\"wildcard\":false}")
        .directory("example.org/css")
        .directory("content");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.org", "/").status);
      assertEquals(404, http.get(server.port, "content", "/").status);
      assertEquals(404, http.get(server.port, "css.example.org", "/").status);
    } finally {
      configs.delete();
    }
  }
}
