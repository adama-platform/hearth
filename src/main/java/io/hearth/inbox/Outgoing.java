package io.hearth.inbox;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A message this server writes, in the shape RFC 5322 asks for.
 *
 * <b>Plain text, and that is a decision rather than a shortcut.</b> An HTML reply has to be
 * generated, sanitized on the way out, and paired with a plain half anyway -- three more places to
 * be wrong, in exchange for bold text. Plain text renders in every client that has ever existed,
 * survives every gateway, quotes cleanly, and cannot carry a tracking pixel back to somebody. For a
 * reader whose whole design is "answer it and move on", it is also the right shape of reply.
 *
 * <b>Base64 for the body, always.</b> Not because it is prettier -- it is not -- but because the
 * alternative is deciding whether the text is seven-bit clean and getting it wrong once. A reply
 * containing an em dash, an accent or an emoji sent as 8-bit through a gateway that is not
 * 8BITMIME arrives as mojibake, and base64 is the encoding that never has that problem.
 *
 * <b>The From address is the address the original arrived at.</b> That is the whole point of
 * tracking `delivered_to`: somebody who wrote to `receipts@` gets a reply from `receipts@`, so the
 * conversation continues from the address they already know -- and the signature and the SPF record
 * both align with it, because it is a real address at a domain this server signs for.
 */
public final class Outgoing {
  private static final DateTimeFormatter RFC5322 =
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
  /** how much of the original a reply quotes back; a whole newsletter helps nobody */
  public static final int QUOTED_CHARS = 8000;
  private static final String CRLF = "\r\n";

  private Outgoing() {
  }

  /** a message ready to hand to the relay */
  public record Written(String messageId, List<String> recipients, byte[] bytes) {
  }

  /** who a reply goes to, worked out from the message being replied to */
  public record Audience(String from, List<String> to, List<String> cc) {
    public boolean any() {
      return !to.isEmpty() || !cc.isEmpty();
    }

    public String describe() {
      StringBuilder out = new StringBuilder(String.join(", ", to));
      if (!cc.isEmpty()) {
        out.append(" and ").append(cc.size()).append(" more");
      }
      return out.toString();
    }
  }

  /**
   * Who a reply is addressed to.
   *
   * <b>Reply-all is the default and this is where that lives.</b> The people on the original are
   * there because the sender put them there, and quietly dropping them is how half a conversation
   * happens twice. Reply-to-one is a deliberate narrowing, so it is the button somebody presses
   * rather than the one they forget.
   *
   * <b>Every address of our own comes out</b>, whichever mailbox it was -- replying to yourself
   * puts a copy in your own inbox, which in a reader whose whole point is reaching zero is worse
   * than a nuisance. `Reply-To` wins over `From` when the sender set one, because that is what it
   * is for.
   */
  public static Audience audienceFor(Messages.Record message, Set<String> mine, boolean all) {
    String answerTo = message.replyTo() == null || message.replyTo().isBlank()
        ? message.fromAddress() : message.replyTo();
    LinkedHashSet<String> to = new LinkedHashSet<>();
    if (answerTo != null && !answerTo.isBlank()) {
      to.add(answerTo.toLowerCase(Locale.ROOT));
    }
    LinkedHashSet<String> cc = new LinkedHashSet<>();
    if (all) {
      for (String address : addressesIn(message.toHeader())) {
        cc.add(address);
      }
      for (String address : addressesIn(message.ccHeader())) {
        cc.add(address);
      }
    }
    Set<String> ours = new LinkedHashSet<>();
    for (String one : mine) {
      ours.add(one.toLowerCase(Locale.ROOT));
    }
    to.removeAll(ours);
    cc.removeAll(ours);
    cc.removeAll(to);
    if (to.isEmpty() && !cc.isEmpty()) {
      // everybody we would have written to was one of ours; promote the rest rather than sending a
      // message with an empty To header, which some receivers refuse outright
      String first = cc.iterator().next();
      cc.remove(first);
      to.add(first);
    }
    return new Audience(message.deliveredTo(), List.copyOf(to), List.copyOf(cc));
  }

