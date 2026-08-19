package io.hearth.smtp;

import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.calendar.IcsReplies;
import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to mail that arrives: first, is it somebody answering an invitation?
 *
 * <b>This is the half that makes a calendar invitation a conversation rather than an announcement.</b>
 * Somebody presses Accept in whatever they use, their program sends a reply here, and the guest list
 * changes -- without them ever opening the site. For a lot of members that will be the only way they
 * ever answer, which is the point: the goal is people turning up, not people visiting a website.
 *
 * Everything else still goes to whatever was behind this -- today the terminal printer -- so nothing
 * is swallowed. A message that was not a calendar reply is not an error, it is just mail.
 *
 * <b>Accepting is not the same as acting.</b> Every path here returns `accepted`, including the ones
 * that ignore the message. A `550` to a calendar client teaches it that this address is broken, and
 * a bounce lands on somebody who did nothing but press a button; the honest failure is that their
 * answer did not register and the nudge loop asks them again.
 */
public class CommunityMailReceiver implements MailReceiver {
  private static final Logger LOG = LoggerFactory.getLogger(CommunityMailReceiver.class);

  private final AuthSystem auth;
  private final DomainTree domains;
  private final MailReceiver next;
  private final io.hearth.places.Geocoder geocoder;
  private final Verbose verbose;

  public CommunityMailReceiver(AuthSystem auth, DomainTree domains, MailReceiver next,
                               Verbose verbose) {
    this(auth, domains, next, io.hearth.places.Geocoder.NONE, verbose);
  }

  public CommunityMailReceiver(AuthSystem auth, DomainTree domains, MailReceiver next,
                               io.hearth.places.Geocoder geocoder, Verbose verbose) {
    this.auth = auth;
    this.domains = domains;
    this.next = next;
    this.geocoder = geocoder;
    this.verbose = verbose;
  }

  @Override
  public Outcome receive(Envelope envelope) {
    try {
      DomainConfig config = domains.resolve(envelope.domain());
      Accounts accounts = config == null ? null : auth.forDomain(config.domain);
      if (accounts != null && config.calendar.enabled) {
        boolean authenticated = authenticated(envelope);
        IcsReplies.Result result = IcsReplies.apply(accounts, envelope, authenticated, verbose);
        if (result.applied()) {
          return Outcome.accepted("calendar: " + result.detail());
        }
        // ...and if it was not an answer, it may be an invitation: somebody adding this community
        // to an event in their own calendar, which is the shortest path there is between "we are
        // doing this" and everybody knowing about it
        io.hearth.calendar.IcsRequests.Result made = io.hearth.calendar.IcsRequests.apply(
            config, accounts, geocoder, envelope, authenticated, verbose);
        if (made.created()) {
          return Outcome.accepted("calendar: " + made.detail());
        }
        verbose.detail(() -> "calendar: nothing to do -- " + result.detail() + "; "
            + made.detail());
      }
    } catch (Exception ex) {
      // a message this server could not make sense of is still a message it received. Failing here
      // would bounce somebody's Accept back at them with a stack trace's worth of nothing.
      LOG.error("inbound-calendar-failed", ex);
    }
    return next == null ? Outcome.accepted("received") : next.receive(envelope);
  }

  /**
   * Did this message pass the checks that already ran on it?
   *
   * The verdict is stamped on the front of the message by `SenderCheck`, so this reads a header
   * rather than re-deriving anything. A community that turned the checks off is treated as
   * authenticated, because that is a decision its operator made rather than a reason to distrust one
   * particular message -- and a community that left them on gets what they are for: a reply is a
   * claim about who somebody is, and an unauthenticated claim about identity is the exact thing not
   * to act on.
   */
  static boolean authenticated(Envelope envelope) {
    String results = envelope.headers().get("authentication-results");
    if (results == null || results.isBlank()) {
      return true;
    }
    String lower = results.toLowerCase();
    if (lower.contains("dmarc=pass")) {
      return true;
    }
    if (lower.contains("dmarc=fail")) {
      return false;
    }
    // No DMARC record at all is the common case for a personal domain, so fall back to the two that
    // stand on their own: either one *passing* is enough to believe an address.
    //
    // There used to be an `|| contains("=none")` on the end of this, and it undid the rest of the
    // line. The stamp always carries `dmarc=none` for a domain with no policy -- which is exactly
    // the case this fallback exists to decide -- so the clause was true whenever it was reached,
    // and SPF and DKIM stopped counting. A forged From: on any domain without a DMARC record was
    // "authenticated", and invariant 157's other half is the only thing that was still standing
    // between that and somebody accepting an invitation on another member's behalf.
    //
    // Nothing vouching for a message is not the same as nothing objecting to it. An answer that
    // cannot be authenticated does not register, and the nudge asks that person again -- which is
    // invariant 158 working as intended rather than a failure.
    return lower.contains("spf=pass") || lower.contains("dkim=pass");
  }
}
