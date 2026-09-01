package io.hearth.web;

import io.hearth.auth.Permission;
import io.hearth.vhost.DomainConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin URL space: which section a path names, and which panel inside it.
 *
 * Two rules shape this, both learned the hard way.
 *
 * **Every sub-view has its own URL.** A panel that refreshes in place -- the event stream, the log
 * results, a filtered listing -- is reachable at a real path, renders the same HTML whether it is
 * fetched on its own or embedded in its section page, and shows up in the access log as itself. The
 * previous design used a `?fragment=1` flag, which is invisible in a log, and which broke outright:
 * mustache escapes `=` to `&#61;`, HTML entities are not decoded inside a script block, so the live
 * button fetched `?fragment&#61;1`, the flag never parsed, and the whole page rendered inside the
 * panel. A path cannot have that problem.
 *
 * **Identity goes in the path, never the query.** `/admin/content/edit/41`, not
 * `/admin/content?edit=41`. Query parameters are for filters -- a view of a list, not the identity
 * of a thing -- and mutations are POST to the section path with no query at all. What is left in
 * the log is readable.
 */
public final class AdminView {
  /** the sidebar, in order, with nesting */
  public enum Section {
    overview("", "Overview", "home", null, Permission.admin_enter),
    people("people", "People", "people", null, Permission.people_read),
    bans("bans", "Bans", "x", people, Permission.people_remove),
    roles("roles", "Roles", "star", people, Permission.people_roles),
    content("content", "Content", "content", null, Permission.content_read),
    templates("templates", "Templates", "template", content, Permission.templates_write),
    directories("templates/directories", "Directories", "logs", content,
        Permission.templates_write),
    navigation("navigation", "Navigation", "logs", content, Permission.navigation_write),
    attachments("attachments", "Files", "content", content, Permission.attachments_write),
    bundles("content/bundles", "Import & export", "logs", content, Permission.content_write),
    unused("attachments/unused", "Unused files", "x", attachments, Permission.attachments_write),
    // The three that decide what this community looks and sounds like, under one heading. They
    // were three top-level entries competing with People and Content for somebody's eye, and none
    // of them is opened weekly -- a colour, a policy and the wording of an email are things
    // somebody sets down once and comes back to on purpose.
    // Top level rather than under Customization, which needs appearance_write: a child whose
    // parent somebody may not open is a sidebar entry that cannot be drawn, and these two are a
    // different decision from the colours anyway -- the name, the clock, and what this community
    // has at all.
    configuration("configuration", "Settings", "star", null, Permission.config_write),
    setup("configuration/setup", "Setup", "star", configuration, Permission.config_write),
    look("look", "Customization", "star", null, Permission.appearance_write),
    appearance("appearance", "Appearance", "star", look, Permission.appearance_write),
    legal("legal", "Legal", "content", look, Permission.legal_write),
    messages("messages", "Messages", "content", look, Permission.legal_write),
    system("system", "System", "analytics", null, Permission.system_read),
    machine("system/machine", "Machine", "analytics", system, Permission.system_read),
    settings("system/settings", "Settings", "star", system, Permission.system_read),
    events("system/events", "Events", "events", system, Permission.system_read),
    analytics("system/analytics", "Analytics", "analytics", system, Permission.system_read),
    caching("system/caching", "Caching", "star", system, Permission.system_read),
    ai("system/ai", "AI", "star", system, Permission.ai_manage),
    logs("system/logs", "Log", "logs", system, Permission.system_read),
    // `everything`, which no other section asks for.
    //
    // This is the only screen that destroys data rather than a row, and there is no undo behind it
    // -- a dropped table is gone from the file. system_read opens the rest of System because
    // reading is what those screens do; this one is a different kind of act and takes the
    // permission that means "all of it".
    cleanup("system/cleanup", "Clean up", "x", system, Permission.everything);

    public final String slug;
    public final String label;
    public final String icon;
    /** the section this one nests under in the sidebar, or null for a top-level entry */
    public final Section parent;
    /** what somebody has to be allowed to do for this section to exist for them */
    public final Permission needs;

    Section(String slug, String label, String icon, Section parent, Permission needs) {
      this.slug = slug;
      this.label = label;
      this.icon = icon;
      this.parent = parent;
      this.needs = needs;
    }

    public String path(DomainConfig config) {
      return slug.isEmpty() ? config.urls.admin : config.urls.admin + "/" + slug;
    }

