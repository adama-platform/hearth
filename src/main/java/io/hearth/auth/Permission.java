package io.hearth.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The things somebody can be allowed to do.
 *
 * A closed enum rather than free strings, because a permission that can be invented at a call site
 * is a permission nobody can audit. Adding one means adding it here, which means the list of what
 * this server lets anybody do is one screen long and readable in one sitting -- and a role editor
 * can offer every one of them as a checkbox without knowing what any of them mean.
 *
 * Named after the job rather than the table. `content_publish` is a thing somebody does; a
 * permission called `content_update_published_column` would be a schema detail leaking into a
 * screen that volunteers have to understand.
 *
 * The grouping is for the role editor and for nothing else. It has no effect on what is allowed.
 */
public enum Permission {
  /** see the admin section at all; every other permission implies it */
  admin_enter("Admin", "Reach the admin section"),

  content_read("Content", "See pages and their history"),
  content_write("Content", "Write and edit pages"),
  content_publish("Content", "Publish a page, or take one down"),
  templates_write("Content", "Write templates and the fields they ask for"),
  navigation_write("Content", "Arrange the navigation"),
  attachments_write("Content", "Upload files, and organise or remove what is there"),

  people_read("People", "See the member list and read profiles"),
  people_approve("People", "Approve somebody waiting to join"),
  people_remove("People", "Reject, disable or ban somebody"),
  people_roles("People", "Give and take away roles"),



  /**
   * Change what this community is, as opposed to how the machine it runs on is set up.
   *
   * Its own permission rather than folded into {@code appearance_write} because the two are not the
   * same decision: the colours are decoration, and this is the name, the clock, which parts of the
   * product exist and what every invitation says. A community might well hand somebody the palette
   * without handing them the switch that turns the board off.
   *
   * It reaches nothing in the config file. Sign-in policy, credentials and what a program may do are
   * the operator's and are not editable from a browser at all -- so this is not a way to become an
   * administrator, and there is nothing behind it that could be.
   */
  config_write("Look", "Change the community's settings, and run the setup"),

  appearance_write("Look", "Choose the colours the site, the emails and the legal pages use"),
  legal_write("Look", "Write the terms of service and the privacy policy"),

  /**
   * May this person connect an assistant of their own?
   *
   * <b>Deliberately not a baseline, and deliberately not admin-only.</b> A connection is a standing
   * credential held by somebody else's software that can act as this person for a month, so it is
   * not something to hand out by being approved. But holding it is not administering anything --
   * what an agent can do is exactly what its person can do -- so it is not `ai_manage` either.
   *
   * `ai_manage` is the *screen*: seeing every connector in the community and revoking one.
   * This is the ability to have one at all.
   */
  agent_connect("System", "Connect an assistant that acts as you"),

  system_read("System", "See the event bus, analytics, caches and logs"),
  ai_manage("System", "Connect and disconnect models"),

  /** the god bit; only the built-in admin role has it, and it answers yes to everything */
  everything("Admin", "Everything, always");

  public final String group;
  public final String label;

  Permission(String group, String label) {
    this.group = group;
    this.label = label;
  }

  /**
   * What every approved member can do without anybody granting them anything.
   *
   * <b>A baseline is not the same as a hole.</b> Every other permission here gates a *screen* --
   * something a community deliberately hands to a few people. These three gate the board, which is
   * the thing most members are here for; if they worked like the rest, an upgrade would leave every
   * community with a board only its administrators could read, and the fix would be a role for
   * everybody, which is a permission system with one row in it.
   *
   * So they are named, checkable and enforced -- and answered yes for anybody the community has
   * approved. What that buys is not restriction, it is that `Access.can` is the one question the
   * board asks too, and that <b>an agent acting for somebody is held to what that person may
   * do</b>. A connection held by a member cannot moderate; a connection held by somebody without
   * `calendar_write` cannot put up a vote that turns into an event. Before this, the board asked
   * "are you approved" and a model asked nothing at all.
   *
   * The membership of this set is deliberately tiny. Anything that can be done to *somebody else's*
   * words -- moderating -- is not in it.
   */
  public static final java.util.Set<Permission> MEMBER_BASELINE =
      java.util.Collections.unmodifiableSet(java.util.EnumSet.noneOf(Permission.class));

  /** is this one of the things being approved is enough for? */
  public boolean isMemberBaseline() {
    return MEMBER_BASELINE.contains(this);
  }

  /** null rather than an exception: an unknown name in a stored role is data, not a crash */
  public static Permission of(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.trim().toLowerCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * What ticking this box also has to mean.
   *
   * Writing implies reading, and every permission implies being able to reach the admin section.
   * Without this, an "editor" ticked for `content_write` gets a section they cannot open -- the
   * permission is real and the door is shut, which looks exactly like a bug and is impossible to
   * diagnose from the role editor.
   *
   * Deliberately a closure over a small explicit list rather than a hierarchy. A tree of permissions
   * is the thing every permission system grows and nobody can reason about afterwards.
   */
  public java.util.Set<Permission> implies() {
    // The baseline three are the exception to "anything implies admin_enter", and it matters: they
    // gate the board, which is not in the admin section, and a role granting one of them would
    // otherwise hand somebody the admin shell as a side effect of a checkbox about a message board.
    // Connecting an assistant is not an admin-section thing either: what it can do is what its
    // person can do, and a checkbox about that must not hand somebody the admin shell.
    java.util.EnumSet<Permission> also = isMemberBaseline() || this == agent_connect
        ? java.util.EnumSet.of(this) : java.util.EnumSet.of(admin_enter, this);
    switch (this) {
      case content_write, content_publish, templates_write,
           navigation_write -> {
        also.add(content_read);
        if (this == content_write) {
          also.add(attachments_write);
        }
      }
      case people_approve, people_remove, people_roles -> also.add(people_read);
      // moderating implies reading, and moderating is the one of the four that is not a baseline:
      // it acts on somebody else's words
      // somebody who writes pages can put a photograph in one; the reverse is deliberately not
      // true, so a community can hand somebody the camera without handing them the website
      case attachments_write -> also.add(content_read);
      // keeping a section implies being able to keep it tidy; the reverse is deliberately not
      // true, so a community can hand somebody the moderating without handing them the editing
      // somebody who administers the connectors can obviously have one; the reverse is the whole
      // point of the split and is deliberately not true
      case ai_manage -> also.add(agent_connect);
      default -> {
      }
    }
    return also;
  }

  /** for the role editor: the permissions in order, gathered under their headings */
  public static Map<String, java.util.List<Permission>> byGroup() {
    LinkedHashMap<String, java.util.List<Permission>> groups = new LinkedHashMap<>();
    for (Permission permission : values()) {
      if (permission == everything) {
        // never offered as a checkbox; it belongs to the built-in admin role and nothing else
        continue;
      }
      if (permission.isMemberBaseline()) {
        // Every approved member already has these, so a checkbox for one grants nothing. An
        // offered permission has to be asked for somewhere that matters (invariant 147) -- and its
        // opposite is just as true: a box that cannot change the answer teaches whoever ticks it
        // that this screen does not work.
        continue;
      }
      groups.computeIfAbsent(permission.group, key -> new java.util.ArrayList<>()).add(permission);
    }
    return groups;
  }
}
