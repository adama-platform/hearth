package io.hearth.board;

import io.hearth.cache.Caches;
import io.hearth.cache.TtlCache;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the board keeps in memory, and what drops it.
 *
 * Two things are cached, and both are cached because they are expensive and *viewer independent*:
 * the feed's rows, and a thread's markdown rendered to HTML. Everything that differs per person --
 * whether they are watching, whether they may reply -- is computed from the cached value at render
 * time rather than baked into it, because a cache keyed by viewer in a community of five hundred is
 * five hundred copies of the same paragraph.
 *
 * Rendering is the cost worth avoiding. A thread of eighty comments is eighty markdown parses on
 * every page load, and the busiest thread is the one most people are opening.
 *
 * Invalidation is event-driven like everything else here, and rests on one fact: every change to a
 * comment also touches its post, because {@link Board} updates the comment count and the last
 * activity time in the same breath. So a listener that watches the posts table sees every change to
 * a thread's contents, and one keyed by post id is enough. Watching the comments table instead
 * would mean knowing which post a comment id belongs to, which is a query on the invalidation path
 * -- and for an insert, a comment that no cached entry has ever heard of.
 *
 * The feed is dropped whole rather than mended. It is one entry, it is rebuilt by one query, and
 * "which of the sixty rows moved" is a question whose wrong answers are silent.
 */
public class BoardCache {
  /** the feed is a single entry; the cache is here for the ceiling and the stats */
  private static final String FEED_KEY = "feed";

  private final String domain;
  private final Board board;
  private final TtlCache<String, List<Board.Post>> feed;
  private final TtlCache<String, Thread> threads;
  private final Verbose verbose;

  public BoardCache(String domain, Board board, Caches policies, EventBus events, Verbose verbose) {
    this.domain = domain;
    this.board = board;
    this.feed = new TtlCache<>(Caches.BOARD_FEED, policies.forName(Caches.BOARD_FEED));
    this.threads = new TtlCache<>(Caches.BOARD_THREADS, policies.forName(Caches.BOARD_THREADS));
    this.verbose = verbose;
    events.subscribe(this::onMutation);
  }

  /** one thread, with the markdown already done */
  public record Thread(Board.Post post, String bodyHtml, List<Rendered> comments) {
  }

  /** one comment, with everything that does not depend on who is looking */
  public record Rendered(long id, int depth, long authorId, String author, long createdAt,
                         boolean removed, boolean edited, String body, String bodyHtml) {
  }

  public List<Board.Post> feed(int limit) throws SQLException {
    List<Board.Post> cached = feed.get(FEED_KEY);
    if (cached != null) {
      return cached;
    }
    List<Board.Post> fresh = board.feed(limit);
    feed.put(FEED_KEY, fresh);
    return fresh;
  }

  /** null when there is no such post; a missing thread is not something to cache */
  public Thread thread(long postId) throws SQLException {
    String key = Long.toString(postId);
    Thread cached = threads.get(key);
    if (cached != null) {
      return cached;
    }
    Board.Post post = board.postById(postId);
    if (post == null) {
      return null;
    }
    ArrayList<Rendered> rendered = new ArrayList<>();
    for (Board.Comment comment : board.thread(postId)) {
      // the author id and the raw source ride along because they are facts about the comment, not
      // about who is reading it -- the "is this mine, can I edit it" question is answered from
      // these at render time rather than by caching a copy per viewer
      rendered.add(new Rendered(comment.id(), comment.depth(), comment.authorId(),
          comment.authorEmail(), comment.createdAt().getTime(), comment.removed(),
          comment.edited(), comment.removed() ? "" : comment.body(),
          comment.removed() ? null : Markdown.toSafeHtml(comment.body())));
    }
    Thread thread = new Thread(post, Markdown.toSafeHtml(post.body()), List.copyOf(rendered));
    threads.put(key, thread);
    return thread;
  }

  public List<TtlCache.Stats> cacheStats() {
    return List.of(feed.stats(), threads.stats());
  }

  /** the backstop sweep, run by whatever runs the others */
  public int sweep() {
    return feed.sweep() + threads.sweep();
  }

  private void onMutation(MutationEvent event) {
    if (!event.domain().equals(domain) || !event.touches(Schema.POSTS)) {
      return;
    }
    // a new post, a removed one, an edited expiry, a comment, a watcher joining -- all of them are
    // a posts event, and all of them change the feed
    feed.invalidate(FEED_KEY);
    threads.invalidate(event.key());
    verbose.detail(() -> "cache: board post " + event.key() + " invalidated");
  }
}