    /** the template for the whole page */
    public String template() {
      return "admin/" + name();
    }

    /** does this section only exist as a heading over its children? */
    public boolean isHeading() {
      return this == system || this == look;
    }
  }

  /** what a request wants: a section, optionally a panel or a form, optionally about one thing */
  public record Target(Section section, Kind kind, String id) {
    public enum Kind {
      /** the whole page, shell and all */
      page,
      /** just the panel, for an in-place refresh */
      panel,
      /** a create form, on its own page */
      create,
      /** an edit form, on its own page */
      edit,
      /** a single thing, reviewed on its own page */
      review,
      /** everything held about one person, as a file */
      export,
      /** every page and template as one JSON file, for source control and for a backup */
      bundle,
      /** the version history of one thing */
      history,
      /** one version of one thing, rendered for a preview */
      version,
      /** what one version changed, line by line, against the one before it */
      changes
    }

    public boolean isPage() {
      return kind == Kind.page;
    }

    public boolean isForm() {
      return kind == Kind.create || kind == Kind.edit;
    }
  }

  /** sections whose page embeds a refreshable panel, and what that panel's sub-path is called */
  // Map.ofEntries rather than Map.of: the latter caps at ten pairs, and adding the eleventh
  // section is not the moment to discover that.
  private static final Map<Section, String> PANELS = Map.ofEntries(
      Map.entry(Section.people, "list"),
      Map.entry(Section.bans, "list"),
      Map.entry(Section.content, "list"),
      Map.entry(Section.attachments, "list"),
      Map.entry(Section.roles, "list"),
      Map.entry(Section.templates, "list"),
      Map.entry(Section.ai, "actions"),
      Map.entry(Section.events, "stream"),
      Map.entry(Section.caching, "stats"),
      Map.entry(Section.logs, "results"));

  private AdminView() {
  }

  /** the panel path for a section, or null when it has none */
  public static String panelPath(Section section, DomainConfig config) {
    String panel = PANELS.get(section);
    return panel == null ? null : section.path(config) + "/" + panel;
  }

  public static String panelTemplate(Section section) {
    String panel = PANELS.get(section);
    return panel == null ? null : "admin/" + section.name() + "_panel";
  }

  public static boolean hasPanel(Section section) {
    return PANELS.containsKey(section);
  }

  /**
   * Resolve a path under the admin root.
   *
   * Longest slug first, so `system/events` is not mistaken for `system` with a stray segment.
   * Anything that does not resolve is a 404 rather than a guess.
   */
  public static Target resolve(DomainConfig config, String path) {
    String admin = config.urls.admin;
    if (!path.equals(admin) && !path.startsWith(admin + "/")) {
      return null;
    }
    String tail = path.equals(admin) ? "" : path.substring(admin.length() + 1);

    Section best = null;
    for (Section section : Section.values()) {
      if (section.slug.isEmpty()) {
        continue;
      }
      if (tail.equals(section.slug) || tail.startsWith(section.slug + "/")) {
        if (best == null || section.slug.length() > best.slug.length()) {
          best = section;
        }
      }
    }
    if (best == null) {
      return tail.isEmpty() ? new Target(Section.overview, Target.Kind.page, null) : null;
    }
    String rest = tail.equals(best.slug) ? "" : tail.substring(best.slug.length() + 1);
    if (rest.isEmpty()) {
      // a heading has no page of its own; land on its first child instead of showing an empty shell
      if (best.isHeading()) {
        return new Target(firstChildOf(best), Target.Kind.page, null);
      }
      return new Target(best, Target.Kind.page, null);
    }
    String panel = PANELS.get(best);
    if (panel != null && rest.equals(panel)) {
      return new Target(best, Target.Kind.panel, null);
    }
    if (rest.equals("new")) {
      return new Target(best, Target.Kind.create, null);
    }
    if (rest.startsWith("edit/")) {
      String id = rest.substring(5);
      return id.isEmpty() ? null : new Target(best, Target.Kind.edit, id);
    }
    if (rest.startsWith("review/")) {
      String id = rest.substring(7);
      return id.isEmpty() ? null : new Target(best, Target.Kind.review, id);
    }
    if (rest.equals("bundle")) {
      // the whole site as a file. Its own path, so it shows up in the log as itself: taking a copy
      // of everything a community has written is exactly the thing worth being able to see somebody
      // did.
      return new Target(best, Target.Kind.bundle, null);
    }
    if (rest.startsWith("bundle/")) {
      String id = rest.substring(7);
      return id.isEmpty() ? null : new Target(best, Target.Kind.bundle, id);
    }
    if (rest.startsWith("export/")) {
      // everything one person's data amounts to, as a file. Its own path like every other
      // sub-view, so it appears in the log as itself -- and reading somebody's whole record is
      // exactly the thing an operator should be able to see that they did.
      String id = rest.substring(7);
      return id.isEmpty() ? null : new Target(best, Target.Kind.export, id);
    }
    if (rest.startsWith("changes/")) {
      // the review queue's diff, at its own path like every other sub-view
      String which = rest.substring(8);
      return which.isEmpty() ? null : new Target(best, Target.Kind.changes, which);
    }
    if (rest.startsWith("history/")) {
      // history/41 is the list; history/41/version/3 is one version, which the preview fetches.
      // Both are real paths, like every other sub-view here.
      String which = rest.substring(8);
      int changed = which.indexOf("/changes/");
      if (changed > 0) {
        String id = which.substring(0, changed);
        String version = which.substring(changed + 9);
        return id.isEmpty() || version.isEmpty() ? null
            : new Target(best, Target.Kind.changes, id + ":" + version);
      }
      int at = which.indexOf("/version/");
      if (at > 0) {
        String id = which.substring(0, at);
        String version = which.substring(at + 9);
        return id.isEmpty() || version.isEmpty() ? null
            : new Target(best, Target.Kind.version, id + ":" + version);
      }
      return which.isEmpty() ? null : new Target(best, Target.Kind.history, which);
    }
    return null;
  }

