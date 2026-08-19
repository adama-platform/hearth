package io.hearth.settings;

import io.hearth.vhost.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a community may decide about itself from the admin section, as a closed list.
 *
 * <b>The rule for what is in here is what a setting is about, not how hard it is to change.</b>
 * Product and presentation live in the database, where the people running a community can reach
 * them without an SSH key. Anything that decides who gets in, what a credential is, what a program
 * is allowed to do, or how many bytes a request may carry stays in the config file: those are the
 * operator's, they are reviewed by reading a file, and a browser session that has been taken over
 * must not be able to move any of them.
 *
 * Closed, and an enum in spirit for the same reasons {@code Permission} is: the editor draws itself
 * from this list, the wizard picks its steps out of it, the manual's table is generated from the
 * same words, and a setting that could be invented at a call site is one nobody can audit.
 *
 * The keys are the paths those values already had in a config file. That is what lets a value typed
 * into a form be validated by the parser that decides whether the server boots, rather than by a
 * second implementation that would eventually disagree with it.
 */
public final class Settings {
  public static final String COMMUNITY = "The community";
  public static final String SURFACES = "What it has";
  public static final String BOARD = "The board";
  public static final String CALENDAR = "The calendar";
  public static final String INVITES = "Invitations";
  public static final String PLACES = "The address book";
  public static final String AVAILABILITY = "When people are free";
  public static final String SETUP = "Setup";

  /**
   * Whether somebody has been through the walkthrough.
   *
   * The one setting with no path in a config file, because it is not a fact about the community --
   * it is a fact about whether anybody has sat down and told this server what the community is. It
   * exists so the admin section can stop nagging, and so a fresh install has something honest to
   * say instead of pretending the defaults were chosen.
   */
  public static final String SETUP_DONE = "setup.completed";

