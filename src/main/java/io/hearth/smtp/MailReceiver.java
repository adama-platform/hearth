package io.hearth.smtp;

/**
 * What happens to a message once it has arrived.
 *
 * The seam, in the same spirit as {@link io.hearth.mail.Mailer} on the way out: the protocol knows
 * nothing about what a community wants done with its mail, and this knows nothing about SMTP. Today
 * the only implementation prints to the terminal, which is exactly enough to see that routing works
 * and to develop against.
 *
 * A receiver is called on the connection's thread with the message already whole. It must not block
 * for long, and it must not throw for something the sending server could retry -- return the
 * outcome and let the protocol decide which code to give back.
 */
public interface MailReceiver {
  Outcome receive(Envelope envelope);

  /**
   * Is there anywhere here for this address, asked at RCPT before any data arrives?
   *
   * <b>The default is yes, and that is what keeps this a seam rather than a requirement.</b> A
   * receiver that has no notion of individual addresses -- the terminal one, and anything written
   * to watch mail arrive -- should not have to answer a question it has no opinion about.
   *
   * <b>Answering no is worth a great deal when a receiver *can*.</b> A 550 at RCPT tells a sending
   * server the address does not exist, which is how a mistyped address comes back to the person who
   * typed it instead of vanishing; it costs a directory harvester one line per guess instead of a
   * whole message; and it means the server never takes responsibility for mail it has nowhere to
   * put, which is the only way to have no bounces to generate later.
   */
  default boolean accepts(String domain, String recipient) {
    return true;
  }

  /**
   * Accepted, or not, and whether it is worth trying again.
   *
   * This distinction matters more than most: a 4xx tells a sending server to hold the message and
   * keep retrying for days, and a 5xx tells it to give up and bounce to the sender. Answering the
   * wrong one either loses mail that was wanted or makes somebody's queue retry a message that
   * never will be.
   */
  record Outcome(boolean accepted, boolean temporary, String detail) {
    public static Outcome accepted(String detail) {
      return new Outcome(true, false, detail);
    }

    /** no, and do not come back -- the address does not exist, or the message is not wanted */
    public static Outcome refused(String detail) {
      return new Outcome(false, false, detail);
    }

    /** no, but try later -- we are the ones having a bad day */
    public static Outcome tryLater(String detail) {
      return new Outcome(false, true, detail);
    }
  }
}
