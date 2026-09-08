package io.hearth.inbox;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.Mailboxes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turning a message that arrived into one somebody can read.
 *
 * <b>Everything expensive and everything dangerous happens once, here.</b> The MIME tree is walked,
 * the bodies are decoded, the HTML is sanitized, and every part is put in front of {@link Safety}
 * -- and the results are stored. A screen later renders what is in the row and never re-runs any of
 * it. That matters twice over: opening a message costs a query rather than a parse, and the
 * sanitizer runs on a delivery thread where a pathological message costs one message's latency
 * rather than blocking the person trying to read their mail.
 *
 * <b>The raw octets go to disk unchanged.</b> They are what an attachment is re-read from and what
 * "show me the original" hands back. Storing the parts separately as well would double the disk a
 * photograph occupies to save a file read on a click nobody makes twice.
 *
 * <b>A part is listed whether or not it may be downloaded.</b> Silently dropping an attachment is
 * how somebody misses a contract and never learns there was one; the manifest carries the name, the
 * size and the sentence saying why it is refused.
 */
public class Delivery {
  /**
   * Where the message id goes in an inline image URL before there is one.
   *
   * The HTML has to be sanitized before the row is written -- it is one of the columns -- and the
   * id only exists once the row is written. So the URLs are built with this in them and it is
   * substituted afterwards, in the one extra update that happens only for a message that actually
   * carries an inline image.
   */
  static final String ID_MARKER = "__hearth_mid__";

  private final MessageFiles files;
  private final Verbose verbose;
  private final Notifier notifier;

  /** what happens once a message is stored; the seam a test replaces */
  public interface Notifier {
    void arrived(Accounts accounts, long userId, long messageId, String from, String selfUrl);
  }

  /**
   * The account space arrives per call, not per instance.
   *
   * One box serves several domains and each has its own database; a Delivery holding one of them
   * would put every domain's mail in the first domain's tables. There is one of these and the
   * caller says whose mail this is.
   */
  public Delivery(MessageFiles files, Notifier notifier, Verbose verbose) {
    this.files = files;
    this.notifier = notifier;
    this.verbose = verbose;
  }

  /** what happened, so the mail log can say it and the SMTP session can answer */
  public record Stored(long id, String problem) {
    public boolean ok() {
      return problem == null;
    }
  }

  /**
   * Store one message for one mailbox.
   *
   * The mailbox has to have an owner. An address nobody owns is a place with no person behind it,
   * and a message delivered there would sit in a table no screen lists -- so that is a refusal
   * rather than a silent drop, and the rule screen says which addresses are in that state.
   */
  public Stored deliver(Accounts accounts, Envelope envelope, Mailboxes.Box box,
                        String selfUrl) {
    if (box.userId() == null) {
      return new Stored(0, "nobody owns " + box.address() + ", so there is nowhere to put this");
    }
    byte[] raw = envelope.data();
    try {
      MimeTree.Message parsed = MimeTree.parse(raw);
      Bodies bodies = bodiesOf(parsed);
      Listed listed = listOf(parsed);

      // The HTML is sanitized against the parts that survived, so an inline image points at a URL
      // this server serves and everything else is removed and counted.
      MailHtml.Cleaned cleaned = MailHtml.clean(bodies.html,
          inlineUrls(listed.inlineUrls, selfUrl));
      String text = bodies.text.isBlank() && !cleaned.html().isBlank()
          ? MailHtml.toText(cleaned.html()) : bodies.text;

      Messages.Draft draft = new Messages.Draft()
          .to(box.userId(), box.id(), envelope.domain(), box.address())
          .envelope(envelope.from())
          .sender(displayName(parsed.header("from")), addressIn(parsed.header("from")))
          .recipients(parsed.header("to"), parsed.header("cc"),
              addressIn(parsed.header("reply-to")))
          .about(parsed.header("subject"), parsed.header("message-id"),
              parsed.header("in-reply-to"), parsed.header("references"))
          .checks(envelope.checks().spf().name(), envelope.checks().dkim().name(),
              envelope.checks().dmarc().name())
          .size(raw == null ? 0 : raw.length)
          .body(text, cleaned.html(), cleaned.blockedRemote())
          .parts(listed.attachments, listed.hasCalendar);

      long id = accounts.inbox.save(draft);
      if (id <= 0) {
        return new Stored(0, "the message could not be stored");
      }
      // The row first, then the file.
      //
      // A row with no file is a message that reads fine and cannot hand back its attachments, which
      // says so on the screen. A file with no row is invisible and never cleaned up. Of the two
      // half-states this is the one somebody can see and act on.
      try {
        files.write(id, raw);
      } catch (java.io.IOException ex) {
        verbose.detail(() -> "inbox: " + id + " stored without its original -- " + ex.getMessage());
      }
      // now that there is an id, the inline images can point at something real
      if (!listed.inlineUrls.isEmpty() && cleaned.html().contains(ID_MARKER)) {
        accounts.inbox.rewriteHtml(id, box.userId(),
            cleaned.html().replace(ID_MARKER, String.valueOf(id)));
      }
      importCalendar(accounts, parsed, box.userId(), id);
      notifier.arrived(accounts, box.userId(), id, displayOr(parsed.header("from")), selfUrl);
      verbose.detail(() -> "inbox: " + box.address() + " <- " + envelope.from());
      return new Stored(id, null);
    } catch (java.sql.SQLException ex) {
      // temporary as far as the sender is concerned: our database, our problem, and the message
      // should come back rather than bounce to somebody who did nothing wrong
      verbose.detail(() -> "inbox: could not store a message -- " + ex.getMessage());
      return new Stored(0, "this server could not store the message");
    } catch (RuntimeException ex) {
      // A parser that throws must not eat the mail.
      //
      // Every piece of MimeTree is written to degrade rather than fail, and this is the backstop
      // for the case that proves one of them wrong: the message is stored with no bodies and its
      // original on disk, which is worse than reading it properly and far better than a bounce.
      verbose.detail(() -> "inbox: a message would not parse -- " + ex);
      return storeUnparseable(accounts, envelope, box, raw);
    }
  }

