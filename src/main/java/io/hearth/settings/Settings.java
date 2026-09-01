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
      Setting.words("disabled", SURFACES, "Parts to switch off",
          "One per line. Everything is on until it is named here, because the decision worth"
              + " writing down is the refusal. Switching something off stops its pages answering"
              + " and takes it out of the navigation, everywhere at once. A word this server does"
              + " not recognise is refused rather than ignored, because a typo here is a part"
              + " somebody believes is off and is not.",
          surfaceNames()),

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
            keys("name", "timezone")),
        new Step("What this community has", "Everything is on. Turn off what this group is not"
            + " going to use, so nobody meets a page nobody maintains.",
            keys("disabled")));
  }

  /**
   * What this key is actually set to right now, as text a form box can hold.
   *
   * Read back off the live {@link io.hearth.vhost.DomainConfig} rather than out of the settings
   * table, and that is the whole point of it: the table says what somebody typed, and this says
   * what the community is running on. They differ wherever a value is coming from the config file
   * or from a built-in default, which is most of them on most installs.
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
      case "disabled" -> joinWords(config.disabled);
      default -> "";
    };
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
