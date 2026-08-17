package io.hearth.analytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * User-Agent strings are a museum of compatibility lies, so the order of the checks is the whole
 * trick. These are real strings, and each one is a browser pretending to be something older.
 */
public class UserAgentsTests {
  private final UserAgents agents = new UserAgents();

  @Test
  public void chromeIsNotSafariEvenThoughItSaysSo() {
    assertEquals("chrome", agents.classify(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));
  }

  @Test
  public void edgeIsNotChromeEvenThoughItSaysSo() {
    assertEquals("edge", agents.classify(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"));
  }

  @Test
  public void operaIsNotChromeEitherEvenThoughItSaysSo() {
    assertEquals("opera", agents.classify(
        "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/119.0.0.0 Safari/537.36 OPR/105.0.0.0"));
  }

  @Test
  public void realSafariIsSafari() {
    assertEquals("safari", agents.classify(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15"));
  }

  @Test
  public void firefoxOnBothPlatforms() {
    assertEquals("firefox", agents.classify("Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"));
    assertEquals("firefox", agents.classify("Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 FxiOS/121.0 Mobile/15E148 Safari/605.1.15"));
  }

  @Test
  public void botsAreCaughtBeforeTheBrowserTheyImpersonate() {
    assertEquals("bot:google", agents.classify(
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) Chrome/120 Safari/537.36"));
    assertEquals("bot:bing", agents.classify("Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)"));
    assertEquals("bot:ai", agents.classify("Mozilla/5.0 (compatible; GPTBot/1.0; +https://openai.com/gptbot)"));
    assertEquals("bot:preview", agents.classify("facebookexternalhit/1.1"));
    assertEquals("bot:other", agents.classify("SomeRandomCrawler/1.0"));
  }

  @Test
  public void toolsAreTools() {
    assertEquals("tool:curl", agents.classify("curl/8.4.0"));
    assertEquals("tool:wget", agents.classify("Wget/1.21.4"));
    assertEquals("tool:python", agents.classify("python-requests/2.31.0"));
    assertEquals("tool:go", agents.classify("Go-http-client/2.0"));
  }

  @Test
  public void nothingAtAll() {
    assertEquals("none", agents.classify(null));
    assertEquals("none", agents.classify(""));
  }

  @Test
  public void anUnknownAgentIsCountedAndRegisteredVerbatim() {
    assertEquals("unknown", agents.classify("SomethingNobodyHasHeardOf/3"));
    assertEquals("unknown", agents.classify("SomethingNobodyHasHeardOf/3"));
    assertEquals(1, agents.unknownCount());
    assertEquals(Long.valueOf(2), agents.unknowns().get("SomethingNobodyHasHeardOf/3"));
  }

  @Test
  public void onlyBrowsersCountAsPeople() {
    assertTrue(UserAgents.isPerson("chrome"));
    assertTrue(UserAgents.isPerson("firefox"));
    assertTrue("an unrecognised browser is still probably a person", UserAgents.isPerson("unknown"));
    assertFalse(UserAgents.isPerson("bot:google"));
    assertFalse(UserAgents.isPerson("tool:curl"));
    assertFalse(UserAgents.isPerson("none"));
  }
}