  private static final List<Setting> ALL = List.of(
      Setting.text("name", COMMUNITY, "What this community is called",
          "Shown in the header, in the browser tab, and as the sender's name on every message this"
              + " server sends. It is the community's name rather than the software's."),
      Setting.text("timezone", COMMUNITY, "The clock this community keeps",
          "An IANA zone like Europe/London or America/Chicago. This is what decides when 'today'"
              + " ends, which evening a Tuesday event is on, and when the nightly jobs run. It is a"
              + " fact about where people meet, not about where the server is -- a box rented in"
              + " another continent would otherwise put every evening a few hours out."),
      Setting.choice("units", COMMUNITY, "Miles or kilometres",
          "How distances are shown: how far members would travel, and how far a place is.",
          List.of("metric", "imperial")),

      Setting.words("disabled", SURFACES, "Parts to switch off",
          "One per line. Everything is on until it is named here, because the decision worth"
              + " writing down is the refusal. Switching something off stops its pages answering"
              + " and takes it out of the navigation, everywhere at once. A word this server does"
              + " not recognise is refused rather than ignored, because a typo here is a part"
              + " somebody believes is off and is not.",
          surfaceNames()),

      Setting.integer("board.expiry-days", BOARD, "How long a conversation lives",
          "Days before a post is swept, or 0 to keep everything for good. A board that keeps"
              + " everything becomes an archive nobody reads and something somebody eventually has"
              + " to think about; one where threads age out stays a conversation."),
      Setting.integer("board.notification-days", BOARD, "How long a notification outlives its thread",
          "Days an unread note stays in somebody's inbox after it was written."),

      Setting.integer("calendar.past-days", CALENDAR, "How long a finished event stays on the list",
          "Days after an event before it drops off the calendar. The page keeps working; this is"
              + " only about what the list shows."),
      Setting.bool("calendar.suggestions", CALENDAR, "Members may suggest events",
          "A suggestion goes to a queue rather than onto the calendar, which is what makes opening"
              + " this door safe: it costs whoever looks after the calendar a screen to read rather"
              + " than control of the front page. A calendar only an administrator can write to is"
              + " a programme published at a group."),
      Setting.bool("calendar.invites", CALENDAR, "Send real calendar invitations",
          "Announcing an event sends a message that draws accept and decline buttons in a mail"
              + " client, and the answers come back by email and become the guest list. This needs"
              + " inbound mail to be working, or people will press Accept and nothing will hear"
              + " them."),
      Setting.numbers("calendar.remind-days-before", CALENDAR, "When to nudge people who have not answered",
          "Days before the event, one per line. Somebody who already said no is never chased."),
      Setting.integer("calendar.attendance-days", CALENDAR, "How long to ask who actually came",
          "Days after an event that the attendance question is still worth asking."),
      Setting.text("calendar.events-name", CALENDAR, "The name on a calendar invitation",
          "What a mail client shows in the From line when an invitation arrives. Defaults to the"
              + " community's name followed by 'Calendar'."),

      Setting.bool("invites.members-may-invite", INVITES, "Members may invite people",
          "When off, only somebody holding a role that says so can write an invitation."),
      Setting.integer("invites.member-daily-limit", INVITES, "How many one member may send in a day",
          "0 for no limit. This is the cheap protection against one enthusiastic member turning"
              + " this community's sending domain into a source of complaints."),
      Setting.bool("invites.reminders", INVITES, "Send the second and third messages",
          "An invitation is one row and up to three messages. Three is the whole sequence and the"
              + " third says it is the last -- a fourth is nagging, and a sequence with no visible"
              + " end is one people report as spam, which costs the whole sending domain rather"
              + " than the one message."),
      Setting.integer("invites.reminder-after-days", INVITES, "Days from the welcome to the reminder",
          "How long to wait before the second message."),
      Setting.integer("invites.apology-after-days", INVITES, "Days from the reminder to the last note",
          "How long to wait before the third and final message."),
      Setting.text("invites.tagline", INVITES, "The line under the community's name",
          "One short line, on every message this server sends. 'Monthly dinners in Kansas City'"
              + " tells somebody what they are being invited to before they read anything else."),
      Setting.multiline("invites.about", INVITES, "A sentence or two about the community",
          "Only on the first message. This is the part that persuades somebody who has never heard"
              + " of you, so it is worth writing once and properly."),
      Setting.text("invites.call-to-action", INVITES, "What the button says",
          "The button in an invitation. 'Accept the invitation' is the default and is hard to"
              + " improve on."),
      Setting.text("invites.sign-off", INVITES, "Who it is from",
          "The name at the end of an invitation. A person's name reads better than a committee's."),

      Setting.text("places.label", PLACES, "What the address book is called",
          "Some communities keep venues, some keep ranches, some keep members' front rooms. This"
              + " is the heading, not the kinds of place inside it."),

      Setting.integer("availability.refresh-hour", AVAILABILITY, "The hour of the nightly pull",
          "0 to 23, in this community's own clock. Members' calendar links are read once a night"
              + " rather than when somebody opens a page, because a page whose speed depends on"
              + " somebody else's server is a page that is sometimes broken."),
      Setting.integer("availability.horizon-days", AVAILABILITY, "How far ahead the grid looks",
          "An hour counts as clear only if it is clear at every occurrence inside this window."
              + " Softening that into an average is how a screen confidently recommends the one"
              + " evening half the group cannot do."),
      Setting.integer("availability.max-links", AVAILABILITY, "Calendar links one member may add",
          "Each link is an outbound request this server makes on a member's behalf, so there is a"
              + " ceiling."),

      Setting.bool(SETUP_DONE, SETUP, "Setup has been completed",
          "Set by the walkthrough when somebody finishes it. While it is off, the admin section"
              + " says so, because a community running entirely on defaults nobody chose is worth"
              + " mentioning once."));

  private static final Map<String, Setting> BY_KEY;

  static {
    LinkedHashMap<String, Setting> map = new LinkedHashMap<>();
    for (Setting setting : ALL) {
      map.put(setting.key(), setting);
    }
    BY_KEY = Map.copyOf(map);
  }

  private Settings() {
  }

  public static List<Setting> all() {
    return ALL;
  }

  public static Setting byKey(String key) {
    return BY_KEY.get(key);
  }

  public static boolean isKnown(String key) {
    return BY_KEY.containsKey(key);
  }

  /**
   * The one setting that is not a fact about the community, so nothing tries to write it into a
   * config file's shape.
   */
  public static boolean isMeta(String key) {
    return SETUP_DONE.equals(key);
  }

  /** the groups, in the order the editor should show them */
  public static List<String> groups() {
    ArrayList<String> groups = new ArrayList<>();
    for (Setting setting : ALL) {
      if (!groups.contains(setting.group()) && !SETUP.equals(setting.group())) {
        groups.add(setting.group());
      }
    }
    return groups;
  }

  public static List<Setting> inGroup(String group) {
    ArrayList<String> ignored = new ArrayList<>();
    ArrayList<Setting> out = new ArrayList<>();
    for (Setting setting : ALL) {
      if (setting.group().equals(group)) {
        out.add(setting);
      }
    }
    ignored.clear();
    return out;
  }

