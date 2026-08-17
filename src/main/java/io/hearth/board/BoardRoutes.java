package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Cookies;
import io.hearth.web.Forms;
import io.hearth.web.Icons;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The discussion board a member sees.
 *
 * Three paths, following the same rule as the admin: the feed is a page, a thread is a page, and
 * everything that changes something is a POST that redirects.
 *
 * <pre>
 *   /board            the feed
 *   /board/<id>       one thread
 *   POST /board       post, reply, or stop watching
 * </pre>
 *
 * The feed and each rendered thread are cached and dropped by the event bus, because a comment box
 * that re-renders forty posts of markdown on every page load is one that gets slower exactly as the
 * community gets busier. Nothing here decides when to invalidate: it publishes and listens, and the
 * cache does the rest.
 */
public class BoardRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(BoardRoutes.class);
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());
  private static final int FEED_SIZE = 60;

  private final Templates templates;
  private final Verbose verbose;

  public BoardRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  /**
   * May this person take somebody else's words down here?
   *
   * <b>`board_moderate`, not "is an admin".</b> The permission existed, was offered in the role
   * editor, gated the admin section's board screen -- and did nothing on the board itself, where the
   * conversation actually is. So a community that handed somebody moderating found they could only
   * do it from a different screen, which is the sort of gap that gets solved by making the person an
   * administrator instead. An administrator still passes, because `everything` answers yes to
   * everything.
   */
  private static boolean canModerate(Accounts accounts, UserRecord me) throws SQLException {
    return accounts.access.can(me, io.hearth.auth.Permission.board_moderate);
  }

  /** the board owns its path and everything under it, so a thread can have its own url */
  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.board;
    return path.equals(root) || path.startsWith(root + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null) {
        // the board is the community, and the community is what approval gates
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                io.hearth.web.Landing.carry(config.urls.login, io.hearth.web.Landing.here(req))});
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, recorder);
        return;
      }
      String path = Forms.path(req.uri());
      String root = config.urls.board;
      if (path.equals(root)) {
        feed(config, accounts, ctx, req, me, recorder);
        return;
      }
      long postId = idOf(path.substring(root.length() + 1));
      thread(config, accounts, ctx, req, me, postId, recorder);
    } catch (SQLException ex) {
      LOG.error("board-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong", null);
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  // ---- doing things ----------------------------------------------------------------------------

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.board;
    if (form.bodyTooLarge()) {
      redirect(ctx, req, recorder, where);
      return;
    }
    if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    String action = String.valueOf(form.get("action"));
    switch (action) {
      case "post" -> {
        String title = form.get("title");
        String body = form.text("body");
        if (title == null || body == null || form.tooLong() != null) {
          redirect(ctx, req, recorder, where);
          return;
        }
        Board.Post post = accounts.board.post(me.id(), me.email(), title, body,
            config.board.expiryDays);
        verbose.detail("board: " + me.email() + " posted " + post.id());
        where = config.urls.board + "/" + post.id();
      }
      case "reply" -> {
        long postId = idOf(form.get("post"));
        Board.Post post = accounts.board.postById(postId);
        String body = form.text("body");
        if (post == null || post.removed() || post.locked() || body == null
            || form.tooLong() != null) {
          redirect(ctx, req, recorder, where);
          return;
        }
        Long parentId = form.get("parent") == null ? null : idOf(form.get("parent"));
        Board.Comment comment = accounts.board.comment(postId, parentId, me.id(), me.email(), body);

        // Joining a conversation is what makes you a watcher. The set returned is who was watching
        // *before*, which is exactly who should be told -- and never includes the person replying.
        Set<Long> others =
            new java.util.LinkedHashSet<>(accounts.board.watchAndReturnOthers(postId, me.id()));
        Timestamp expires = post.expiresAt();

        // whoever is being answered hears about it as a response rather than as news, which is the
        // distinction the two notification settings exist to make: a reply aimed at you is a
        // conversation waiting on an answer, and activity in a thread you watch is not
        Long answered = answeredAuthor(accounts, parentId, post, me.id());
        // what the notification calls them, written into the note rather than joined when it is
        // read: an address in somebody's inbox is the same disclosure as one on the page, and it
        // would also be mailed out by the notifier
        String mine = io.hearth.people.Names.nameOf(accounts, me.id());
        int told = 0;
        if (answered != null) {
          others.remove(answered);
          accounts.inbox.add(answered, Inbox.Kind.response, postId, comment.id(), mine,
              mine + " replied to you in " + post.title(), expires);
          told++;
        }
        told += accounts.inbox.notifyWatchers(others, me.id(), Inbox.Kind.reply, postId,
            comment.id(), mine, mine + " replied to " + post.title(), expires);
        int total = told;
        verbose.detail(() -> "board: " + me.email() + " replied on " + postId + ", told " + total);
        where = config.urls.board + "/" + postId;
      }
      case "edit_post" -> {
        // author only, enforced in the DAO by the WHERE clause rather than by a check here that a
        // future caller could forget
        long postId = idOf(form.get("post"));
        String title = form.get("title");
        String body = form.text("body");
        if (title == null || body == null || form.tooLong() != null) {
          redirect(ctx, req, recorder, where);
          return;
        }
        accounts.board.editPost(postId, title, body, me.id());
        where = config.urls.board + "/" + postId;
      }
      case "edit_comment" -> {
        long commentId = idOf(form.get("comment"));
        String body = form.text("body");
        if (body == null || form.tooLong() != null) {
          redirect(ctx, req, recorder, where);
          return;
        }
        Board.Comment comment = accounts.board.commentById(commentId);
        accounts.board.editComment(commentId, body, me.id());
        where = comment == null ? where : config.urls.board + "/" + comment.postId();
      }
      case "remove_post" -> {
        long postId = idOf(form.get("post"));
        Board.Post post = accounts.board.postById(postId);
        if (post != null && (post.authorId() == me.id() || canModerate(accounts, me))) {
          accounts.board.removePost(postId, me.id());
        }
        where = config.urls.board;
      }
      case "remove_comment" -> {
        long commentId = idOf(form.get("comment"));
        Board.Comment comment = accounts.board.commentById(commentId);
        if (comment != null && (comment.authorId() == me.id() || canModerate(accounts, me))) {
          accounts.board.removeComment(commentId, me.id());
        }
        where = comment == null ? where : config.urls.board + "/" + comment.postId();
      }
      case "unwatch" -> {
        long postId = idOf(form.get("post"));
        accounts.board.unwatch(postId, me.id());
        where = config.urls.board + "/" + postId;
      }
      case "vote" -> {
        Subject subject = subjectOf(form);
        Signals.Kind kind = Signals.Kind.of(form.get("vote"));
        if (subject != null && kind != null && kind != Signals.Kind.flag) {
          accounts.signals.cast(subject, me.id(), kind, "");
        }
        where = backTo(config, form, where);
      }
      case "flag" -> {
        // a flag is a request for a person to look, and it says so plainly: nothing is hidden,
        // nothing is scored down, and the person who wrote it is not told
        Subject subject = subjectOf(form);
        if (subject != null) {
          accounts.signals.cast(subject, me.id(), Signals.Kind.flag, form.text("reason"));
          verbose.detail(() -> "board: " + me.email() + " flagged " + subject.key());
        }
        where = backTo(config, form, where);
      }
      case "poll_new" -> {
        where = newPoll(config, accounts, form, me, where);
      }
      case "poll_option" -> {
        where = addOption(config, accounts, form, me, where);
      }
      case "poll_remove_option" -> {
        long optionId = idOf(form.get("option"));
        Poll.Option option = accounts.polls.optionById(optionId);
        Poll.Record poll = option == null ? null : accounts.polls.byId(option.pollId());
        if (poll != null && poll.isOpen() && mayRemove(accounts, me, poll, option)) {
          accounts.polls.removeOption(optionId, me.id());
        }
        where = poll == null ? where : config.urls.board + "/" + poll.postId();
      }
      case "poll_vote" -> {
        long optionId = idOf(form.get("option"));
        Poll.Option option = accounts.polls.optionById(optionId);
        Poll.Record poll = option == null ? null : accounts.polls.byId(option.pollId());
        if (poll != null && poll.isOpen()
            && accounts.access.can(me, io.hearth.auth.Permission.board_vote)) {
          accounts.polls.vote(poll.id(), optionId, me.id(),
              "down".equals(form.get("weight")) ? -1 : 1);
        }
        where = poll == null ? where : config.urls.board + "/" + poll.postId();
      }
      case "poll_close" -> {
        long pollId = idOf(form.get("poll"));
        Poll.Record poll = accounts.polls.byId(pollId);
        if (poll != null && poll.isOpen() && mayClose(accounts, me, poll)) {
          new PollClock(null, java.util.Map.of(), verbose).settle(config, accounts, poll);
          verbose.detail(() -> "board: " + me.email() + " closed poll " + pollId);
        }
        where = poll == null ? where : config.urls.board + "/" + poll.postId();
      }
      case "read" -> accounts.inbox.markAllRead(me.id());
      default -> {
      }
    }
    redirect(ctx, req, recorder, where);
  }

  /**
   * Put a vote to the group.
   *
   * <b>A schedule poll needs the permission to create events</b>, checked here rather than when it
   * closes. Finding out at midnight, after people had voted, that the answer cannot become anything
   * wastes the group's attention and teaches them the feature does not work. Anybody can ask a
   * plain either-or question; what needs the permission is having the answer put itself in the
   * calendar.
   */
  private String newPoll(DomainConfig config, Accounts accounts, Forms form, UserRecord me,
                         String where) throws SQLException {
    long postId = idOf(form.get("post"));
    Board.Post post = accounts.board.postById(postId);
    if (post == null || post.removed() || post.locked()
        || !accounts.access.can(me, io.hearth.auth.Permission.board_write)) {
      return where;
    }
    Poll.Kind kind = Poll.Kind.of(form.get("kind"));
    String question = form.get("question");
    if (kind == null || question == null || question.isBlank() || form.tooLong() != null) {
      return config.urls.board + "/" + postId;
    }
    if (kind == Poll.Kind.schedule
        && !accounts.access.can(me, io.hearth.auth.Permission.calendar_write)) {
      return config.urls.board + "/" + postId;
    }
    java.sql.Timestamp closes = closesAt(config, form.get("closes_on"));
    if (kind == Poll.Kind.schedule && closes == null) {
      return config.urls.board + "/" + postId;
    }
    Poll.Record poll = accounts.polls.create(postId, kind, question, closes,
        form.get("only_me") == null, me.id());
    // the choices, typed one per line, because three boxes for three options is a form that has
    // decided in advance how many things a group is choosing between
    String choices = form.text("choices");
    if (kind == Poll.Kind.choice && choices != null) {
      for (String line : choices.split("\n")) {
        if (!line.isBlank()) {
          accounts.polls.addOption(poll.id(), Poll.Facet.choice, line.trim(), null, null, null,
              me.id());
        }
      }
    }
    verbose.detail(() -> "board: " + me.email() + " asked poll " + poll.id());
    return config.urls.board + "/" + postId;
  }

  private String addOption(DomainConfig config, Accounts accounts, Forms form, UserRecord me,
                           String where) throws SQLException {
    long pollId = idOf(form.get("poll"));
    Poll.Record poll = accounts.polls.byId(pollId);
    if (poll == null || !poll.isOpen()
        || !accounts.access.can(me, io.hearth.auth.Permission.board_write)) {
      return where;
    }
    String back = config.urls.board + "/" + poll.postId();
    if (!poll.openOptions() && (poll.createdBy() == null || poll.createdBy() != me.id())) {
      return back;
    }
    Poll.Facet facet = Poll.Facet.of(form.get("facet"));
    if (facet == null || accounts.polls.liveOptionCount(pollId, facet) >= Polls.MAX_OPTIONS) {
      return back;
    }
    if (facet == Poll.Facet.time) {
      java.time.LocalDate day = parseDay(form.get("day"));
      if (day == null || alreadyThere(accounts, pollId, day, null)) {
        return back;
      }
      accounts.polls.addOption(pollId, facet, "", day, form.get("at"), null, me.id());
      return back;
    }
    if (facet == Poll.Facet.place) {
      long placeId = idOf(form.get("place"));
      io.hearth.places.Places.Place place =
          placeId <= 0 ? null : accounts.places.byId(placeId);
      if (place == null || alreadyThere(accounts, pollId, null, placeId)) {
        return back;
      }
      accounts.polls.addOption(pollId, facet, place.name(), null, "", placeId, me.id());
      return back;
    }
    String label = form.get("label");
    if (label != null && !label.isBlank()) {
      accounts.polls.addOption(pollId, facet, label, null, null, null, me.id());
    }
    return back;
  }

  private static boolean alreadyThere(Accounts accounts, long pollId, java.time.LocalDate day,
                                      Long placeId) throws SQLException {
    for (Poll.Option option : accounts.polls.options(pollId)) {
      if (option.removed()) {
        continue;
      }
      if (day != null && day.equals(option.onDay())) {
        return true;
      }
      if (placeId != null && placeId.equals(option.placeId())) {
        return true;
      }
    }
    return false;
  }

  /** whoever put it forward, whoever asked the question, or a moderator */
  private static boolean mayRemove(Accounts accounts, UserRecord me, Poll.Record poll,
                                   Poll.Option option) throws SQLException {
    if (option.addedBy() != null && option.addedBy() == me.id()) {
      return true;
    }
    if (poll.createdBy() != null && poll.createdBy() == me.id()) {
      return true;
    }
    return accounts.access.can(me, io.hearth.auth.Permission.board_moderate);
  }

  /** whoever asked, or a moderator -- and a schedule poll also needs the calendar */
  private static boolean mayClose(Accounts accounts, UserRecord me, Poll.Record poll)
      throws SQLException {
    boolean mine = poll.createdBy() != null && poll.createdBy() == me.id();
    if (!mine && !accounts.access.can(me, io.hearth.auth.Permission.board_moderate)) {
      return false;
    }
    return poll.kind() != Poll.Kind.schedule
        || accounts.access.can(me, io.hearth.auth.Permission.calendar_write);
  }

  /**
   * The end of the day somebody named, in the community's own clock.
   *
   * "Closes on Friday" means Friday evening to everybody who reads it and midnight-that-morning to
   * nobody, so the day is taken as inclusive.
   */
  private static java.sql.Timestamp closesAt(DomainConfig config, String raw) {
    java.time.LocalDate day = parseDay(raw);
    return day == null ? null
        : java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(config.zone).toInstant());
  }

  private static java.time.LocalDate parseDay(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  // ---- showing things --------------------------------------------------------------------------

  private void feed(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    String csrf = Cookies.stableToken(req);
    // one read of the profiles for the whole feed; what a member sees is a person's name, never
    // the address they get spam at
    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);
    Map<String, Object> model = base(config, accounts, req, config.name + " board", csrf);
    model.put("me", names.of(me.id()));
    model.put("expiryDays", config.board.expiryDays);
    model.put("expires", config.board.expiryDays > 0);

    long now = System.currentTimeMillis();
    java.util.List<Board.Post> posts = accounts.boardCache.feed(FEED_SIZE);
    // one grouped query for the whole feed rather than one per row, which is the shape that makes
    // a board slower exactly as a community gets busier
    java.util.Map<Long, Signals.Tally> tallies = accounts.signals.tallies(Subject.Kind.post,
        posts.stream().map(Board.Post::id).toList(), me.id());
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Board.Post post : posts) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", post.id());
      row.put("url", config.urls.board + "/" + post.id());
      row.put("title", post.title());
      row.put("author", names.of(post.authorId()));
      row.put("comments", post.commentCount());
      row.put("when", WHEN.format(Instant.ofEpochMilli(post.lastActivityAt().getTime())));
      row.put("pinned", post.pinned());
      row.put("locked", post.locked());
      row.put("watching", post.isWatchedBy(me.id()));
      row.put("expiring", post.expires() && post.daysLeft(now) <= 3);
      row.put("daysLeft", post.daysLeft(now));
      putTally(row, tallies.getOrDefault(post.id(), Signals.Tally.NONE));
      row.put("subjectKind", Subject.Kind.post.name());
      row.put("subjectId", post.id());
      row.put("postId", post.id());
      row.put("csrf", csrf);
      row.put("signalAction", config.urls.board);
      rows.add(row);
    }
    model.put("posts", rows);
    model.put("anyPosts", !rows.isEmpty());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("board", model));
  }

  private void thread(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                      FullHttpRequest req, UserRecord me, long postId,
                      WebHandler.Recorder recorder) throws SQLException {
    BoardCache.Thread cached = accounts.boardCache.thread(postId);
    Board.Post post = cached == null ? null : cached.post();
    String csrf = Cookies.stableToken(req);
    if (post == null || post.removed() || post.expired(System.currentTimeMillis())) {
      Map<String, Object> model = base(config, accounts, req, "Not here", csrf);
      model.put("heading", "That conversation is not here");
      model.put("message", post != null && post.expired(System.currentTimeMillis())
          ? "It aged out. Posts on this board expire so the feed stays a conversation."
          : "It may have been removed.");
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, templates.render("message", model));
      return;
    }

    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);
    Map<String, Object> model = base(config, accounts, req, post.title(), csrf);
    model.put("me", names.of(me.id()));
    model.put("id", post.id());
    model.put("title", post.title());
    model.put("bodyHtml", cached.bodyHtml());
    model.put("author", names.of(post.authorId()));
    model.put("when", WHEN.format(Instant.ofEpochMilli(post.createdAt().getTime())));
    model.put("locked", post.locked());
    model.put("watching", post.isWatchedBy(me.id()));
    model.put("watchers", post.watchers().size());
    boolean admin = canModerate(accounts, me);
    Subject postSubject = new Subject(Subject.Kind.post, post.id());
    putTally(model, accounts.signals.tally(postSubject, me.id()));
    model.put("subjectKind", Subject.Kind.post.name());
    model.put("subjectId", post.id());
    model.put("postId", post.id());
    model.put("signalAction", config.urls.board);
    model.put("canModerate", admin);
    model.put("mine", post.authorId() == me.id());
    model.put("canRemove", post.authorId() == me.id() || admin);
    model.put("edited", post.edited());
    model.put("body", post.body());
    model.put("action", config.urls.board);
    model.put("boardUrl", config.urls.board);
    long now = System.currentTimeMillis();
    model.put("expires", post.expires());
    model.put("daysLeft", post.daysLeft(now));

    // everything per-viewer is decided here, from a value that is the same for everybody: caching
    // the finished HTML instead would be one copy of the same paragraph per member
    java.util.Map<Long, Signals.Tally> commentTallies = accounts.signals.tallies(
        Subject.Kind.comment, cached.comments().stream().map(BoardCache.Rendered::id).toList(),
        me.id());
    ArrayList<Map<String, Object>> comments = new ArrayList<>();
    for (BoardCache.Rendered comment : cached.comments()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", comment.id());
      // the post, not the comment: the nested reply form used to send the comment id here, which
      // made every reply-to-a-reply from the UI look up a post that does not exist and quietly do
      // nothing. The test-suite reply helper builds the form by hand, so nothing saw it.
      row.put("postId", postId);
      row.put("depth", comment.depth());
      // the indent is a style rather than nested markup: a tree of divs six deep is a tree that
      // wraps badly on a phone, and the depth is already known here
      row.put("indent", Math.min(comment.depth(), Board.MAX_DEPTH) * 1.5);
      // the cached value holds the address because it is a fact about the comment; what a reader
      // sees is resolved here, per render, from who they are rather than from what was stored
      row.put("author", names.of(comment.authorId()));
      row.put("when", WHEN.format(Instant.ofEpochMilli(comment.createdAt())));
      row.put("at", comment.createdAt());
      row.put("removed", comment.removed());
      row.put("bodyHtml", comment.removed()
          ? "<p class=\"gone\">removed</p>" : comment.bodyHtml());
      row.put("canReply", !post.locked() && comment.depth() < Board.MAX_DEPTH);
      row.put("mine", comment.authorId() == me.id());
      row.put("canRemove", comment.authorId() == me.id() || admin);
      row.put("edited", comment.edited());
      row.put("body", comment.body());
      putTally(row, commentTallies.getOrDefault(comment.id(), Signals.Tally.NONE));
      row.put("subjectKind", Subject.Kind.comment.name());
      row.put("subjectId", comment.id());
      row.put("postId", postId);
      row.put("csrf", csrf);
      row.put("signalAction", config.urls.board);
      comments.add(row);
    }
    // Clumped by age once a thread gets long. Comments do not expire -- a thread is what the
    // community decided -- so the answer to four hundred replies is that the old ones arrive as one
    // line with a count on it, and the recent conversation is open underneath.
    pollsInto(model, config, accounts, me, post, csrf);
    model.put("commentGroups", clump(comments, System.currentTimeMillis()));
    model.put("comments", comments);
    model.put("anyComments", !comments.isEmpty());
    model.put("commentCount", comments.size());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("thread", model));
  }

  /**
   * The votes attached to this conversation, with everybody's numbers and this reader's own marks.
   *
   * Not cached with the rest of the thread, and that is the point of doing it here: the thread's
   * HTML is rendered once for everybody, and a poll is the one thing on the page whose appearance
   * depends on who is looking -- which button of yours is pressed. Caching it per viewer in a
   * community of five hundred would be five hundred copies of the same paragraph, which is exactly
   * what the board's cache exists to avoid.
   */
  private void pollsInto(Map<String, Object> model, DomainConfig config, Accounts accounts,
                         UserRecord me, Board.Post post, String csrf) throws SQLException {
    List<Poll.Record> polls = accounts.polls.forPost(post.id());
    boolean mayVote = accounts.access.can(me, io.hearth.auth.Permission.board_vote);
    boolean mayWrite = accounts.access.can(me, io.hearth.auth.Permission.board_write);
    boolean mayPlan = accounts.access.can(me, io.hearth.auth.Permission.calendar_write);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Poll.Record poll : polls) {
      Polls.Standing standing = accounts.polls.standing(poll.id(), me.id());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", poll.id());
      row.put("question", poll.question());
      row.put("open", poll.isOpen());
      row.put("schedule", poll.kind() == Poll.Kind.schedule);
      row.put("csrf", csrf);
      row.put("action", config.urls.board);
      row.put("closes", poll.closesAt() == null ? null
          : WHEN.format(Instant.ofEpochMilli(poll.closesAt().getTime())));
      row.put("outcome", poll.outcome().isBlank() ? null : poll.outcome());
      row.put("eventUrl", poll.eventId() == null ? null
          : config.urls.calendar + "/" + poll.eventId());
      row.put("mayVote", mayVote && poll.isOpen());
      boolean mine = poll.createdBy() != null && poll.createdBy() == me.id();
      row.put("mayClose", poll.isOpen() && mayClose(accounts, me, poll));
      row.put("mayAdd", poll.isOpen() && mayWrite && (poll.openOptions() || mine));
      if (poll.kind() == Poll.Kind.schedule) {
        row.put("times", facetRows(accounts, standing, Poll.Facet.time, config));
        row.put("places", facetRows(accounts, standing, Poll.Facet.place, config));
        row.put("placeChoices", placeChoices(accounts, standing));
      } else {
        row.put("choices", facetRows(accounts, standing, Poll.Facet.choice, config));
      }
      rows.add(row);
    }
    model.put("polls", rows);
    model.put("anyPolls", !rows.isEmpty());
    // the form to start one is offered only to somebody who could actually finish it -- a control
    // that would refuse teaches people the software is broken (invariant 149)
    model.put("mayAskPoll", mayWrite && !post.locked() && !post.removed());
    model.put("mayAskSchedule", mayPlan);
    model.put("anyPlaces", !accounts.places.all(200).isEmpty());
  }

  private List<Map<String, Object>> facetRows(Accounts accounts, Polls.Standing standing,
                                              Poll.Facet facet, DomainConfig config)
      throws SQLException {
    Poll.Result result = standing.count(facet);
    int strongest = 0;
    for (Poll.Tally tally : result.tallies()) {
      strongest = Math.max(strongest, tally.score());
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Poll.Tally tally : result.tallies()) {
      Poll.Option option = tally.option();
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", option.id());
      row.put("what", option.describe());
      row.put("up", tally.up());
      row.put("down", tally.down());
      row.put("anyDown", tally.down() > 0);
      row.put("score", tally.score());
      row.put("share", tally.share(strongest));
      row.put("winning", result.decided() && result.winner().option().id() == option.id());
      Integer mine = standing.mine().get(option.id());
      row.put("mineUp", mine != null && mine > 0);
      row.put("mineDown", mine != null && mine < 0);
      row.put("approval", facet.isApproval());
      if (option.placeId() != null) {
        io.hearth.places.Places.Place place = accounts.places.byId(option.placeId());
        if (place != null) {
          row.put("placeUrl", config.urls.places + "/" + place.typeSlug() + "/" + place.slug());
          row.put("placeWhere", place.oneLine());
        }
      }
      rows.add(row);
    }
    return rows;
  }

  /** the address book, minus what is already on the table */
  private List<Map<String, Object>> placeChoices(Accounts accounts, Polls.Standing standing)
      throws SQLException {
    java.util.HashSet<Long> taken = new java.util.HashSet<>();
    for (Poll.Option option : standing.options()) {
      if (!option.removed() && option.placeId() != null) {
        taken.add(option.placeId());
      }
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.places.Places.Place place : accounts.places.all(200)) {
      if (taken.contains(place.id()) || !place.published()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", place.id());
      row.put("name", place.name());
      rows.add(row);
    }
    return rows;
  }

  /**
   * Group the rendered rows the same way {@link CommentGroups} groups the records.
   *
   * The thread page works from the cache rather than from the comment rows, because everything on
   * it was rendered once for everybody -- so the grouping is redone here on the same rule rather
   * than the cache being taught about time. The rule is one method away in one place; what would
   * be worth avoiding is two rules.
   */
  private static List<Map<String, Object>> clump(List<Map<String, Object>> rows, long now) {
    ArrayList<Map<String, Object>> groups = new ArrayList<>();
    if (rows.isEmpty()) {
      return groups;
    }
    boolean everythingOpen = rows.size() < CommentGroups.EXPAND_BELOW;
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    String label = null;
    ArrayList<Map<String, Object>> current = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if (Integer.valueOf(0).equals(row.get("depth"))) {
        String wanted = everythingOpen ? "" : CommentGroups.labelFor(
            new java.sql.Timestamp((Long) row.get("at")), today, zone);
        if (label != null && !wanted.equals(label)) {
          groups.add(group(label, current));
          current = new ArrayList<>();
        }
        label = wanted;
      }
      current.add(row);
    }
    if (label != null) {
      groups.add(group(label, current));
    }
    for (int k = 0; k < groups.size(); k++) {
      boolean collapse = !everythingOpen && k < groups.size() - 2;
      groups.get(k).put("collapsed", collapse);
      groups.get(k).put("open", !collapse);
    }
    return groups;
  }

  private static Map<String, Object> group(String label, List<Map<String, Object>> comments) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("label", label);
    row.put("anyLabel", !label.isBlank());
    row.put("count", comments.size());
    row.put("comments", comments);
    return row;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title, String csrf) {
    Map<String, Object> model = new HashMap<>();
    io.hearth.web.Chrome.site(model, config, accounts, req);
    model.put("title", title);
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("csrf", csrf);
    return model;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, Accounts accounts, String csrf,
                    byte[] html) {
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", html,
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  private void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  /**
   * Who, if anybody, is being answered.
   *
   * A reply to a comment answers its author; a reply to the thread answers whoever posted it. Both
   * only count when it is somebody else -- answering yourself is not a notification, it is a train
   * of thought.
   */
  private static Long answeredAuthor(Accounts accounts, Long parentId, Board.Post post, long meId)
      throws SQLException {
    long author;
    if (parentId == null) {
      author = post.authorId();
    } else {
      Board.Comment parent = accounts.board.commentById(parentId);
      if (parent == null) {
        return null;
      }
      author = parent.authorId();
    }
    return author == meId ? null : author;
  }

  /** which thing a vote or a flag is about, from the two fields the form carries */
  private static Subject subjectOf(Forms form) {
    Subject.Kind kind;
    try {
      kind = Subject.Kind.valueOf(String.valueOf(form.get("subject_kind")).trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
    long id = idOf(form.get("subject_id"));
    return id <= 0 ? null : new Subject(kind, id);
  }

  /** back to the thread the button was pressed on, rather than to the top of the board */
  private static String backTo(DomainConfig config, Forms form, String fallback) {
    long post = idOf(form.get("post"));
    return post > 0 ? config.urls.board + "/" + post : fallback;
  }

  /**
   * What a page shows about a vote: the two counts and what this reader already said.
   *
   * The score is not used for anything -- nothing is hidden, sorted or removed by it. A community
   * where votes bury things has handed its judgement to whoever votes most, and the entire point of
   * this product is that a person decides. What the numbers are for is telling that person where to
   * look.
   */
  private static void putTally(Map<String, Object> row, Signals.Tally tally) {
    row.put("up", tally.up());
    row.put("down", tally.down());
    row.put("anyUp", tally.up() > 0);
    row.put("anyDown", tally.down() > 0);
    row.put("iVotedUp", tally.iVotedUp());
    row.put("iVotedDown", tally.iVotedDown());
    row.put("flags", tally.flags());
    row.put("flagged", tally.flags() > 0);
  }

  private static long idOf(String raw) {
    try {
      return raw == null ? -1 : Long.parseLong(raw.trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }
}
