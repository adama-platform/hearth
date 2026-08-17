package io.hearth.analytics;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.TreeMap;

/**
 * Turning a User-Agent string into something countable.
 *
 * User-Agent strings are a museum of compatibility lies -- Chrome claims to be Safari, which claims
 * to be KHTML, which claims to be Gecko -- so the order of these checks matters and is the whole
 * trick. Edge before Chrome, Chrome before Safari, and so on down the chain of things pretending to
 * be each other.
 *
 * Anything unrecognised is counted under "unknown" AND recorded verbatim, so an operator looking at
 * a spike can see what the actual string was and decide whether it deserves a rule. A classifier
 * that silently bucketed everything it did not know would hide exactly the traffic worth looking at.
 */
public class UserAgents {
  private static final int MAX_UNKNOWNS = 200;

  private final ConcurrentHashMap<String, AtomicLong> unknowns = new ConcurrentHashMap<>();

  /** the family this agent belongs to */
  public String classify(String raw) {
    if (raw == null || raw.isBlank()) {
      return "none";
    }
    String ua = raw.toLowerCase(Locale.ROOT);

    // bots first: many of them also claim to be a browser further along the string
    if (ua.contains("googlebot")) return "bot:google";
    if (ua.contains("bingbot")) return "bot:bing";
    if (ua.contains("duckduckbot")) return "bot:duckduckgo";
    if (ua.contains("yandexbot")) return "bot:yandex";
    if (ua.contains("baiduspider")) return "bot:baidu";
    if (ua.contains("applebot")) return "bot:apple";
    if (ua.contains("ahrefsbot") || ua.contains("semrushbot") || ua.contains("mj12bot")) return "bot:seo";
    if (ua.contains("gptbot") || ua.contains("claudebot") || ua.contains("ccbot")
        || ua.contains("perplexitybot") || ua.contains("bytespider")) return "bot:ai";
    if (ua.contains("facebookexternalhit") || ua.contains("twitterbot") || ua.contains("slackbot")
        || ua.contains("discordbot") || ua.contains("whatsapp") || ua.contains("telegrambot")) return "bot:preview";
    if (ua.contains("uptimerobot") || ua.contains("pingdom") || ua.contains("statuscake")) return "bot:monitor";
    if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")) return "bot:other";

    // tools, which are usually a person doing something deliberate
    if (ua.startsWith("curl/")) return "tool:curl";
    if (ua.startsWith("wget/")) return "tool:wget";
    if (ua.contains("python-requests") || ua.contains("python-urllib") || ua.contains("httpx")) return "tool:python";
    if (ua.contains("java/") || ua.contains("okhttp") || ua.contains("apache-httpclient")) return "tool:java";
    if (ua.contains("postman")) return "tool:postman";
    if (ua.contains("go-http-client")) return "tool:go";

    // browsers, most-specific first because each pretends to be the next
    if (ua.contains("edg/") || ua.contains("edge/")) return "edge";
    if (ua.contains("opr/") || ua.contains("opera")) return "opera";
    if (ua.contains("samsungbrowser")) return "samsung";
    if (ua.contains("vivaldi")) return "vivaldi";
    if (ua.contains("brave")) return "brave";
    if (ua.contains("firefox/") || ua.contains("fxios")) return "firefox";
    if (ua.contains("chrome/") || ua.contains("crios")) return "chrome";
    if (ua.contains("safari/")) return "safari";
    if (ua.contains("msie") || ua.contains("trident/")) return "ie";

    register(raw);
    return "unknown";
  }

  /** is this family a browser a person is looking at? */
  public static boolean isPerson(String family) {
    return !family.startsWith("bot:") && !family.startsWith("tool:") && !family.equals("none");
  }

  private void register(String raw) {
    if (unknowns.size() >= MAX_UNKNOWNS && !unknowns.containsKey(raw)) {
      return; // bounded; a flood of distinct junk strings must not become a memory leak
    }
    String trimmed = raw.length() > 200 ? raw.substring(0, 200) : raw;
    unknowns.computeIfAbsent(trimmed, key -> new AtomicLong()).incrementAndGet();
  }

  /** the unrecognised strings and how often each showed up, so rules can be added deliberately */
  public Map<String, Long> unknowns() {
    TreeMap<String, Long> out = new TreeMap<>();
    for (Map.Entry<String, AtomicLong> entry : unknowns.entrySet()) {
      out.put(entry.getKey(), entry.getValue().get());
    }
    return out;
  }

  public int unknownCount() {
    return unknowns.size();
  }
}