  /**
   * The walkthrough, as the settings it asks about in the order it asks.
   *
   * Deliberately a small subset. A wizard that asked about everything would be the editor with a
   * Next button, and somebody would click through twenty screens without reading any of them --
   * these are the handful where the default is a guess about a community this software has never
   * met, and where being wrong is visible to everybody.
   */
  public static List<Step> walkthrough() {
    return List.of(
        new Step("Who you are", "The name and the clock. Everything else on this server refers back"
            + " to these two, including every message it sends.",
            keys("name", "timezone", "units")),
        new Step("What this community has", "Everything is on. Turn off what this group is not"
            + " going to use, so nobody meets a page nobody maintains.",
            keys("disabled")),
        new Step("How people find out", "The lines that go on every invitation and every message."
            + " These are the ones worth writing properly, because they are what somebody who has"
            + " never heard of you reads first.",
            keys("invites.tagline", "invites.about", "invites.sign-off",
                "invites.call-to-action")),
        new Step("Getting together", "How long a conversation lives, and whether members may put"
            + " an event forward.",
            keys("board.expiry-days", "calendar.suggestions", "calendar.invites")));
  }

  /**
   * What this key is actually set to right now, as text a form box can hold.
   *
   * Read back off the live {@link io.hearth.vhost.DomainConfig} rather than out of the settings
   * table, and that is the whole point of it: the table says what somebody typed, and this says
   * what the community is running on. They differ wherever a value is coming from the config file
   * or from a built-in default, which is most of them on most installs -- and an editor that showed
   * empty boxes for those would be an editor that told you nothing about your own community.
   *
   * A switch rather than reflection, because a setting whose display value came from a field name
   * would go quietly blank the day somebody renamed the field.
   */
  public static String currentValue(io.hearth.vhost.DomainConfig config, String key) {
    if (config == null) {
      return "";
    }
    return switch (key) {
      case "name" -> config.name;
      case "timezone" -> config.zone.getId();
      case "units" -> config.imperial ? "imperial" : "metric";
      case "disabled" -> joinWords(config.disabled);
      case "board.expiry-days" -> Integer.toString(config.board.expiryDays);
      case "board.notification-days" -> Integer.toString(config.board.notificationDays);
      case "calendar.past-days" -> Integer.toString(config.calendar.pastDays);
      case "calendar.suggestions" -> Boolean.toString(config.calendar.suggestions);
      case "calendar.invites" -> Boolean.toString(config.calendar.invites);
      case "calendar.remind-days-before" -> joinWords(config.calendar.remindDaysBefore);
      case "calendar.attendance-days" -> Integer.toString(config.calendar.attendanceDays);
      case "calendar.events-name" -> orEmpty(config.calendar.eventsName);
      case "invites.members-may-invite" -> Boolean.toString(config.invites.membersMayInvite);
      case "invites.member-daily-limit" -> Integer.toString(config.invites.memberDailyLimit);
      case "invites.reminders" -> Boolean.toString(config.invites.remindersEnabled);
      case "invites.reminder-after-days" -> Integer.toString(config.invites.reminderAfterDays);
      case "invites.apology-after-days" -> Integer.toString(config.invites.apologyAfterDays);
      case "invites.tagline" -> orEmpty(config.invites.tagline);
      case "invites.about" -> orEmpty(config.invites.about);
      case "invites.call-to-action" -> orEmpty(config.invites.callToAction);
      case "invites.sign-off" -> orEmpty(config.invites.signOff);
      case "places.label" -> orEmpty(config.places.label);
      case "availability.refresh-hour" -> Integer.toString(config.availability.refreshHour);
      case "availability.horizon-days" -> Integer.toString(config.availability.horizonDays);
      case "availability.max-links" -> Integer.toString(config.availability.maxLinks);
      default -> "";
    };
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String joinWords(java.util.Collection<?> items) {
    StringBuilder out = new StringBuilder();
    for (Object item : items) {
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(item);
    }
    return out.toString();
  }

  private static List<Setting> keys(String... names) {
    ArrayList<Setting> out = new ArrayList<>();
    for (String name : names) {
      Setting setting = BY_KEY.get(name);
      if (setting != null) {
        out.add(setting);
      }
    }
    return out;
  }

  private static List<String> surfaceNames() {
    ArrayList<String> names = new ArrayList<>();
    for (Surface surface : Surface.values()) {
      names.add(surface.name());
    }
    return names;
  }

  /** one screen of the walkthrough */
  public record Step(String title, String blurb, List<Setting> settings) {
  }
}
