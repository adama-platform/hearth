package io.hearth.sms;

/**
 * Text messages, which this server cannot send yet.
 *
 * This exists as an interface with one refusing implementation rather than as nothing at all,
 * because the shape of the seam is the decision worth making early and it is cheap to make now:
 * one method, a closed outcome, and no provider vocabulary anywhere above it. When a provider
 * lands it drops in beside {@link NoSms} the way {@link io.hearth.mail.AmazonSes} dropped in beside
 * the terminal mailer.
 *
 * What is deliberately *not* here: a phone number is stored, and {@link
 * io.hearth.board.NotifyPrefs} carries an sms flag, but nothing reads either to decide to send.
 * A settings page that offered a channel nothing delivers on would be a promise the server does
 * not keep, so the page says out loud that SMS is not available yet -- see {@link #available()},
 * which is what it asks.
 *
 * The reason there is no provider today is that every one of them needs an account, a sending
 * number and a per-message cost, none of which a community of under five hundred people has
 * decided it wants. The groundwork is here so that decision is a config block rather than a
 * refactor.
 */
public interface Sms {
  /** whether anything would actually be sent; the settings page asks before offering the option */
  boolean available();

  /** what this is, for the boot report */
  String describe();

  /** one message; never throws for something a person could retry */
  Outcome send(String phone, String text);

  /** what happened */
  record Outcome(boolean delivered, String detail) {
    public static Outcome ok(String detail) {
      return new Outcome(true, detail);
    }

    public static Outcome failed(String detail) {
      return new Outcome(false, detail);
    }
  }
}
