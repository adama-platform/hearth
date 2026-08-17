package io.hearth.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LoginSecurityTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static LoginSecurity of(String json) throws Exception {
    return new LoginSecurity(new ConfigObject((ObjectNode) MAPPER.readTree(json), "login_security"));
  }

  private static void refuses(String json, String expected) throws Exception {
    try {
      of(json);
      fail("expected a refusal mentioning: " + expected);
    } catch (ConfigException ex) {
      assertTrue("wanted '" + expected + "' in: " + ex.getMessage(), ex.getMessage().contains(expected));
    }
  }

  @Test
  public void theDefaultsAreForAHighTrustCommunity() throws Exception {
    LoginSecurity security = of("{}");
    // no password to leak, and nobody gets logged out for no reason
    assertEquals(LoginSecurity.Mode.passwordless, security.mode);
    assertFalse(security.usesPasswords());
    assertEquals(0, security.sessionLifetimeSeconds);
    assertEquals(0, security.sessionIdleSeconds);
    assertEquals(0, security.maxActiveSessions);
    // the cache is sized for the scale this server targets
    assertEquals(1000, security.cacheMaxSessions);
    assertEquals(3600, security.cacheTtlSeconds);
    assertEquals(6, security.codeLength);
    assertEquals(600, security.codeLifetimeSeconds);
    assertEquals("hearth_session", security.cookieName);
    assertEquals("Lax", security.cookieSameSite);
    // developer HTTP has no TLS, so a Secure cookie would be silently dropped
    assertFalse(security.cookieSecure);
  }

  @Test
  public void aBusinessCanTightenEveryKnob() throws Exception {
    LoginSecurity security = of("{\"mode\":\"password_and_code\",\"session-lifetime-seconds\":28800,"
        + "\"session-idle-seconds\":1800,\"max-active-sessions\":4,"
        + "\"max-active-sessions-grace-seconds\":1800,\"lockout-threshold\":5,"
        + "\"password-min-length\":16,\"cookie-secure\":true,\"cookie-same-site\":\"Strict\"}");
    assertEquals(LoginSecurity.Mode.password_and_code, security.mode);
    assertTrue(security.usesPasswords());
    assertTrue(security.requiresSecondFactor());
    assertEquals(28800, security.sessionLifetimeSeconds);
    assertEquals(4, security.maxActiveSessions);
    assertEquals(16, security.passwordMinLength);
    assertTrue(security.cookieSecure);
    assertEquals("Strict", security.cookieSameSite);
  }

  @Test
  public void theStatedPolicyReadsBackAsOneSentence() throws Exception {
    assertEquals("passwordless, sessions never expire", of("{}").describe());
    assertEquals("passwordless, sessions never expire, max 4 older than 1800s",
        of("{\"max-active-sessions\":4}").describe());
    assertTrue(of("{\"mode\":\"password\",\"session-lifetime-seconds\":60}").describe()
        .contains("password, sessions 60s"));
  }

  @Test
  public void anUnknownModeIsRefused() throws Exception {
    refuses("{\"mode\":\"magic\"}", "must be one of passwordless, password, password_and_code");
  }

  @Test
  public void anUnknownKeyIsRefused() throws Exception {
    refuses("{\"sesion-lifetime-seconds\":10}", "unknown key");
  }

  @Test
  public void negativeAndZeroValuesAreRefusedWhereTheyMakeNoSense() throws Exception {
    refuses("{\"session-lifetime-seconds\":-1}", "must be zero or more");
    refuses("{\"session-cache-max\":0}", "must be greater than zero");
    refuses("{\"code-lifetime-seconds\":0}", "must be greater than zero");
    refuses("{\"code-length\":3}", "must be between 4 and 12");
    refuses("{\"code-length\":13}", "must be between 4 and 12");
    refuses("{\"password-min-length\":4}", "must be between 8 and 256");
  }

  @Test
  public void aCookieNameMustBeUsableAsACookieName() throws Exception {
    refuses("{\"cookie-name\":\"has spaces\"}", "cookie-name must be");
    refuses("{\"cookie-name\":\"\"}", "cookie-name must be");
    assertEquals("my_session", of("{\"cookie-name\":\"my_session\"}").cookieName);
  }

  @Test
  public void sameSiteNoneWithoutSecureIsRefused() throws Exception {
    // browsers reject that combination outright, so the login would fail with no visible cause
    refuses("{\"cookie-same-site\":\"None\"}", "requires cookie-secure true");
    assertEquals("None", of("{\"cookie-same-site\":\"None\",\"cookie-secure\":true}").cookieSameSite);
  }

  @Test
  public void anUnknownSameSiteIsRefused() throws Exception {
    refuses("{\"cookie-same-site\":\"Whatever\"}", "must be Lax, Strict or None");
  }
}
