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

  content_read("Content", "See pages, templates and tables"),
  /**
   * Write the site: pages, templates, navigation, files.
   *
   * <b>One permission where there were four.</b> `templates_write`, `navigation_write` and
   * `attachments_write` were separate when this was a community server and somebody might be handed
   * the camera without the website. At one person and a few friends nobody is ever granted three of
   * these and not the fourth -- and a split nobody uses is not a smaller blast radius, it is three
   * more checkboxes on a screen and three more things to get wrong.
   */
  content_write("Content", "Write pages, templates, navigation and files"),
  content_publish("Content", "Publish a page, or take one down"),
  /**
   * Still its own, and the reason is unchanged by the consolidation above.
   *
   * Dropping a table deletes rows a program was reading and a page has never been able to delete
   * anything. Somebody trusted to write the site is not automatically somebody trusted to throw
   * away what it collected -- that is a difference in what can be lost, not a difference in job.
   */
  tables_write("Content", "Make and change the tables a dynamic page reads"),

  people_read("People", "See who is here"),
  /**
   * Approve, disable, reject, ban: two permissions where there were three.
   *
   * `people_approve` and `people_remove` were split so a greeter could let somebody in without
   * being able to throw them out. At this scale nobody is ever handed one and not the other.
   *
   * Giving roles is deliberately NOT in here -- see {@link #people_roles}.
   */
  people_manage("People", "Approve, disable, reject and ban"),

  /**
   * Give and take away roles, and it stays separate from everything else about people.
   *
   * <b>This is the escalation path, and merging it away was a mistake caught by the test that
   * exists to catch it.</b> Somebody who can grant roles can grant themselves one holding every
   * permission there is -- an administrator in all but the word, reached sideways. That is why
   * "nobody may grant a permission they do not hold" is an invariant and why this is the one
   * People permission that does not fold into approving somebody.
   */
  people_roles("People", "Give and take away roles"),

  /**
   * What this place is and how it reads: settings, colours, the legal pages, the wording of email.
   *
   * <b>Three permissions became one because they are one decision at this scale.</b> The split
   * between the palette and the terms of service was for a community where a designer might be
   * given one and not the other. Here they are the same afternoon's work by the same person.
   *
   * It still reaches nothing in the config file. Sign-in policy, credentials and what a program may
   * do are the operator's and are not editable from a browser at all, so this is not a way to
   * become an administrator and there is nothing behind it that could be.
   */
  config_write("Look", "Settings, appearance, the legal pages and the wording of email"),

  /**
   * May this person connect an assistant of their own?
   *
   * <b>Now a baseline, and that is a deliberate reversal.</b> It was a permission because a
   * connection is a standing credential held by somebody else's software that can act as this
   * person for a month -- which is true, and was the right default when this hosted a community of
   * strangers waiting to be approved.
   *
   * It is not the right default here. Agents are how everybody who is not the owner uses this at
   * all: the voting only works if friends' assistants can vote, and requiring a role grant per
   * friend means the feature does not work until somebody remembers a screen. Approval is already
   * the boundary -- a human decided this person belongs -- and an agent can still only do what its
   * person can do.
   *
   * It stays a named permission rather than disappearing, because {@code Access.can} is the one
   * question the whole surface asks and an agent has to be held to it. What is gone is the
   * ceremony, not the check. Taking it away from one person individually is no longer possible;
   * the tool for that is disabling the account or a ban.
   */
  agent_connect("System", "Connect an assistant that acts as you"),

  /** the system screens, including the log of what every agent has done */
  system_read("System", "See the machine, the event bus, analytics, caches, logs and the AI log"),

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
   * <b>One thing: connect an assistant.</b> Agents are how everybody who is not the owner uses this
   * at all, and a feature that needs a role grant per friend before it works is a feature that does
   * not work. Approval is the boundary -- a human decided this person belongs -- and what an agent
   * can do is exactly what its person can do, which for a friend is: vote, and read what they were
   * shown.
   *
   * <b>A baseline is not the same as a hole.</b> It is named, checkable, and answered by the same
   * {@code Access.can} the rest of the surface asks, which is what holds an agent to its person's
   * limits. What it buys is not restriction; it is that there is one question rather than two.
   *
   * The membership of this set is deliberately tiny, and everything that acts on somebody else's
   * anything stays out of it.
   */
  public static final java.util.Set<Permission> MEMBER_BASELINE =
      java.util.Collections.unmodifiableSet(java.util.EnumSet.of(agent_connect));

  /** is this one of the things being approved is enough for? */
  public boolean isMemberBaseline() {
    return MEMBER_BASELINE.contains(this);
  }

  /** null rather than an exception: an unknown name in a stored role is data, not a crash */
  /**
   * What a permission used to be called, so a role saved before the consolidation still works.
   *
   * <b>Renaming a permission silently un-grants it, which is the worst failure available here.</b>
   * A stored role holds names; nine of those names stopped existing, and without this map a role
   * called "helper" would come back from the database missing most of what it was given -- no
   * error, no log line, just somebody who can no longer do their job. The same reasoning as
   * `Column.renamedFrom`, for the same reason: the old name is data that already exists.
   */
  private static final java.util.Map<String, Permission> RENAMED = java.util.Map.of(
      "templates_write", content_write,
      "navigation_write", content_write,
      "attachments_write", content_write,
      "people_approve", people_manage,
      "people_remove", people_manage,
      "appearance_write", config_write,
      "legal_write", config_write,
      // the AI screen is a system screen now; managing connectors is reading the log and revoking
      "ai_manage", system_read);

  public static Permission of(String raw) {
    if (raw == null) {
      return null;
    }
    String name = raw.trim().toLowerCase();
    try {
      return valueOf(name);
    } catch (IllegalArgumentException ex) {
      return RENAMED.get(name);
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
    // The baseline is the exception to "anything implies admin_enter", and it matters: connecting
    // an assistant is not an admin-section thing. What an agent can do is what its person can do,
    // and a checkbox about that must not hand somebody the admin shell as a side effect.
    java.util.EnumSet<Permission> also = isMemberBaseline()
        ? java.util.EnumSet.of(this) : java.util.EnumSet.of(admin_enter, this);
    switch (this) {
      // writing anything about the site implies being able to see it
      case content_write, content_publish, tables_write -> also.add(content_read);
      // acting on somebody implies being able to see who is here
      case people_manage, people_roles -> also.add(people_read);
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
        // offered permission has to be asked for somewhere that matters (invariant 35) -- and its
        // opposite is just as true: a box that cannot change the answer teaches whoever ticks it
        // that this screen does not work.
        continue;
      }
      groups.computeIfAbsent(permission.group, key -> new java.util.ArrayList<>()).add(permission);
    }
    return groups;
  }
}
