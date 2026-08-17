package io.hearth.calendar;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * The `calendar` block: whether a domain has one, and how far back it looks.
 *
 * On by default, like the board. A community that meets is the normal case, and a community that
 * does not simply never has an event on it -- an empty calendar costs one query and says "nothing
 * planned yet", which is a more useful thing for a new community to see than a missing tab.
 */
public class CalendarConfig {
  public final boolean enabled;
  /** how many days of finished events the list still shows */
  public final int pastDays;
  /**
   * May any approved member put an event forward?
   *
   * On by default, and that is a decision about what a community is rather than a convenience. A
   * calendar only an administrator can write to is a programme somebody publishes at a group;
   * a calendar anybody can suggest to is a group deciding what it does. The queue is what makes
   * that safe -- a suggestion is not on the calendar until somebody says yes, so opening the door
   * costs a screen to look at rather than control of the front page.
   */
  public final boolean suggestions;
  /**
   * Send a real calendar invitation when an event is published.
   *
   * <b>On unless a community says otherwise</b>, because the whole argument of this product is that
   * more people turn up. An invitation that lands in a calendar carries its own reminder and sits in
   * the week somebody is already looking at; a link to a page is a thing to remember to click.
   *
   * Off is a reasonable choice for a community whose members do not keep calendars, or one whose
   * mail is somewhere this server cannot receive at -- see {@link #canSend}, which is why turning it
   * on is not enough on its own.
   */
  public final boolean invites;
  /**
   * The address invitations come from and answers go back to.
   *
   * <b>Its own address, not the community's from-address.</b> Everything else this server sends is
   * one-way and can come from `no-reply@`; a calendar invitation is a conversation, and the address
   * on it has to be one somebody's mail client can answer to and this server actually receives at.
   * Mixing the two means either a no-reply address that swallows every acceptance, or a general
   * address whose inbox fills with machine-readable calendar files.
   *
   * Empty means derived: `events@` on the domain of whatever this community already sends as, which
   * is the answer for every community that has not thought about it and needs no migration for the
   * ones that predate the setting.
   */
  public final String eventsAddress;
  /**
   * What that address is called in a mail client.
   *
   * "Example Community Calendar" rather than an address, because this is the name that appears in
   * an invitation's organiser line and in the From of every reminder -- and `events@example.org` in
   * that position tells nobody which community is asking.
   */
  public final String eventsName;
  /**
   * Days before an event to nudge somebody who has not answered.
   *
   * Two of them by default, a week out and the day before, because those are the two moments the
   * answer changes: a week is when somebody can still rearrange, and the day before is when they
   * notice they never replied. More than two is nagging, and a nudge nobody wanted is how a
   * community teaches people to filter its mail.
   */
  public final java.util.List<Integer> remindDaysBefore;
  /** how far into the past a "you said you were coming" attendance question stays askable */
  public final int attendanceDays;

  public static CalendarConfig defaults() {
    return new CalendarConfig();
  }

  private CalendarConfig() {
    this.enabled = true;
    this.pastDays = 90;
    this.suggestions = true;
    this.invites = true;
    this.eventsAddress = "";
    this.eventsName = "";
    this.remindDaysBefore = java.util.List.of(7, 1);
    this.attendanceDays = 30;
  }

  public CalendarConfig(ConfigObject config) throws ConfigException {
    this.enabled = config.boolOf("enabled", true);
    this.pastDays = config.intOf("past-days", 90);
    this.suggestions = config.boolOf("suggestions", true);
    this.invites = config.boolOf("invites", true);
    this.eventsAddress = config.strOf("events-address", "").trim();
    this.eventsName = config.strOf("events-name", "").trim();
    if (!eventsAddress.isEmpty() && eventsAddress.indexOf('@') <= 0) {
      throw new ConfigException("calendar.events-address must be an email address");
    }
    this.attendanceDays = config.intOf("attendance-days", 30);
    java.util.ArrayList<Integer> days = new java.util.ArrayList<>();
    for (String each : config.stringsOf("remind-days-before", new String[]{"7", "1"})) {
      try {
        int day = Integer.parseInt(each.trim());
        if (day > 0 && day <= 365 && !days.contains(day)) {
          days.add(day);
        }
      } catch (NumberFormatException ex) {
        throw new ConfigException("calendar.remind-days-before must be whole numbers of days");
      }
    }
    // furthest out first, so the loop can ask "which of these have we passed" in order
    days.sort(java.util.Comparator.reverseOrder());
    this.remindDaysBefore = java.util.List.copyOf(days);
    if (pastDays < 0) {
      throw new ConfigException("calendar.past-days must be zero or more");
    }
    if (attendanceDays < 0) {
      throw new ConfigException("calendar.attendance-days must be zero or more");
    }
    config.assertKnownKeys();
  }

  public String describe() {
    if (!enabled) {
      return "off";
    }
    return "on, showing " + pastDays + " days of past events"
        + (suggestions ? ", members may suggest" : ", admins only")
        + (invites ? ", sending calendar invitations" : ", no calendar invitations");
  }

  /**
   * Can this community actually send an invitation?
   *
   * <b>Every invitation says where to reply, and a reply is an email to this server.</b> With no
   * inbound mail, a calendar program's "Accept" goes to an address nothing is listening at: the
   * person believes they have answered, the guest list never hears, and the reminder loop chases
   * somebody who did reply. That is worse than not sending invitations at all, so the setting being
   * on is not the same as it being possible, and the admin screen says which is missing.
   */
  public boolean canSend(boolean inboundMailWorks) {
    return enabled && invites && inboundMailWorks;
  }

  /**
   * Where a calendar reply comes back to.
   *
   * <b>Derived rather than defaulted, so an existing community upgrades without touching a file.</b>
   * `events@` on the domain this community already sends mail from -- which is the domain its SPF,
   * DKIM and MX records are already set up for, and therefore the one an address here can actually
   * receive at. Falling back to the site's own domain covers a community with no provider
   * configured, where mail is printed to a terminal and the address is a label rather than a route.
   *
   * @param sendingAddress what this community sends as, e.g. `no-reply@example.org`, or null
   * @param domain the community's own domain, used when there is no sending address to learn from
   */
  public String eventsAddressOr(String sendingAddress, String domain) {
    if (!eventsAddress.isEmpty()) {
      return eventsAddress;
    }
    String at = domain;
    if (sendingAddress != null && sendingAddress.indexOf('@') > 0) {
      at = sendingAddress.substring(sendingAddress.indexOf('@') + 1).trim();
    }
    return "events@" + at;
  }

  /** and what a mail client calls it: the community's name plus the word, unless somebody said */
  public String eventsNameOr(String communityName) {
    if (!eventsName.isEmpty()) {
      return eventsName;
    }
    return (communityName == null || communityName.isBlank() ? "Community" : communityName.trim())
        + " Calendar";
  }
}
