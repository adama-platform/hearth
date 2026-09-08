package io.hearth.inbox;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.push.PushSubs;
import io.hearth.push.WebPush;

/**
 * The notification that goes out when mail lands, and the first thing in this server that produces
 * one.
 *
 * <b>Push having no producer was written down as a gap for as long as this project has had push.</b>
 * Subscribing worked, the keypair worked, the worker worked and the self-test worked, and nothing
 * ever generated a notification, because the two features that would have -- a board and a calendar
 * -- were removed. Mail arriving is the thing that was always going to fill it: it is the one event
 * on this machine that is genuinely worth interrupting somebody for.
 *
 * <b>Who and where, never what.</b> A push crosses a push service run by Google or Mozilla and
 * lands on a lock screen that anybody in the room can read. So it says who wrote and carries a link
 * to the message, and it does not say the subject -- which is the thing somebody would most want
 * and is exactly what the rule exists to keep off a lock screen. The subject is one tap away, on
 * this server, behind their session.
 *
 * <b>A failure here never fails a delivery.</b> The message is already stored; a push service
 * having a bad afternoon is not a reason to make a sending server retry.
 */
public class PushOnArrival implements Delivery.Notifier {
  private final Verbose verbose;

  public PushOnArrival(Verbose verbose) {
    this.verbose = verbose;
  }

  @Override
  public void arrived(Accounts accounts, long userId, long messageId, String from,
                      String selfUrl) {
    try {
      java.util.List<PushSubs.Sub> subs = accounts.pushSubs.forUser(userId);
      if (subs.isEmpty()) {
        return;
      }
      // The tag is the whole inbox rather than the message.
      //
      // A browser replaces a notification carrying the same tag, so ten messages arriving while
      // somebody is out become one line saying the most recent -- rather than ten stacked
      // notifications, which is what makes people turn a feature like this off within a week.
      WebPush.Message push = new WebPush.Message("New mail",
          from == null || from.isBlank() ? "Something arrived." : from + " wrote to you.",
          selfUrl + "/mail/" + messageId, "mail", userId);
      int sent = 0;
      for (PushSubs.Sub sub : subs) {
        WebPush.Outcome outcome = new WebPush(verbose).send(sub, push, "mailto:no-reply@localhost");
        if (outcome.delivered()) {
          accounts.pushSubs.recordSuccess(sub.id());
          sent++;
        } else if (outcome.gone()) {
          // the browser threw the subscription away; so do we, rather than retrying it forever
          accounts.pushSubs.recordFailure(sub.id(), true, outcome.detail());
        }
      }
      if (sent > 0) {
        accounts.pushLedger.sent(userId, System.currentTimeMillis());
      }
    } catch (Exception ex) {
      // the message is already stored, and a push service is somebody else's infrastructure
      verbose.detail(() -> "inbox: no notification went out -- " + ex.getMessage());
    }
  }

  /** what a test uses when it wants delivery without a push service */
  public static Delivery.Notifier none() {
    return (accounts, userId, messageId, from, selfUrl) -> {
    };
  }
}
