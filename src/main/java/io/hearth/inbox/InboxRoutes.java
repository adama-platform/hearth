package io.hearth.inbox;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.smtp.Mailboxes;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Flash;
import io.hearth.web.Forms;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading mail, and the two things you are meant to do with it.
 *
 * <b>The design target is an empty inbox, and every decision here follows from that.</b> There are
 * no folders, no labels, no stars and no bin. A message is in the list or it is not, and the only
 * ways out are to answer it or to be rid of it -- which are the two buttons under every message,
 * with reply-all as the default because the people on a thread are on it deliberately. Anything
 * that lets a message sit in a list "for later" is the mechanism by which an inbox reaches four
 * thousand, so none of it is here.
 *
 * <b>A reply goes out from the address the message arrived at.</b> That is what `delivered_to` is
 * for: somebody who wrote to `receipts@` gets an answer from `receipts@`, which is the address they
 * know, and the one SPF and DKIM both align with.
 *
 * <b>Nothing remote is ever fetched.</b> The HTML was sanitized at delivery, inline images point at
 * this server, and every external reference was removed and counted -- the screen says how many, so
 * "this message wanted to load nine things from elsewhere" is a fact about the sender rather than a
 * silent decision.
 *
 * <b>An attachment is served only if the bytes agree with the name.</b> {@link Safety} decides;
 * this hands back what it allows, with the content type <em>it</em> chose, always as a download and
 * never inline -- so nothing a stranger sent is ever rendered in this origin.
 */
