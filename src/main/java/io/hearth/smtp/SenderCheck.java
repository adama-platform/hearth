package io.hearth.smtp;

import io.hearth.common.Verbose;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * All three checks, and what to do about them.
 *
 * SPF, DKIM and DMARC each answer a different question and none of them is "is this message
 * legitimate". Run together they answer the one question worth asking: *does the domain in the
 * From header, which is what a person reads, vouch for this message?*
 *
 * <b>Enforcement is the domain owner's decision, not ours.</b> A message that fails DMARC where the
 * owner published `p=reject` is one they have explicitly asked the world to refuse, so it is
 * refused. Everything else is annotated and delivered, because the alternative -- refusing on SPF
 * failure alone -- rejects every message that came through a mailing list, and a community whose
 * mail silently stops working is worse off than one receiving the occasional forgery it can see is
 * a forgery.
 *
 * Every message gets an `Authentication-Results` header whatever happens. The checks are not only
 * for refusing: whatever handles the mail later should be able to see what was known when it
 * arrived rather than re-deriving it from a message that has since been stored.
 */
public class SenderCheck {
  private final SmtpConfig config;
  private final Verbose verbose;

  public SenderCheck(SmtpConfig config, Verbose verbose) {
    this.config = config;
    this.verbose = verbose;
  }

  /** what was found, and whether the message should still be delivered */
  public record Judgement(AuthResult result, boolean deliver, boolean temporary, String reason) {
  }

  public Judgement judge(InetAddress client, String mailFrom, String helo, byte[] message,
                         SmtpDns dns) {
    if (!config.checkSenders) {
      return new Judgement(AuthResult.nothingChecked(), true, false, "sender checks are off");
    }
    try {
      String envelopeDomain = SmtpRouting.domainOf(mailFrom);
      AuthResult.Status spf = Spf.check(client, envelopeDomain, helo, dns);

      Dkim.Verified dkim = Dkim.verify(message, dns);

      // DMARC aligns against the *header* From, which is the thing a person sees and the only
      // identity worth protecting. The envelope is what SPF authenticated and nobody reads.
      String fromDomain = headerFromDomain(message);
      Dmarc.Policy dmarc = Dmarc.check(fromDomain, spf, envelopeDomain == null ? helo : envelopeDomain,
          dkim.status(), dkim.domain(), dns);

      AuthResult result = new AuthResult(spf, envelopeDomain, dkim.status(), dkim.domain(),
          dmarc.status(), dmarc.domain(), dmarc.policy());
      verbose.detail(() -> "smtp: " + result.summary() + " for " + mailFrom);

      if (config.enforceDmarc && result.dmarcSaysReject()) {
        // the domain owner published p=reject. Refusing is doing what they asked, and delivering
        // anyway would make their policy meaningless everywhere it is honoured.
        return new Judgement(result, false, false,
            "the domain " + result.dmarcDomain() + " publishes DMARC p=reject and this failed");
      }
      return new Judgement(result, true, false, result.summary());
    } catch (Exception ex) {
      // A check that breaks must not eat the mail. Deliver it and say the checks did not run --
      // an outage in our validation is our problem, not the sender's.
      verbose.detail(() -> "smtp: sender checks failed to run -- " + ex.getMessage());
      return new Judgement(AuthResult.nothingChecked(), true, false, "checks could not run");
    }
  }

  /**
   * The domain in the From header, which is the identity DMARC is about.
   *
   * Deliberately takes the *last* angle-bracketed address, because `From: "a@evil.example"
   * &lt;real@example.org&gt;` is a real trick: a display name that looks like an address. Reading
   * the first thing that looks like one authenticates the wrong domain.
   */
  static String headerFromDomain(byte[] message) {
    if (message == null) {
      return null;
    }
    String text = new String(message, StandardCharsets.UTF_8);
    int end = text.indexOf("\r\n\r\n");
    if (end < 0) {
      end = text.indexOf("\n\n");
    }
    String headers = end < 0 ? text : text.substring(0, end);
    for (String[] header : Dkim.parseHeaders(headers)) {
      if (!header[0].trim().equalsIgnoreCase("from")) {
        continue;
      }
      String value = header[1].replaceAll("\r?\n[ \t]+", " ").trim();
      int open = value.lastIndexOf('<');
      int close = value.lastIndexOf('>');
      String address = open >= 0 && close > open ? value.substring(open + 1, close) : value;
      int at = address.lastIndexOf('@');
      if (at > 0 && at < address.length() - 1) {
        return address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
      }
      return null;
    }
    return null;
  }

  /**
   * Put the results on the front of the message.
   *
   * Prepended rather than appended: a header added at the top is where every mail system puts its
   * own findings, and a reader takes the topmost as the most recent hop. It also means anything
   * downstream reads ours before any the sender may have forged further down.
   */
  public static byte[] stamp(byte[] message, AuthResult result, String hostname) {
    byte[] header = (result.toHeader(hostname) + "\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[header.length + (message == null ? 0 : message.length)];
    System.arraycopy(header, 0, out, 0, header.length);
    if (message != null) {
      System.arraycopy(message, 0, out, header.length, message.length);
    }
    return out;
  }
}
