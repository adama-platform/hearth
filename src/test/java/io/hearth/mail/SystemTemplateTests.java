package io.hearth.mail;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every message this server sends, in the community's own words.
 *
 * The two things worth proving here are the two that were wrong before this existed. One: the
 * wording that ships is not written into the database, so a community that never opens the screen
 * keeps getting the improvements. Two: what an administrator types actually reaches the message --
 * an editing screen whose output nothing reads is a setting somebody believes in and does not have.
 */
public class SystemTemplateTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }
}