  /**
   * A message that would not come apart, kept anyway.
   *
   * Whoever sent it deserves to have it arrive, and the person it is for deserves to know it did.
   * What they get is the envelope, the subject if the top-level headers gave one, and the original
   * to download.
   */
  private Stored storeUnparseable(Accounts accounts, Envelope envelope,
                                  Mailboxes.Box box, byte[] raw) {
    try {
      Messages.Draft draft = new Messages.Draft()
          .to(box.userId(), box.id(), envelope.domain(), box.address())
          .envelope(envelope.from())
          .sender("", addressIn(envelope.header("from")))
          .about(envelope.subject(), envelope.header("message-id"), "", "")
          .checks(envelope.checks().spf().name(), envelope.checks().dkim().name(),
              envelope.checks().dmarc().name())
          .size(raw == null ? 0 : raw.length)
          .body("This message could not be read by this server. The original is below.", "", 0);
      long id = accounts.inbox.save(draft);
      if (id > 0) {
        try {
          files.write(id, raw);
        } catch (java.io.IOException ex) {
          verbose.detail("inbox: and its original could not be written either");
        }
      }
      return new Stored(id, null);
    } catch (java.sql.SQLException ex) {
      return new Stored(0, "this server could not store the message");
    }
  }

  // ---- pulling a message apart -------------------------------------------------------------------

  private record Bodies(String text, String html) {
  }

  /**
   * The body somebody meant you to see.
   *
   * <b>The last alternative wins, which is the rule and reads backwards.</b> `multipart/alternative`
   * is ordered worst-first: the plain-text fallback comes before the HTML, because a reader that
   * stops at the first part it understands should stop at the simplest one. So the HTML found
   * later replaces the HTML found earlier, and both halves are kept -- the text for search and
   * previews, the HTML for reading.
   *
   * A part with a filename is never a body however it is typed. A `.txt` attachment is a document,
   * and treating it as the message replaces what somebody wrote with the contents of a log file
   * they attached.
   */
  private static Bodies bodiesOf(MimeTree.Message parsed) {
    StringBuilder text = new StringBuilder();
    String html = "";
    for (MimeTree.Part part : parsed.leaves()) {
      if (part.isAttachment() || part.isCalendar()) {
        continue;
      }
      if (part.contentType().equals("text/html")) {
        html = part.text();
      } else if (part.contentType().startsWith("text/")) {
        if (text.length() > 0) {
          text.append("\n\n");
        }
        text.append(part.text());
      }
    }
    return new Bodies(text.toString().strip(), html);
  }

  private record Listed(List<Messages.Attachment> attachments, Map<String, String> inlineUrls,
                        boolean hasCalendar) {
  }

  /**
   * Every part worth naming, judged, and the URLs for the inline images among them.
   *
   * The inline map is keyed by Content-ID because that is what an HTML body refers to them by, and
   * only parts that passed carry a URL -- so an image that failed its check is not reachable from
   * the markup any more than it is from the list.
   */
  private Listed listOf(MimeTree.Message parsed) {
    ArrayList<Messages.Attachment> listed = new ArrayList<>();
    LinkedHashMap<String, String> inline = new LinkedHashMap<>();
    boolean calendar = false;
    for (MimeTree.Part part : parsed.leaves()) {
      if (part.isCalendar()) {
        calendar = true;
      }
      boolean inlineImage = part.contentId() != null && part.contentType().startsWith("image/");
      if (!part.isAttachment() && !inlineImage && !part.isCalendar()) {
        continue;
      }
      String name = Safety.safeName(part.filename(), defaultName(part));
      Safety.Verdict verdict = Safety.check(name, part.content());
      listed.add(new Messages.Attachment(part.path(), name,
          verdict.allowed() ? verdict.contentType() : part.contentType(), part.size(),
          verdict.allowed(), verdict.reason(), verdict.width(), verdict.height()));
      if (inlineImage && verdict.allowed()) {
        inline.put(part.contentId(), part.path());
      }
    }
    return new Listed(listed, inline, calendar);
  }

