package io.hearth.sms;

/**
 * The only implementation: it refuses, and says why.
 *
 * Refusing out loud rather than silently succeeding is the whole value of having this class. A stub
 * that returned "ok" would put a preference in the settings page, a phone number in the database
 * and a green tick in a log, and the first person to find out it does nothing would be somebody who
 * needed a message.
 */
public class NoSms implements Sms {
  public static final NoSms INSTANCE = new NoSms();

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public String describe() {
    return "no provider configured";
  }

  @Override
  public Outcome send(String phone, String text) {
    return Outcome.failed("no SMS provider is configured; nothing was sent");
  }
}
