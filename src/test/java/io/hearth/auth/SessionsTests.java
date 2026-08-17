package io.hearth.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import io.hearth.common.Verbose;
import io.hearth.store.Store;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The session store: the cache, the write-through guarantee, and the reaper.
 *
 * These run against a real H2 file rather than a fake, because the properties under test are about
 * what survives a cache miss and what is actually on disk after a revocation. A mock would let both
 * of those be wrong.
 */
public class SessionsTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private File dir;
  private Store store;
  private Users users;
  private long userId;
  private long otherUserId;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("hearth-sessions-test").toFile();
    store = Store.open(dir, "example.com", Verbose.OFF);
    users = new Users(store);
    userId = users.create("somebody@example.com", null, true, null).id();
    otherUserId = users.create("other@example.com", null, true, null).id();
  }

  @After
  public void tearDown() {
    if (store != null) {
      store.close();
    }
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        child.delete();
      }
    }
    dir.delete();
  }

  private LoginSecurity security(String json) throws ConfigException {
    try {
      ObjectNode node = (ObjectNode) MAPPER.readTree(json);
      return new LoginSecurity(new ConfigObject(node, "login_security"));
    } catch (Exception ex) {
      throw new ConfigException("bad test config: " + ex.getMessage(), ex);
    }
  }

  private Sessions sessions(String json) throws Exception {
    return new Sessions(store, security(json), Verbose.OFF);
  }

  /** move a session's created_at back in time, so cap and expiry logic can be exercised */
  private void backdate(long sessionId, long millis) throws Exception {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE sessions SET created_at = ? WHERE id = ?")) {
      statement.setTimestamp(1, new Timestamp(System.currentTimeMillis() - millis));
      statement.setLong(2, sessionId);
      statement.executeUpdate();
    }
  }

  // ---- issuing and resolving ---------------------------------------------------------------

  @Test
  public void aTokenResolvesToItsSession() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, "127.0.0.1", "test");
    SessionRecord resolved = sessions.resolve(issued.token());
    assertNotNull(resolved);
    assertEquals(userId, resolved.userId());
    assertEquals(issued.record().id(), resolved.id());
  }

  @Test
  public void everyTokenIsDifferent() throws Exception {
    Sessions sessions = sessions("{}");
    assertNotEquals(sessions.create(userId, null, null).token(), sessions.create(userId, null, null).token());
  }

  @Test
  public void onlyTheHashIsStored() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    assertEquals(Tokens.hash(issued.token()), issued.record().tokenHash());
    assertNotEquals(issued.token(), issued.record().tokenHash());
    // the hash is not itself a working credential
    assertNull(sessions.resolve(issued.record().tokenHash()));
  }

  @Test
  public void nonsenseResolvesToNothing() throws Exception {
    Sessions sessions = sessions("{}");
    assertNull(sessions.resolve(null));
    assertNull(sessions.resolve(""));
    assertNull(sessions.resolve("not-a-real-token"));
  }

  // ---- the cache ---------------------------------------------------------------------------

  @Test
  public void theSecondLookupComesFromMemory() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    long missesBefore = sessions.cacheMisses();
    for (int k = 0; k < 100; k++) {
      assertNotNull(sessions.resolve(issued.token()));
    }
    assertEquals("a cached session must not touch the database", missesBefore, sessions.cacheMisses());
    assertEquals(100, sessions.cacheHits());
  }

  @Test
  public void aSessionEvictedFromTheCacheStillWorks() throws Exception {
    Sessions sessions = sessions("{\"session-cache-max\":1,\"session-cache-ttl-seconds\":1}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    // fill past the ceiling and sweep, which drops the coldest entries
    for (int k = 0; k < 5; k++) {
      sessions.create(otherUserId, null, null);
    }
    sessions.sweep();
    assertTrue("the cache should have been trimmed", sessions.cacheSize() <= 2);
    // the row is still on disk, so the token is still good; it just costs one lookup
    assertNotNull("eviction must not sign anybody out", sessions.resolve(issued.token()));
  }

  @Test
  public void aThousandSessionsResolveFromCache() throws Exception {
    Sessions sessions = sessions("{\"session-cache-max\":1000}");
    List<String> tokens = new java.util.ArrayList<>();
    for (int k = 0; k < 1000; k++) {
      tokens.add(sessions.create(userId, null, null).token());
    }
    long missesBefore = sessions.cacheMisses();
    for (String token : tokens) {
      assertNotNull(sessions.resolve(token));
    }
    assertEquals("all thousand should be cached", missesBefore, sessions.cacheMisses());
    assertEquals(1000, sessions.cacheSize());
  }

  // ---- write-through -----------------------------------------------------------------------

  @Test
  public void revokingWritesThroughBeforeItTouchesTheCache() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    sessions.revoke(issued.token());
    assertNull(sessions.resolve(issued.token()));
    // a fresh Sessions over the same database sees the revocation, so it landed on disk
    Sessions reopened = sessions("{}");
    assertNull("a revocation that only lived in memory would still work here",
        reopened.resolve(issued.token()));
  }

  @Test
  public void signingOutEverywhereEndsEverySession() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued one = sessions.create(userId, null, null);
    Sessions.Issued two = sessions.create(userId, null, null);
    Sessions.Issued elsewhere = sessions.create(otherUserId, null, null);

    assertEquals(2, sessions.revokeAllFor(userId));
    assertNull(sessions.resolve(one.token()));
    assertNull(sessions.resolve(two.token()));
    assertNotNull("somebody else's session is not ours to end", sessions.resolve(elsewhere.token()));

    Sessions reopened = sessions("{}");
    assertNull(reopened.resolve(one.token()));
    assertNotNull(reopened.resolve(elsewhere.token()));
  }

  @Test
  public void creatingWritesThroughToo() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    Sessions reopened = sessions("{}");
    assertNotNull("a session that only lived in memory would be lost on restart",
        reopened.resolve(issued.token()));
  }

  // ---- expiry ------------------------------------------------------------------------------

  @Test
  public void sessionsNeverExpireByDefault() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    assertEquals(SessionRecord.NEVER, issued.record().expiresAt());
    assertFalse(issued.record().isExpired(System.currentTimeMillis() + 10_000_000L));
  }

  @Test
  public void aLifetimeIsRecordedWhenConfigured() throws Exception {
    Sessions sessions = sessions("{\"session-lifetime-seconds\":60}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    assertNotEquals(SessionRecord.NEVER, issued.record().expiresAt());
    assertTrue(issued.record().isExpired(System.currentTimeMillis() + 61_000L));
    assertFalse(issued.record().isExpired(System.currentTimeMillis()));
  }

  @Test
  public void anExpiredSessionStopsResolvingAndIsReaped() throws Exception {
    Sessions sessions = sessions("{\"session-lifetime-seconds\":1}");
    Sessions.Issued issued = sessions.create(userId, null, null);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE sessions SET expires_at = ? WHERE id = ?")) {
      statement.setTimestamp(1, new Timestamp(System.currentTimeMillis() - 1000));
      statement.setLong(2, issued.record().id());
      statement.executeUpdate();
    }
    Sessions reopened = sessions("{\"session-lifetime-seconds\":1}");
    assertNull(reopened.resolve(issued.token()));
    assertTrue(reopened.sweep().deleted() >= 1);
    assertEquals(0, reopened.count());
  }

  // ---- the cap -----------------------------------------------------------------------------

  @Test
  public void theCapKeepsTheNewestSessionsAndOnlyReapsOldOnes() throws Exception {
    // the stated policy: sessions never expire, but only four older than the grace window stick
    Sessions sessions = sessions("{\"max-active-sessions\":4,\"max-active-sessions-grace-seconds\":1800}");
    List<Sessions.Issued> issued = new java.util.ArrayList<>();
    for (int k = 0; k < 6; k++) {
      issued.add(sessions.create(userId, null, null));
    }
    // all six are fresh, so nothing is over the line yet
    assertEquals(6, sessions.count());
    assertEquals(0, sessions.sweep().capped());

    // age the first four past the grace window, each a minute older than the next, so that
    // "oldest first" is well defined rather than a tie the database breaks however it likes
    for (int k = 0; k < 4; k++) {
      backdate(issued.get(k).record().id(), 3600_000L + (4 - k) * 60_000L);
    }
    Sessions.Swept swept = sessions.sweep();
    assertEquals("only the aged surplus should go", 2, swept.capped());
    assertEquals(4, sessions.count());
    // the two newest survive because they are newest, not because they are young
    assertNotNull(sessions.resolve(issued.get(5).token()));
    assertNotNull(sessions.resolve(issued.get(4).token()));
    assertNull(sessions.resolve(issued.get(0).token()));
    assertNull(sessions.resolve(issued.get(1).token()));
  }

  @Test
  public void aFreshLoginDoesNotKnockAnybodyOutImmediately() throws Exception {
    Sessions sessions = sessions("{\"max-active-sessions\":2,\"max-active-sessions-grace-seconds\":1800}");
    Sessions.Issued first = sessions.create(userId, null, null);
    Sessions.Issued second = sessions.create(userId, null, null);
    Sessions.Issued third = sessions.create(userId, null, null);
    // over the cap, but nothing is old enough to take away yet
    assertNotNull(sessions.resolve(first.token()));
    assertNotNull(sessions.resolve(second.token()));
    assertNotNull(sessions.resolve(third.token()));
    assertEquals(3, sessions.count());
  }

  @Test
  public void noCapMeansNoCap() throws Exception {
    Sessions sessions = sessions("{}");
    for (int k = 0; k < 20; k++) {
      backdate(sessions.create(userId, null, null).record().id(), 3600_000L);
    }
    assertEquals(0, sessions.sweep().capped());
    assertEquals(20, sessions.count());
  }

  @Test
  public void theCapIsPerPerson() throws Exception {
    Sessions sessions = sessions("{\"max-active-sessions\":1,\"max-active-sessions-grace-seconds\":0}");
    Sessions.Issued mine = sessions.create(userId, null, null);
    Sessions.Issued theirs = sessions.create(otherUserId, null, null);
    backdate(mine.record().id(), 10_000L);
    backdate(theirs.record().id(), 10_000L);
    sessions.sweep();
    // one each, so neither is over
    assertNotNull(sessions.resolve(mine.token()));
    assertNotNull(sessions.resolve(theirs.token()));
  }

  // ---- inspection --------------------------------------------------------------------------

  @Test
  public void activeSessionsAreListedNewestFirst() throws Exception {
    Sessions sessions = sessions("{}");
    Sessions.Issued older = sessions.create(userId, null, null);
    backdate(older.record().id(), 60_000L);
    Sessions.Issued newer = sessions.create(userId, null, null);
    List<SessionRecord> active = sessions.activeFor(userId);
    assertEquals(2, active.size());
    assertEquals(newer.record().id(), active.get(0).id());
    assertEquals(older.record().id(), active.get(1).id());
  }

  @Test
  public void ipAndUserAgentAreTruncatedRatherThanRejected() throws Exception {
    Sessions sessions = sessions("{}");
    String longAgent = "x".repeat(500);
    Sessions.Issued issued = sessions.create(userId, "1.2.3.4", longAgent);
    assertNotNull(sessions.resolve(issued.token()));
  }
}
