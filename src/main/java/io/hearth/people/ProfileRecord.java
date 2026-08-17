package io.hearth.people;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** What somebody says about themselves. */
public record ProfileRecord(long id, long userId, String displayName, String headline, String about,
                            String location, String links, int orientationStep,
                            Timestamp createdAt, Timestamp updatedAt) {

  /** the step somebody has reached when there is nothing left of the welcome to do */
  public static final int ORIENTED = 3;

  /** an empty profile for somebody who has not written one yet */
  public static ProfileRecord blank(long userId) {
    return new ProfileRecord(0, userId, "", "", "", "", "", 0, null, null);
  }

  /** have they been all the way through the welcome? */
  public boolean oriented() {
    return orientationStep >= ORIENTED;
  }

  /** has this person actually written anything, or is the row just a placeholder? */
  public boolean isFilledIn() {
    return !displayName.isBlank() || !about.isBlank() || !headline.isBlank();
  }

  /** what to call them; falls back to nothing rather than leaking an email address */
  public String nameOr(String fallback) {
    return displayName.isBlank() ? fallback : displayName;
  }

  /** links are one per line, so an admin sees them without a second table */
  public List<String> linkList() {
    ArrayList<String> out = new ArrayList<>();
    for (String line : links.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && trimmed.length() <= 256) {
        out.add(trimmed);
      }
    }
    return out;
  }
}
