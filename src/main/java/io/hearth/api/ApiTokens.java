package io.hearth.api;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.Sessions;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The tokens a person hands to a program, which are sessions with a bit set.
 *
 * <b>A token is a session and nothing else.</b> Same table, same reaper, same revocation, same
 * answer to "is this still valid" -- because a parallel notion of a credential is a second
 * implementation of that question, and the two will eventually disagree. The bit is what makes "who
 * did this" answerable afterwards: a token can never do anything the person could not, and is never
 * mistaken for that person sitting at a keyboard.
 *
 * <b>The label is the whole difference from a model's connection.</b> Both are robot sessions; an
 * API token's agent starts `api:`, so the two lists can never be confused for one another --
 * revoking a connector must not cut off somebody's build machine, and revoking a build machine must
 * not disconnect a model somebody authorised on a consent screen.
 *
 * <b>Two at a time, refused rather than rotated.</b> Minting a third could silently kill the token
 * a script has been using for a month, and a program that stops working for a reason nobody
 * witnessed is the worst failure this can have. The screen says which two exist and offers to
 * revoke one.
 */
public final class ApiTokens {
  /** what a token is called when nobody said */
  public static final String DEFAULT_LABEL = "a program";

  private ApiTokens() {
  }

  /** one live token, as a person needs to see it: what it is called and when it dies */
  public record Token(long id, String label, long createdAt, long lastSeenAt, long expiresAt) {
    public boolean expires() {
      return expiresAt != SessionRecord.NEVER;
    }
  }

  /**
   * Clean up whatever somebody typed as a name.
   *
   * It is shown back to them in a list they revoke things from, so it has to be readable and short
   * and cannot carry markup -- the point of a label is telling two tokens apart at a glance six
   * weeks later.
   */
  public static String label(String raw) {
    if (raw == null) {
      return DEFAULT_LABEL;
    }
    StringBuilder out = new StringBuilder();
    for (char ch : raw.trim().toCharArray()) {
      if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '_' || ch == '.') {
        out.append(ch);
      }
      if (out.length() >= 40) {
        break;
      }
    }
    String clean = out.toString().trim();
    return clean.isEmpty() ? DEFAULT_LABEL : clean;
  }

  /** every live token this person holds, oldest first */
  public static List<Token> of(Accounts accounts, long userId) throws SQLException {
    ArrayList<Token> tokens = new ArrayList<>();
    for (SessionRecord session : accounts.sessions.agentsFor(userId)) {
      if (session.agent() == null || !session.agent().startsWith(ApiConfig.AGENT_PREFIX)) {
        continue;
      }
      tokens.add(new Token(session.id(),
          session.agent().substring(ApiConfig.AGENT_PREFIX.length()),
          session.createdAt(), session.lastSeenAt(), session.expiresAt()));
    }
    tokens.sort((left, right) -> Long.compare(left.createdAt(), right.createdAt()));
    return tokens;
  }

  /** what happened when somebody asked for one */
  public record Minted(String token, Token record, String problem) {
    public boolean ok() {
      return token != null;
    }
  }

  public static Minted mint(Accounts accounts, ApiConfig config, long userId, String rawLabel)
      throws SQLException {
    if (!config.enabled) {
      return new Minted(null, null, "This community does not answer to programs.");
    }
    List<Token> existing = of(accounts, userId);
    if (existing.size() >= config.maxTokens) {
      return new Minted(null, null, "You already have " + existing.size() + " token(s), which is"
          + " the most this community allows. Revoke one first -- rotating the oldest away for you"
          + " would stop whatever is using it, and you would find out from something breaking.");
    }
    String label = label(rawLabel);
    Sessions.Issued issued = accounts.sessions.createForAgent(userId,
        ApiConfig.AGENT_PREFIX + label, config.lifetimeSeconds());
    return new Minted(issued.token(), new Token(issued.record().id(), label,
        issued.record().createdAt(), issued.record().lastSeenAt(), issued.record().expiresAt()),
        null);
  }

  /**
   * Take one away.
   *
   * Deleted rather than revoked, for the same reason signing out deletes a session: a revoked row
   * lingers, and "this token no longer works" should mean it does not exist. The id is checked
   * against this person's own tokens first, so an id from somewhere else revokes nothing.
   */
  public static boolean revoke(Accounts accounts, long userId, long tokenId) throws SQLException {
    for (Token token : of(accounts, userId)) {
      if (token.id() == tokenId) {
        accounts.sessions.deleteById(tokenId);
        return true;
      }
    }
    return false;
  }
}