  /** does this set of permissions open that door? everything opens every door */
  public static boolean permits(java.util.Set<Permission> allowed, Permission needs) {
    if (allowed == null) {
      return false;
    }
    return allowed.contains(Permission.everything) || needs == null || allowed.contains(needs);
  }

  /**
   * Is this child in the same family as wherever somebody is standing?
   *
   * True when they are on its parent, on it, on one of its siblings, or anywhere deeper -- so the
   * whole branch stays open while somebody is working in it and the rest of the tree stays out of
   * the way.
   */
  static boolean isFamily(Section section, Section active) {
    for (Section at = active; at != null; at = at.parent) {
      if (at == section || at == section.parent) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasVisibleChild(Section heading, java.util.Set<Permission> allowed) {
    for (Section section : Section.values()) {
      if (section.parent == heading && permits(allowed, section.needs)) {
        return true;
      }
    }
    return false;
  }

  private static Section firstVisibleChildOf(Section heading, java.util.Set<Permission> allowed) {
    for (Section section : Section.values()) {
      if (section.parent == heading && permits(allowed, section.needs)) {
        return section;
      }
    }
    return heading;
  }

  private static Section firstChildOf(Section heading) {
    for (Section section : Section.values()) {
      if (section.parent == heading) {
        return section;
      }
    }
    return Section.overview;
  }

  /** the sidebar for a page, with the active item and its ancestors marked */
  public static List<Map<String, Object>> sidebar(DomainConfig config, Section active,
                                                 java.util.Set<Permission> allowed) {
    ArrayList<Map<String, Object>> items = new ArrayList<>();
    for (Section section : Section.values()) {
      // a section somebody cannot open is not greyed out, it is absent. A sidebar full of doors
      // that say no is a worse experience than a small sidebar, and it advertises what is behind
      // them to somebody who was not meant to know.
      if (!permits(allowed, section.needs)) {
        continue;
      }
      if (section.isHeading() && !hasVisibleChild(section, allowed)) {
        continue;
      }
      // A child is drawn only when somebody is somewhere in its family.
      //
      // The sidebar had thirty entries, which is a list nobody reads -- and every one of them was
      // a door into a part of the product that most days is nobody's business. The cost is that
      // reaching Bans means pressing People first, which is one press and is what a person does
      // anyway: they go to People, and then they want the bans. Being *in* a child keeps its
      // siblings visible, so moving between them is one press as well.
      if (section.parent != null && !isFamily(section, active)) {
        continue;
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("label", section.label);
      item.put("href", section.isHeading()
          ? firstVisibleChildOf(section, allowed).path(config) : section.path(config));
      item.put("active", section == active);
      item.put("open", section == active || section == active.parent);
      item.put("child", section.parent != null);
      item.put("icon", Icons.of(section.icon));
      items.add(item);
    }
    return items;
  }
}
