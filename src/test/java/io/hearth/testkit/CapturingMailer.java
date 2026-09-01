package io.hearth.testkit;

import io.hearth.mail.Mailer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mailer that keeps what it was asked to send.
 *
 * This is how the account tests read a code back: exactly the way a person does, by looking at what
 * arrived in the mail, rather than by reaching into PendingCodes. That means the test exercises the
 * whole path -- issue, deliver, redeem -- and a change that stopped sending the mail would fail it.
 */
public class CapturingMailer implements Mailer {
  private final List<Sent> sent = new CopyOnWriteArrayList<>();
  private final List<String> html = new CopyOnWriteArrayList<>();
  private final List<String> subjects = new CopyOnWriteArrayList<>();
  private final List<String> touches = new CopyOnWriteArrayList<>();

  /** which of the three messages each invitation was, in order */
  public List<String> inviteTouches() {
    return new ArrayList<>(touches);
  }

  /** the rendered HTML of every invitation, newest last */
  public List<String> inviteHtml() {
    return new ArrayList<>(html);
  }

  public List<String> inviteSubjects() {
    return new ArrayList<>(subjects);
  }

  @Override
  public Outcome sendRegistrationCode(Envelope envelope, String code) {
    return record("register", envelope, code, null);
  }

  @Override
  public Outcome sendLoginCode(Envelope envelope, String code) {
    return record("login", envelope, code, null);
  }

  @Override
  public Outcome sendPasswordReset(Envelope envelope, String code, String link) {
    return record("reset", envelope, code, link);
  }

  @Override
  public Outcome sendTwoFactorCode(Envelope envelope, String code) {
    return record("two_factor", envelope, code, null);
  }

  @Override
  public Outcome sendPasswordChanged(Envelope envelope) {
    return record("password_changed", envelope, null, null);
  }








  private Outcome record(String flow, Envelope envelope, String code, String link) {
    sent.add(new Sent(flow, envelope.domain(), envelope.email(), code, link, null, null));
    return Outcome.ok("captured");
  }

  /** the most recent code sent to an address, or null if nothing was sent to it */
  public String lastCodeFor(String email) {
    for (int k = sent.size() - 1; k >= 0; k--) {
      Sent message = sent.get(k);
      if (message.email().equalsIgnoreCase(email) && message.code() != null) {
        return message.code();
      }
    }
    return null;
  }

  public Sent last() {
    return sent.isEmpty() ? null : sent.get(sent.size() - 1);
  }

  public List<Sent> all() {
    return new ArrayList<>(sent);
  }

  public List<Sent> forFlow(String flow) {
    return sent.stream().filter(message -> message.flow().equals(flow)).toList();
  }

  public int count() {
    return sent.size();
  }

  public void clear() {
    sent.clear();
    html.clear();
    subjects.clear();
    touches.clear();
  }

  public record Sent(String flow, String domain, String email, String code, String link,
                     String pixel, String note) {
  }

  public Sent lastInvite() {
    for (int k = sent.size() - 1; k >= 0; k--) {
      if (sent.get(k).flow().equals("invite")) {
        return sent.get(k);
      }
    }
    return null;
  }
}
