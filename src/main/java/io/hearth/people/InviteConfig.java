package io.hearth.people;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `invites` block: what an invitation says, and when it says it again.
 *
 * Set once for the whole community so that inviting somebody is *just an email address*. An invite
 * flow that asks for a subject line and a body every time is one nobody uses twice, and a community
 * whose invitations all read differently is one that looks like it is run by nobody.
 *
 * <h2>The reminder cadence</h2>
 *
 * Three touches, and the defaults come from published sequence research rather than taste: the
 * usual spacing is 2-4-7 days with a hard floor of a day before the first follow-up, one initial
 * message plus a follow-up around day three and another six or seven days after that. A first
 * follow-up lifts response substantially; a three-step sequence lifts completion by 14-25%.
 *
 * The three are deliberately different in tone, which is the point of having three:
 *
 * <ol>
 *   <li><b>welcome</b> -- here is what this is, and here is your way in.</li>
 *   <li><b>reminder</b> -- friendly, short, assumes it got buried.</li>
 *   <li><b>apology</b> -- the last one, says so, and says how to never hear from us again.</li>
 * </ol>
 *
 * A fourth would be nagging. The third exists to be the last one *and to say that it is*, because
 * an invitation sequence with no visible end is a sequence people mark as spam -- and a spam
 * complaint costs the whole domain rather than the one message.
 */
public class InviteConfig {
  /**
   * The line under the community's name.
   *
   * Deliberately about the shape of the thing rather than its subject: it is true for a supper
   * club, a support group and a games night, and it says the one thing that distinguishes this from
   * every other invitation in somebody's inbox -- that there are people at the end of it.
   */
  public static final String DEFAULT_TAGLINE = "A small community, and a real one.";

  /**
   * What every invitation says this is.
   *
   * Written to be true of most communities that would run this and worth reading by somebody who
   * has never heard of it: what it is, how big, what it is for, and what happens if they ignore it.
   * An admin who wants their own writes one in the config; an admin who never opens that file still
   * sends something a person would answer.
   */
  public static final String DEFAULT_ABOUT =
      "{{community}} is a small community that organises itself here -- what is happening, who is"
          + " coming, and the conversation in between. It is a few hundred people rather than a few"
          + " hundred thousand, everything on it is written by somebody you can reply to, and the"
          + " point of all of it is meeting in person. If it is not for you, ignoring this is"
          + " enough; nothing has been created for you.";

  public final boolean enabled;
  /** may an ordinary member invite somebody, or only people with a role that says so */
  public final boolean membersMayInvite;
  /** how many a member may send in a day, so one enthusiastic person cannot burn the domain */
  public final int memberDailyLimit;

  /** the line under the community name, on every invitation */
  public final String tagline;
  /**
   * What this community is, in a sentence or two; the body of the welcome.
   *
   * <b>This has a default, and the default is the point.</b> Inviting somebody has to be one
   * address in one box -- an invite flow that asks for a subject and a body every time is one
   * nobody uses twice, and a community whose invitations all read differently is one that looks
   * like it is run by nobody. So there is one message for the whole community, written once, and
   * it ships filled in rather than blank: a blank field is a decision every admin has to make on
   * their first day, in a box, about copywriting.
   *
   * `{{community}}` becomes the community's name wherever it appears, so the default is true
   * without anybody editing it and stays true after a rename.
   */
  public final String about;
  /** what the button says */
  public final String callToAction;
  /** who it is from, in the sign-off */
  public final String signOff;

  /** days after sending before the friendly reminder */
  public final int reminderAfterDays;
  /** days after the reminder before the last one */
  public final int apologyAfterDays;
  /** whether to send reminders at all */
  public final boolean remindersEnabled;

  public static InviteConfig defaults() {
    return new InviteConfig();
  }

  private InviteConfig() {
    this.enabled = true;
    this.membersMayInvite = true;
    this.memberDailyLimit = 5;
    this.tagline = DEFAULT_TAGLINE;
    this.about = DEFAULT_ABOUT;
    this.callToAction = "Accept the invitation";
    this.signOff = "";
    this.reminderAfterDays = 3;
    this.apologyAfterDays = 7;
    this.remindersEnabled = true;
  }

  public InviteConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.membersMayInvite = config.boolOf("members-may-invite", true);
    this.memberDailyLimit = atLeast(config, "member-daily-limit", 5, 0);
    this.tagline = config.strOf("tagline", DEFAULT_TAGLINE);
    this.about = config.strOf("about", DEFAULT_ABOUT);
    this.callToAction = config.strOf("call-to-action", "Accept the invitation");
    this.signOff = config.strOf("sign-off", "");
    this.remindersEnabled = config.boolOf("reminders", true);
    // A day is the floor for both, from the same research the defaults come from: a follow-up that
    // arrives the same afternoon reads as a system rather than a person.
    this.reminderAfterDays = atLeast(config, "reminder-after-days", 3, 1);
    this.apologyAfterDays = atLeast(config, "apology-after-days", 7, 1);
    config.assertKnownKeys();
  }

  /** the community's name, filled in wherever the text mentions it */
  public String aboutFor(String community) {
    return fill(about, community);
  }

  public String taglineFor(String community) {
    return fill(tagline, community);
  }

  /**
   * One substitution and no template engine.
   *
   * This text is edited by administrators in a config file; anything that could *evaluate* what
   * they typed would be a way to reach the model from a text field, and a literal replace cannot do
   * anything but replace.
   */
  private static String fill(String text, String community) {
    if (text == null) {
      return "";
    }
    return text.replace("{{community}}", community == null || community.isBlank()
        ? "This community" : community);
  }

  /** how many days after the previous touch this one is due; 0 means there is no next one */
  public int daysUntilTouch(int touchesSoFar) {
    if (!remindersEnabled) {
      return 0;
    }
    return switch (touchesSoFar) {
      case 1 -> reminderAfterDays;
      case 2 -> apologyAfterDays;
      default -> 0;
    };
  }

  public String describe() {
    if (!enabled) {
      return "off";
    }
    if (!remindersEnabled) {
      return "one message, no reminders";
    }
    return "welcome, reminder after " + reminderAfterDays + "d, last after "
        + apologyAfterDays + "d more";
  }

  private static int atLeast(ConfigObject config, String key, int fallback, int floor)
      throws ConfigException {
    int value = config.intOf(key, fallback);
    if (value < floor) {
      throw new ConfigException("invites." + key + " must be at least " + floor);
    }
    return value;
  }
}