  /**
   * Content-ID to a URL on this server, with the id still to be filled in.
   *
   * Built here rather than in the sanitizer so that the sanitizer knows nothing about this
   * server's URL space -- it is handed a map and rewrites what is in it.
   */
  private static Map<String, String> inlineUrls(Map<String, String> paths, String selfUrl) {
    LinkedHashMap<String, String> urls = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : paths.entrySet()) {
      urls.put(entry.getKey(),
          selfUrl + "/mail/part?m=" + ID_MARKER + "&p=" + entry.getValue());
    }
    return urls;
  }

  /**
   * An invitation that came with the message goes into the calendar.
   *
   * <b>Imported, not applied.</b> The event lands with `NEEDS-ACTION` against this person's name --
   * it is in the calendar so it can be seen and answered, and the answer is a thing they do. A
   * mail reader that silently accepts every invitation it receives fills somebody's week with
   * meetings they never agreed to.
   *
   * A CANCEL is honoured without being answered, because a cancellation is not a question.
   */
  private void importCalendar(Accounts accounts, MimeTree.Message parsed, long userId,
                              long messageId) {
    for (MimeTree.Part part : parsed.leaves()) {
      if (!part.isCalendar()) {
        continue;
      }
      try {
        io.hearth.calendar.IcsFile.Calendar calendar =
            io.hearth.calendar.IcsFile.read(part.text(), accounts.zone());
        for (io.hearth.calendar.IcsFile.Event event : calendar.events()) {
          io.hearth.calendar.IcsFile.Event landing = calendar.isCancel()
              ? new io.hearth.calendar.IcsFile.Event(event.uid(), event.sequence(),
                  event.summary(), event.description(), event.location(), event.startsAt(),
                  event.endsAt(), event.allDay(), event.rrule(), event.exdates(), "CANCELLED",
                  event.organizer(), event.attendees())
              : event;
          accounts.events.merge(userId, landing, "invitation", messageId, null);
        }
      } catch (java.sql.SQLException ex) {
        // the message is stored and readable; a calendar that would not import is a line in the
        // narration rather than a reason to fail the delivery
        verbose.detail(() -> "inbox: an invitation would not import -- " + ex.getMessage());
      } catch (RuntimeException ex) {
        verbose.detail(() -> "inbox: an invitation would not parse -- " + ex);
      }
    }
  }

  /**
   * A name for a part that did not carry one.
   *
   * It has to end in something, because {@link Safety} decides on the extension and a part with no
   * name would be refused for having none -- which is right for an unnamed blob and wrong for an
   * inline photograph that simply had no `filename`. So the content type provides the extension it
   * claims, and the bytes still have to agree with it.
   */
  private static String defaultName(MimeTree.Part part) {
    String type = part.contentType();
    String extension = switch (type) {
      case "image/png" -> "png";
      case "image/jpeg", "image/jpg" -> "jpg";
      case "image/gif" -> "gif";
      case "image/webp" -> "webp";
      case "application/pdf" -> "pdf";
      case "text/calendar" -> "ics";
      case "text/plain" -> "txt";
      default -> "";
    };
    String stem = "part-" + part.path().replace('.', '-');
    return extension.isEmpty() ? stem : stem + "." + extension;
  }

  // ---- headers -----------------------------------------------------------------------------------

  /**
   * The address in a From or Reply-To header.
   *
   * The <b>last</b> angle-bracketed one, for the reason `SenderCheck` gives: a display name that
   * looks like an address is a real trick, and reading the first thing that looks like one shows a
   * person the wrong sender.
   */
  public static String addressIn(String header) {
    if (header == null || header.isBlank()) {
      return "";
    }
    String value = header.replaceAll("\r?\n[ \t]+", " ").trim();
    int open = value.lastIndexOf('<');
    int close = value.lastIndexOf('>');
    String address = open >= 0 && close > open ? value.substring(open + 1, close) : value;
    address = address.trim();
    return address.indexOf('@') > 0 ? address.toLowerCase(Locale.ROOT) : "";
  }

  /** the words before the angle brackets, unquoted */
  public static String displayName(String header) {
    if (header == null || header.isBlank()) {
      return "";
    }
    String value = header.replaceAll("\r?\n[ \t]+", " ").trim();
    int open = value.lastIndexOf('<');
    if (open < 0) {
      return "";
    }
    String name = value.substring(0, open).trim();
    if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
      name = name.substring(1, name.length() - 1);
    }
    return name.replace("\\\"", "\"").trim();
  }

  /** what to call the sender in a notification: their name if they gave one, else their address */
  static String displayOr(String header) {
    String name = displayName(header);
    return name.isBlank() ? addressIn(header) : name;
  }
}
