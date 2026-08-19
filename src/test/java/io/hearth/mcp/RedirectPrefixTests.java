package io.hearth.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where an authorization code may be sent, which is the whole security of the authorize step.
 *
 * No amount of PKCE helps if the code goes to the attacker in the first place, so this is matched
 * against an explicit prefix list rather than a pattern. The hazard is not the pattern language --
 * there isn't one -- it is that `startsWith` has no idea where a hostname ends.
 *
 * The shipped vendor prefixes all end in a slash and were never exposed to it. An operator adding
 * their own with `mcp.extra-redirect-prefixes` types the obvious thing, which is a bare origin, and
 * a bare origin as a raw prefix accepts every hostname that merely starts with it.
 */
public class RedirectPrefixTests {
  private static final ObjectMapper JSON = new ObjectMapper();

  private McpConfig configWith(String... prefixes) throws ConfigException {
    ObjectNode node = JSON.createObjectNode();
    node.put("enabled", true);
    node.putArray("vendors");
    var array = node.putArray("extra-redirect-prefixes");
    for (String prefix : prefixes) {
      array.add(prefix);
    }
    return new McpConfig(new ConfigObject(node, "example.org.cfg: mcp"));
  }

  /**
   * The finding: a prefix that stops in the middle of a hostname matches anything appended to it.
   *
   * Both spellings matter and they fail the same way. `.evil.net` is a different registrable domain
   * that merely starts with the trusted string; `@evil.net` puts the trusted string in the userinfo
   * of a URL whose host is somebody else's, which is the older and more surprising of the two.
   */
  @Test
  public void aBareOriginDoesNotMatchALongerHostname() throws Exception {
    McpConfig config = configWith("https://connector.example.com");

    assertFalse("a longer hostname must not match a shorter prefix",
        config.allowsRedirect("https://connector.example.com.evil.net/callback"));
    assertFalse("userinfo must not smuggle the trusted name onto another host",
        config.allowsRedirect("https://connector.example.com@evil.net/callback"));
    assertFalse(config.allowsRedirect("https://connector.example.com-evil.net/callback"));
  }

  /** and it still does the job it was added for */
  @Test
  public void aBareOriginStillMatchesItsOwnCallbacks() throws Exception {
    McpConfig config = configWith("https://connector.example.com");

    assertTrue(config.allowsRedirect("https://connector.example.com/callback"));
    assertTrue(config.allowsRedirect("https://connector.example.com/oauth/cb?x=1"));
    assertEquals("the stored prefix says where it stops",
        "https://connector.example.com/", config.extraRedirectPrefixes.get(0));
  }

  @Test
  public void aPortIsPartOfTheAuthorityAndIsHandledTheSameWay() throws Exception {
    McpConfig config = configWith("https://connector.example.com:8443");

    assertTrue(config.allowsRedirect("https://connector.example.com:8443/cb"));
    assertFalse(config.allowsRedirect("https://connector.example.com:8443.evil.net/cb"));
  }

  /**
   * A prefix that already names a path is left exactly as written.
   *
   * `https://host/cb` also matching `https://host/cbx` is a different page on a host somebody
   * already trusted, which is not a boundary being crossed -- and narrowing it would break an
   * operator who deliberately wrote a prefix rather than a whole URL.
   */
  @Test
  public void aPrefixWithAPathIsLeftAlone() throws Exception {
    McpConfig config = configWith("https://connector.example.com/oauth");

    assertEquals("https://connector.example.com/oauth", config.extraRedirectPrefixes.get(0));
    assertTrue(config.allowsRedirect("https://connector.example.com/oauth/callback"));
    assertFalse(config.allowsRedirect("https://connector.example.com/other"));
  }

  /** the shipped vendor prefixes were always safe, and this says so rather than assuming it */
  @Test
  public void everyShippedVendorPrefixStopsAtABoundary() {
    for (Vendor vendor : Vendor.values()) {
      for (String prefix : vendor.redirectPrefixes()) {
        assertTrue(vendor + " ships '" + prefix + "', which does not stop at a path boundary",
            prefix.startsWith("https://")
                && prefix.substring("https://".length()).indexOf('/') >= 0);
      }
    }
  }

  @Test
  public void aPrefixNamingNoHostIsRefused() {
    try {
      configWith("https://");
      org.junit.Assert.fail("a prefix with no host must be refused at boot");
    } catch (ConfigException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("names no host"));
    }
  }

  /** the rules that were already right, kept under test beside the one that was not */
  @Test
  public void theOtherRefusalsStillHold() throws Exception {
    McpConfig config = configWith("https://connector.example.com");

    assertFalse("http would put a code on the wire in clear",
        config.allowsRedirect("http://connector.example.com/cb"));
    assertFalse("traversal is refused outright",
        config.allowsRedirect("https://connector.example.com/../../cb"));
    assertFalse(config.allowsRedirect(null));
    assertFalse(config.allowsRedirect("https://somewhere.else/cb"));
  }
}
