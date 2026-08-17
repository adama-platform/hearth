package io.hearth.vhost;

import io.hearth.common.ConfigException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The parts of this program a community can switch off in one word.
 *
 * Every one of these already has its own block with its own settings, and every block already has
 * an `enabled` flag. This exists because that is the wrong shape for the decision an operator
 * actually makes. "We do not want a chat" is not a chat setting; it is a decision about what this
 * community is, taken once, and it should read that way in the file:
 *
 * <pre>
 *   { "disabled": ["chat", "places"] }
 * </pre>
 *
 * <b>Everything is on unless it is named here.</b> A community that has thought about none of this
 * gets all of it, which is the right default for a program whose whole argument is that a group of
 * two hundred people should not have to assemble their own software. Turning something off is the
 * decision worth writing down, so that is the one that takes a line.
 *
 * A name nobody recognises is fatal at boot rather than ignored. A typo in this list is a surface
 * somebody believes is off and is not, which is the worst possible outcome for a list whose entire
 * purpose is turning things off.
 */
public enum Surface {
  /** the discussion board */
  board,
  /** what is happening, and who is coming */
  calendar,
  /** the address book */
  places,
  /** the directory of who else is here */
  members,
  /** the community's standing questions, and the welcome that starts them */
  survey,
  /** projects, things to do, routines, and what was recorded against them */
  tasks,
  /** inviting people by email */
  invites,
  /** the installable shell and push notifications */
  app,
  /** the endpoint a model connects to */
  ai,
  /** the endpoint a program connects to, holding somebody's own token */
  api,
  /** when people can actually come, and the grid that adds it up */
  availability,
  /** uploaded files: photographs, video, the PDF of the menu */
  attachments;

  public static Surface of(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.trim().toLowerCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** the list an operator wrote, checked; an unknown name stops the server */
  public static Set<Surface> parse(String domain, List<String> names) throws ConfigException {
    EnumSet<Surface> off = EnumSet.noneOf(Surface.class);
    for (String name : names) {
      Surface surface = of(name);
      if (surface == null) {
        throw new ConfigException(domain + ": disabled lists '" + name
            + "', which is not something this server has. It knows about " + all() + ".");
      }
      off.add(surface);
    }
    return off;
  }

  public static String all() {
    StringBuilder out = new StringBuilder();
    for (Surface surface : values()) {
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(surface.name());
    }
    return out.toString();
  }
}