  /**
   * Every address in a header that may list several.
   *
   * Deliberately simple: split on commas outside angle brackets and quotes, then take the address
   * out of each. A full RFC 5322 group-syntax parser buys nothing here -- what is being built is a
   * Cc list, and an address this cannot read is one that does not get copied rather than one that
   * goes somewhere wrong.
   */
  public static List<String> addressesIn(String header) {
    ArrayList<String> out = new ArrayList<>();
    if (header == null || header.isBlank()) {
      return out;
    }
    boolean quoted = false;
    boolean angled = false;
    int start = 0;
    String value = header.replaceAll("\r?\n[ \t]+", " ");
    for (int k = 0; k <= value.length(); k++) {
      char ch = k < value.length() ? value.charAt(k) : ',';
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == '<') {
        angled = true;
      } else if (ch == '>') {
        angled = false;
      } else if (ch == ',' && !quoted && !angled) {
        String address = Delivery.addressIn(value.substring(start, k));
        if (!address.isEmpty() && !out.contains(address)) {
          out.add(address);
        }
        start = k + 1;
      }
    }
    return out;
  }

  /**
   * Build the reply.
   *
   * `In-Reply-To` and `References` are what make this a thread in everybody else's client rather
   * than a new conversation with a similar subject. References is the original's chain plus the
   * original's own id -- the whole ancestry, which is what a client walks to draw the tree.
   */
  public static Written reply(Messages.Record original, Audience audience, String fromName,
                              String bodyText, ZoneId zone) {
    String subject = original.subject() == null ? "" : original.subject().trim();
    if (!subject.toLowerCase(Locale.ROOT).startsWith("re:")) {
      subject = "Re: " + subject;
    }
    String references = (original.references() == null ? "" : original.references().trim());
    String parent = original.messageId() == null ? "" : original.messageId().trim();
    if (!parent.isEmpty()) {
      references = references.isEmpty() ? parent : references + " " + parent;
    }

    ArrayList<String> headers = new ArrayList<>();
    String messageId = newMessageId(audience.from());
    headers.add("Message-ID: " + messageId);
    headers.add("Date: " + ZonedDateTime.now(zone).format(RFC5322));
    headers.add("From: " + address(fromName, audience.from()));
    headers.add("To: " + String.join(", ", audience.to()));
    if (!audience.cc().isEmpty()) {
      headers.add("Cc: " + String.join(", ", audience.cc()));
    }
    headers.add("Subject: " + encodeWord(subject));
    if (!parent.isEmpty()) {
      headers.add("In-Reply-To: " + parent);
    }
    if (!references.isEmpty()) {
      headers.add("References: " + fold(references));
    }
    headers.add("MIME-Version: 1.0");
    headers.add("Content-Type: text/plain; charset=utf-8");
    headers.add("Content-Transfer-Encoding: base64");
    // so a mail system that decides to bounce this does not mail the bounce to a list
    headers.add("Auto-Submitted: no");

    String body = bodyText.stripTrailing() + "\n\n" + quote(original, zone);
    return write(messageId, headers, body, allOf(audience));
  }

  /** a message that is not a reply to anything: same shape, no thread headers */
  public static Written fresh(String from, String fromName, List<String> to, List<String> cc,
                              String subject, String bodyText, ZoneId zone) {
    ArrayList<String> headers = new ArrayList<>();
    String messageId = newMessageId(from);
    headers.add("Message-ID: " + messageId);
    headers.add("Date: " + ZonedDateTime.now(zone).format(RFC5322));
    headers.add("From: " + address(fromName, from));
    headers.add("To: " + String.join(", ", to));
    if (!cc.isEmpty()) {
      headers.add("Cc: " + String.join(", ", cc));
    }
    headers.add("Subject: " + encodeWord(subject));
    headers.add("MIME-Version: 1.0");
    headers.add("Content-Type: text/plain; charset=utf-8");
    headers.add("Content-Transfer-Encoding: base64");
    ArrayList<String> everybody = new ArrayList<>(to);
    everybody.addAll(cc);
    return write(messageId, headers, bodyText, everybody);
  }

  private static List<String> allOf(Audience audience) {
    ArrayList<String> everybody = new ArrayList<>(audience.to());
    everybody.addAll(audience.cc());
    return everybody;
  }

  private static Written write(String messageId, List<String> headers, String body,
                               List<String> recipients) {
    StringBuilder out = new StringBuilder();
    for (String header : headers) {
      out.append(header).append(CRLF);
    }
    out.append(CRLF);
    // 76 characters a line, which is what every base64 body in every mail system uses
    out.append(Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII))
        .encodeToString(body.getBytes(StandardCharsets.UTF_8)));
    out.append(CRLF);
    return new Written(messageId, List.copyOf(recipients),
        out.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The attribution line and the original, quoted.
   *
   * One `>` per line and a bounded amount of it. Quoting a whole thread back at somebody is how a
   * five-message conversation becomes a document, and cutting it is visible rather than silent.
   */
  static String quote(Messages.Record original, ZoneId zone) {
    String when = original.receivedAt() == null ? "" : ZonedDateTime
        .ofInstant(original.receivedAt().toInstant(), zone)
        .format(DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.US));
    String who = original.who();
    StringBuilder out = new StringBuilder();
    out.append("On ").append(when).append(", ").append(who).append(" wrote:\n");
    String text = original.textBody() == null || original.textBody().isBlank()
        ? MailHtml.toText(original.htmlBody()) : original.textBody();
    boolean cut = text.length() > QUOTED_CHARS;
    if (cut) {
      text = text.substring(0, QUOTED_CHARS);
    }
    for (String line : text.split("\r?\n")) {
      out.append("> ").append(line).append('\n');
    }
    if (cut) {
      out.append("> [the rest of this message is not quoted]\n");
    }
    return out.toString();
  }

  // ---- headers -----------------------------------------------------------------------------------

  /**
   * `Name <address>`, with the name quoted when it needs to be.
   *
   * A display name carrying a comma or an angle bracket rewrites the header it is in -- somebody
   * called `Smith, John` produces two recipients if it is not quoted, and a name containing `<>`
   * can produce a different address entirely.
   */
  static String address(String name, String address) {
    if (name == null || name.isBlank()) {
      return address;
    }
    String clean = name.replace('\r', ' ').replace('\n', ' ').trim();
    if (clean.length() > 100) {
      clean = clean.substring(0, 100);
    }
    if (!clean.chars().allMatch(ch -> ch < 128)) {
      return encodeWord(clean) + " <" + address + ">";
    }
    if (clean.matches("[\\w .'-]+")) {
      return clean + " <" + address + ">";
    }
    return "\"" + clean.replace("\\", "").replace("\"", "'") + "\" <" + address + ">";
  }

  /**
   * A header value with anything outside ASCII wrapped in an encoded word.
   *
   * A raw UTF-8 subject is legal nowhere a receiver is obliged to accept it, and the failure is a
   * subject line of question marks. Pure ASCII goes through untouched, which is most of them.
   */
  static String encodeWord(String value) {
    String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    if (clean.isEmpty()) {
      return "";
    }
    if (clean.chars().allMatch(ch -> ch >= 32 && ch < 127)) {
      return clean;
    }
    // one word rather than several: the 75-character limit is a should, every client handles a
    // long one, and splitting UTF-8 across two encoded words is how a character gets cut in half
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
  }

  /** a References chain wrapped at something a receiver will not rewrap for us */
  static String fold(String value) {
    StringBuilder out = new StringBuilder();
    int line = 12;
    for (String piece : value.trim().split("\\s+")) {
      if (line + piece.length() > 76) {
        out.append(CRLF).append(' ');
        line = 1;
      } else if (out.length() > 0) {
        out.append(' ');
        line++;
      }
      out.append(piece);
      line += piece.length();
    }
    return out.toString();
  }

  /**
   * A Message-ID nobody else will produce.
   *
   * The domain half is ours, which is what makes it globally unique without coordinating with
   * anybody. The random half is from `SecureRandom` rather than a counter: a predictable
   * Message-ID lets somebody guess the id of a message they have not seen, which matters the moment
   * anything threads on it.
   */
  static String newMessageId(String from) {
    byte[] random = new byte[12];
    new java.security.SecureRandom().nextBytes(random);
    String domain = from != null && from.indexOf('@') > 0
        ? from.substring(from.indexOf('@') + 1) : "localhost";
    return "<" + Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        + "@" + domain + ">";
  }
}
