package io.hearth.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clumping a long conversation so it stays readable.
 *
 * Comments do not expire -- a thread is the community's memory of what it decided, and a memory
 * with a fortnight's horizon is not one. What a two-year-old thread needs instead is for the four
 * hundred replies nobody has read since March to be *one line* rather than four hundred, with the
 * recent conversation open underneath.
 *
 * <b>Grouped by the age of the root, never of the reply.</b> Comments are a tree walked in reading
 * order, so bucketing each comment by its own timestamp would scatter a reply away from the thing
 * it replies to -- somebody answering a two-year-old question would appear under "this week" with
 * no question above it. A whole subtree goes wherever its top-level comment went, which keeps every
 * conversation intact and puts it where somebody would look for it.
 *
 * <b>Oldest first, because that is how a thread reads.</b> So the collapsing happens at the top:
 * you arrive at the recent conversation with "247 earlier replies" above it, which is the shape
 * every long thread wants and almost none have.
 */
public final class CommentGroups {
  /** below this many, a thread is just a thread and everything is open */
  public static final int EXPAND_BELOW = 20;
  /** always leave at least this recent, however the months fall */
  private static final int ALWAYS_OPEN = 2;

  /** one clump: what to call it, whether it arrives folded, and what is in it */
  public record Group(String label, boolean collapsed, int count, List<Board.Comment> comments) {
  }

  private CommentGroups() {
  }

  public static List<Group> of(List<Board.Comment> ordered, long nowMillis) {
    return of(ordered, nowMillis, EXPAND_BELOW);
  }

  /**
   * @param ordered comments in reading order -- path order, so a subtree is contiguous
   * @param expandBelow leave everything open when the thread is smaller than this
   */
  public static List<Group> of(List<Board.Comment> ordered, long nowMillis, int expandBelow) {
    ArrayList<Group> groups = new ArrayList<>();
    if (ordered == null || ordered.isEmpty()) {
      return groups;
    }
    if (ordered.size() < expandBelow) {
      // one group, no label, nothing folded. A short thread that arrived in three labelled boxes
      // would be a worse thread.
      groups.add(new Group("", false, ordered.size(), List.copyOf(ordered)));
      return groups;
    }

    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
    String label = null;
    ArrayList<Board.Comment> current = new ArrayList<>();

    for (Board.Comment comment : ordered) {
      // a top-level comment starts a subtree, and the subtree inherits its bucket. Anything deeper
      // simply continues whatever is open.
      if (comment.depth() == 0) {
        String wanted = labelFor(comment.createdAt(), today, zone);
        if (label != null && !wanted.equals(label)) {
          groups.add(new Group(label, false, current.size(), List.copyOf(current)));
          current.clear();
        }
        label = wanted;
      }
      current.add(comment);
    }
    if (label != null && !current.isEmpty()) {
      groups.add(new Group(label, false, current.size(), List.copyOf(current)));
    }

    // fold everything but the last couple of clumps. The count is on the summary, so what is
    // hidden is always visible as a number.
    ArrayList<Group> folded = new ArrayList<>(groups.size());
    for (int k = 0; k < groups.size(); k++) {
      Group group = groups.get(k);
      boolean collapse = k < groups.size() - ALWAYS_OPEN;
      folded.add(new Group(group.label(), collapse, group.count(), group.comments()));
    }
    return folded;
  }

  /**
   * What to call a clump.
   *
   * Relative near the present and absolute further back, which is how people talk about time: "this
   * week" means something today and nothing in a year, and "March 2026" means the same thing
   * forever.
   */
  static String labelFor(java.sql.Timestamp at, LocalDate today, ZoneId zone) {
    if (at == null) {
      return "Earlier";
    }
    LocalDate when = at.toInstant().atZone(zone).toLocalDate();
    long days = java.time.temporal.ChronoUnit.DAYS.between(when, today);
    if (days <= 0) {
      return "Today";
    }
    if (days == 1) {
      return "Yesterday";
    }
    if (days < 7) {
      return "This week";
    }
    if (days < 31 && when.getMonth() == today.getMonth() && when.getYear() == today.getYear()) {
      return "Earlier this month";
    }
    String month = when.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault());
    return when.getYear() == today.getYear() ? month : month + " " + when.getYear();
  }
}