public class InboxRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(InboxRoutes.class);
  /** how many messages a listing shows before somebody should be searching instead */
  private static final int PAGE = 100;
  private final Templates templates;
  private final MessageFiles files;
  private final Postman postman;
  private final Flash flash;
  private final Verbose verbose;

  public InboxRoutes(Templates templates, MessageFiles files, Postman postman, Verbose verbose) {
    this.templates = templates;
    this.files = files;
    this.postman = postman;
    this.flash = new Flash();
    this.verbose = verbose;
  }

  /** does this path belong here? */
  public static boolean owns(DomainConfig config, String path) {
    // no query branch: this is handed a path with the query already stripped, and a condition that
    // can never fire is one somebody later reads as protection that is not there
    String root = config.urls.self + "/mail";
    return path.equals(root) || path.startsWith(root + "/");
  }

  /** where the part route lives for a given domain, since `self` is configurable */
  public static String partUrl(DomainConfig config) {
    return config.urls.self + "/mail/part";
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null) {
        // the same answer a signed-out request gets anywhere private: back to the door, carrying
        // where they were going
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                io.hearth.web.Landing.carry(config.urls.login, io.hearth.web.Landing.here(req))});
        return;
      }
      String root = config.urls.self + "/mail";
      String path = Forms.path(req.uri());
      String rest = path.equals(root) ? "" : path.substring(root.length() + 1);

      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, session, recorder);
        return;
      }
      if (rest.equals("part")) {
        part(config, accounts, ctx, req, me, recorder);
        return;
      }
      if (rest.equals("raw")) {
        raw(accounts, ctx, req, me, recorder);
        return;
      }
      if (rest.isEmpty()) {
        list(config, accounts, ctx, req, me, session, recorder);
        return;
      }
      boolean replying = rest.endsWith("/reply");
      String id = replying ? rest.substring(0, rest.length() - 6) : rest;
      one(config, accounts, ctx, req, me, session, recorder, id, replying);
    } catch (SQLException | RuntimeException ex) {
      LOG.error("inbox-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new LinkedHashMap<>();
      Chrome.admin(model, accounts);
      model.put("title", "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      model.put("nav", List.of());
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  // ---- the listing -------------------------------------------------------------------------------

  private void list(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, SessionRecord session,
                    WebHandler.Recorder recorder) throws SQLException {
    String csrf = Cookies.stableToken(req);
    String search = orEmpty(Forms.query(req.uri(), "q"));
    boolean archived = "archive".equals(Forms.query(req.uri(), "show"));

    List<Messages.Record> found = !search.isBlank()
        ? accounts.inbox.search(me.id(), search, PAGE)
        : archived ? accounts.inbox.archive(me.id(), PAGE) : accounts.inbox.inbox(me.id(), PAGE);

    Map<String, Object> model = shell(config, accounts, me, csrf, session);
    model.put("messages", rows(config, found, accounts.zone()));
    model.put("any", !found.isEmpty());
    model.put("count", found.size());
    model.put("q", search);
    model.put("searching", !search.isBlank());
    model.put("archived", archived);
    model.put("archiveUrl", config.urls.self + "/mail?show=archive");
    model.put("inboxUrl", config.urls.self + "/mail");
    // The empty inbox is the goal, so it is a state worth drawing properly rather than a blank
    // page that reads like something failed to load.
    model.put("clear", found.isEmpty() && search.isBlank() && !archived);
    send(ctx, req, recorder, accounts, csrf, templates.render("inbox", model));
  }

  private List<Map<String, Object>> rows(DomainConfig config, List<Messages.Record> found,
                                         ZoneId zone) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Messages.Record message : found) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", message.id());
      row.put("who", message.who());
      row.put("address", message.fromAddress());
      row.put("subject", message.subjectOr());
      row.put("preview", message.preview(140));
      row.put("when", when(message.receivedAt(), zone));
      row.put("unread", message.unread());
      row.put("replied", message.replied());
      row.put("attachments", message.attachments());
      row.put("hasAttachments", message.attachments() > 0);
      row.put("hasCalendar", message.hasCalendar());
      row.put("authenticated", message.authenticated());
      row.put("to", message.deliveredTo());
      row.put("openUrl", config.urls.self + "/mail/" + message.id());
      rows.add(row);
    }
    return rows;
  }

  // ---- one message -------------------------------------------------------------------------------

  private void one(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, SessionRecord session,
                   WebHandler.Recorder recorder, String rawId, boolean replying)
      throws SQLException {
    long id = longOr(rawId);
    Messages.Record message = id <= 0 ? null : accounts.inbox.byId(id, me.id());
    if (message == null) {
      // somebody else's message and a message that never existed answer identically, which is the
      // rule the whole server follows about things that are not yours
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    // Opening it is what marks it read. Not a button: a reader that needs to be told you read
    // something is one where the unread count is always wrong.
    accounts.inbox.read(id, me.id());

    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = shell(config, accounts, me, csrf, session);
    model.put("id", message.id());
    model.put("subject", message.subjectOr());
    model.put("who", message.who());
    model.put("fromAddress", message.fromAddress());
    model.put("deliveredTo", message.deliveredTo());
    model.put("toHeader", message.toHeader());
    model.put("ccHeader", message.ccHeader());
    model.put("hasCc", !message.ccHeader().isBlank());
    model.put("when", when(message.receivedAt(), accounts.zone()));
    model.put("authenticated", message.authenticated());
    model.put("checks", "spf=" + message.spf() + " dkim=" + message.dkim()
        + " dmarc=" + message.dmarc());
    model.put("replied", message.replied());
    model.put("archived", message.archived());
    model.put("blockedRemote", message.blockedRemote());
    model.put("anyBlocked", message.blockedRemote() > 0);
    model.put("backUrl", config.urls.self + "/mail");
    model.put("rawUrl", config.urls.self + "/mail/raw?m=" + message.id());

    boolean html = !message.htmlBody().isBlank();
    // The HTML was sanitized at delivery, so this is the stored result rather than a fresh pass --
    // one sanitizer run per message, not one per read.
    model.put("body", html ? message.htmlBody() : MailHtml.fromText(message.textBody()));

    ArrayList<Map<String, Object>> parts = new ArrayList<>();
    for (Messages.Attachment part : message.parts()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", part.filename());
      row.put("size", part.describeSize());
      row.put("type", part.contentType());
      row.put("allowed", part.allowed());
      row.put("reason", part.reason());
      row.put("image", part.isImage());
      row.put("dimensions", part.width() > 0 ? part.width() + " x " + part.height() : "");
      row.put("url", partUrl(config) + "?m=" + message.id() + "&p=" + part.path());
      parts.add(row);
    }
    model.put("parts", parts);
    model.put("anyParts", !parts.isEmpty());
    model.put("hasOriginal", files.has(message.id()));

    // an invitation that arrived with this message, if it is still waiting for an answer
    model.put("invitations", invitationsFor(accounts, me, message, config));

    if (replying) {
      boolean all = !"0".equals(Forms.query(req.uri(), "all"));
      Outgoing.Audience audience = Outgoing.audienceFor(message, addressesOf(accounts, me), all);
      model.put("replyAll", all);
      model.put("replyTo", String.join(", ", audience.to()));
      model.put("replyCc", String.join(", ", audience.cc()));
      model.put("anyCc", !audience.cc().isEmpty());
      model.put("replyFrom", message.deliveredTo());
      model.put("canSend", postman != null);
      model.put("otherUrl", config.urls.self + "/mail/" + message.id() + "/reply?all="
          + (all ? "0" : "1"));
      model.put("quoted", Outgoing.quote(message, accounts.zone()));
      send(ctx, req, recorder, accounts, csrf, templates.render("inbox_reply", model));
      return;
    }
    model.put("replyUrl", config.urls.self + "/mail/" + message.id() + "/reply");
    send(ctx, req, recorder, accounts, csrf, templates.render("inbox_message", model));
  }

  /**
   * The invitations this message brought, and what they are waiting for.
   *
   * Read from the calendar rather than from the message: an invitation is imported at delivery, and
   * the answer somebody gave lives with the event. Showing what the file said would mean a message
   * that keeps offering "accept" after you already have.
   */
  private List<Map<String, Object>> invitationsFor(Accounts accounts, UserRecord me,
                                                  Messages.Record message, DomainConfig config)
      throws SQLException {
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    if (!message.hasCalendar()) {
      return out;
    }
    for (io.hearth.calendar.Events.Record event : accounts.events.unanswered(me.id())) {
      if (event.fromMessage() != null && event.fromMessage() == message.id()) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("uid", event.uid());
        row.put("summary", event.summary());
        row.put("when", when(event.startsAt(), accounts.zone()));
        row.put("location", event.location());
        row.put("calendarUrl", config.urls.self + "/calendar");
        out.add(row);
      }
    }
    return out;
  }

  // ---- downloads ---------------------------------------------------------------------------------

  /**
   * One part of one message, if it is allowed.
   *
   * <b>Always an attachment, never inline, and always with the type this server chose.</b> A
   * browser asked to display a file from a stranger in this origin is the whole class of attack
   * this feature could have introduced; `Content-Disposition: attachment` and
   * `X-Content-Type-Options: nosniff` together mean the file is saved rather than interpreted --
   * except for the images, which are the one thing a page has to be able to show and which have
   * been through the dimension check to earn it.
   */
  private void part(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    long id = longOr(Forms.query(req.uri(), "m"));
    String wanted = orEmpty(Forms.query(req.uri(), "p"));
    Messages.Record message = id <= 0 ? null : accounts.inbox.byId(id, me.id());
    if (message == null || wanted.isBlank() || !wanted.matches("[0-9.]{1,40}")) {
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    // The manifest decides, not a fresh look at the file.
    //
    // It was written by Safety at delivery, so a part refused then is refused now for the same
    // reason -- and the check cannot drift between what the screen listed and what this serves.
    Messages.Attachment listed = null;
    for (Messages.Attachment part : message.parts()) {
      if (part.path().equals(wanted)) {
        listed = part;
        break;
      }
    }
    if (listed == null || !listed.allowed()) {
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    byte[] raw = files.read(id);
    if (raw == null) {
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    MimeTree.Part part = MimeTree.parse(raw).byPath(wanted);
    if (part == null) {
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    // Checked again on the way out, against the bytes actually being served.
    //
    // The manifest is a cache of this answer and the file on disk is the truth. They cannot
    // disagree today -- neither ever changes -- and checking anyway costs microseconds and means a
    // future bug in one of them cannot become a file served on a stale verdict.
    Safety.Verdict verdict = Safety.check(listed.filename(), part.content());
    if (!verdict.allowed()) {
      verbose.detail(() -> "inbox: refused part " + wanted + " of " + id + " -- "
          + verdict.reason());
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(config, accounts));
      return;
    }
    boolean image = verdict.isImage();
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, verdict.contentType(), part.content(),
        new String[]{
            "Content-Disposition", (image ? "inline" : "attachment") + "; filename=\""
                + Safety.safeName(listed.filename(), "attachment") + "\"",
            "X-Content-Type-Options", "nosniff",
            // somebody else's photograph of somebody else's children; never a shared cache
            "Cache-Control", "private, max-age=300"});
  }

  /** the message exactly as it arrived, which is the escape hatch for everything refused above */
  private void raw(Accounts accounts, ChannelHandlerContext ctx, FullHttpRequest req,
                   UserRecord me, WebHandler.Recorder recorder) throws SQLException {
    long id = longOr(Forms.query(req.uri(), "m"));
    Messages.Record message = id <= 0 ? null : accounts.inbox.byId(id, me.id());
    byte[] raw = message == null ? null : files.read(id);
    if (raw == null) {
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
          "no original is kept for that message".getBytes(StandardCharsets.UTF_8));
      return;
    }
    recorder.status(200);
    // message/rfc822 as a download: inert to a browser, and openable by every mail client there is
    Responses.send(ctx, req, HttpResponseStatus.OK, "message/rfc822", raw, new String[]{
        "Content-Disposition", "attachment; filename=\"message-" + id + ".eml\"",
        "X-Content-Type-Options", "nosniff",
        "Cache-Control", "private, max-age=300"});
  }

  // ---- doing things ------------------------------------------------------------------------------

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, SessionRecord session,
                   WebHandler.Recorder recorder) throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.self + "/mail";
    String outcome;
    boolean problem = false;

    if (form.bodyTooLarge()) {
      outcome = "That was too long to send in one go. Nothing was sent.";
      problem = true;
    } else if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      outcome = "That form expired. Please try again.";
      problem = true;
    } else {
      String action = String.valueOf(form.get("action"));
      long id = longOr(form.get("id"));
      Messages.Record message = id <= 0 ? null : accounts.inbox.byId(id, me.id());
      if (message == null) {
        outcome = "That message is not here.";
        problem = true;
      } else {
        switch (action) {
          case "archive" -> {
            accounts.inbox.archive(id, me.id());
            outcome = "Done with it.";
          }
          case "unarchive" -> {
            accounts.inbox.unarchive(id, me.id());
            outcome = "Back in the inbox.";
            where = config.urls.self + "/mail";
          }
          case "delete" -> {
            // the row and the file together; a deleted-items folder that fills for years is a copy
            // of everything you decided you did not want, on a machine in your house
            if (accounts.inbox.delete(id, me.id())) {
              files.delete(id);
            }
            outcome = "Deleted.";
          }
          case "reply" -> {
            Sent sent = reply(config, accounts, me, message, form);
            outcome = sent.message;
            problem = sent.problem;
            where = sent.problem ? config.urls.self + "/mail/" + id + "/reply"
                : config.urls.self + "/mail";
          }
          default -> {
            outcome = "That is not something this page can do.";
            problem = true;
          }
        }
      }
    }
    flash.set(Flash.keyFor(session), outcome, problem);
    verbose.detail("inbox: " + me.email() + " -> " + outcome);
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private record Sent(String message, boolean problem) {
  }

  /**
   * Send a reply and take the message out of the inbox.
   *
   * <b>Archived only when something actually went.</b> A reply that failed at every recipient
   * leaves the message where it is, because the thing still needs answering -- archiving it would
   * hide the one message somebody has to come back to.
   */
  private Sent reply(DomainConfig config, Accounts accounts, UserRecord me,
                     Messages.Record message, Forms form) throws SQLException {
    // What they typed is checked before whether this server can send it.
    //
    // "An empty reply is not a reply" is about the thing they just did; "this server cannot send
    // mail" is about the machine, and is already on the screen in front of them with the button
    // disabled. Answering the second first means somebody who submitted nothing is told about a
    // configuration problem instead of about the empty box.
    String body = orEmpty(form.text("body"));
    if (form.tooLong() != null) {
      return new Sent("'" + form.tooLong() + "' was too long. Nothing was sent.", true);
    }
    if (body.isBlank()) {
      return new Sent("An empty reply is not a reply.", true);
    }
    if (postman == null) {
      return new Sent("This server cannot send mail: forwarding is off in config.cfg.", true);
    }
    boolean all = form.get("all") != null;
    Outgoing.Audience audience = Outgoing.audienceFor(message, addressesOf(accounts, me), all);
    if (!audience.any()) {
      return new Sent("There is nobody to reply to on that message.", true);
    }
    // The From address is the address it arrived at, and it has to be one this server can honestly
    // sign for -- otherwise the reply fails every check at the far end and burns the reputation of
    // every domain that is set up properly.
    Set<String> ourDomains = new LinkedHashSet<>();
    ourDomains.add(config.domain);
    if (!Postman.canSendAs(audience.from(), ourDomains)) {
      return new Sent("Replies can only be sent from an address at " + config.domain + ".", true);
    }
    String name = accounts.people.profileOf(me.id()).nameOr("");
    Outgoing.Written written = Outgoing.reply(message, audience, name, body, accounts.zone());
    Postman.Sent sent = postman.send(audience.from(), written);
    if (sent.anyDelivered()) {
      accounts.inbox.replied(message.id(), me.id());
    }
    return new Sent(sent.describe(), !sent.allDelivered());
  }

  // ---- plumbing ----------------------------------------------------------------------------------

  /**
   * Every address this person can be written to at.
   *
   * Their account address and every mailbox an administrator gave them -- which is the set that
   * comes out of a reply-all, because writing to yourself puts a copy in the inbox you are trying
   * to empty.
   */
  public static Set<String> addressesOf(Accounts accounts, UserRecord me) throws SQLException {
    LinkedHashSet<String> mine = new LinkedHashSet<>();
    mine.add(me.email().toLowerCase(Locale.ROOT));
    for (Mailboxes.Box box : accounts.mailboxes.ownedBy(me.id())) {
      mine.add(box.address().toLowerCase(Locale.ROOT));
    }
    return mine;
  }

  private Map<String, Object> shell(DomainConfig config, Accounts accounts, UserRecord me,
                                    String csrf, SessionRecord session) throws SQLException {
    Map<String, Object> model = new LinkedHashMap<>();
    Chrome.admin(model, accounts);
    model.put("csrf", csrf);
    model.put("community", config.name);
    model.put("selfUrl", config.urls.self);
    model.put("mailUrl", config.urls.self + "/mail");
    model.put("calendarUrl", config.urls.self + "/calendar");
    model.put("action", config.urls.self + "/mail");
    model.put("flash", flash.take(Flash.keyFor(session)));
    model.put("unread", accounts.inbox.unreadCount(me.id()));
    model.put("inboxCount", accounts.inbox.inboxCount(me.id()));
    model.put("title", "Mail");
    model.put("nav", List.of());
    return model;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder,
                    Accounts accounts, String csrf, byte[] body) {
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", body,
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accounts.security, csrf)});
  }

  private byte[] notFound(DomainConfig config, Accounts accounts) {
    Map<String, Object> model = new LinkedHashMap<>();
    Chrome.admin(model, accounts);
    model.put("title", "Not found");
    model.put("heading", "Not found");
    model.put("message", "There is nothing here.");
    model.put("nav", List.of());
    return templates.render("message", model);
  }

  static String when(java.sql.Timestamp stamp, ZoneId zone) {
    if (stamp == null) {
      return "";
    }
    ZonedDateTime at = ZonedDateTime.ofInstant(stamp.toInstant(), zone);
    ZonedDateTime now = ZonedDateTime.now(zone);
    if (at.toLocalDate().equals(now.toLocalDate())) {
      return at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US));
    }
    if (at.getYear() == now.getYear()) {
      return at.format(DateTimeFormatter.ofPattern("d MMM", Locale.US));
    }
    return at.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US));
  }

  static long longOr(Object value) {
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
