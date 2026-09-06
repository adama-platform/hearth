package io.hearth.tasks;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;
import io.hearth.mail.Mailer;
import io.hearth.vhost.DomainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What is on today, sent once each morning, and only when there is something.
 *
 * <b>Nothing on the docket means no email, and that is the feature.</b> A daily message that arrives
 * whether or not it has anything to say gets filtered within a fortnight, and then the one that
 * mattered goes into the same folder. The check is on `today` alone: things coming up are worth
 * mentioning when you are already being written to, and are not worth an email of their own.
 *
 * <b>Sent once per person per day, tracked in memory.</b> A row per send would be a table that only
 * ever grows to answer a question that stops mattering at midnight. Losing it in a restart means at
 * worst a second copy on a day the server was restarted between the two, which is a far smaller
 * problem than the table.
 *
 * <b>It is the same sheet the screen shows.</b> {@link Tasks#sheet} builds it and this renders it;
 * building the list twice is how the email and the page start disagreeing about what today is.
 */
public class Docket {
  private static final Logger LOG = LoggerFactory.getLogger(Docket.class);

  /** the hour the docket goes out, in the community's own clock */
  public static final int SEND_AT_HOUR = 6;

  /** who has already had today's, so a second pass in the same day does nothing */
  private final java.util.concurrent.ConcurrentHashMap<String, LocalDate> sent =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final Mailer mailer;

  public Docket(Mailer mailer) {
    this.mailer = mailer;
  }

  /** what one send would do, so the caller can log it and a test can read it */
  public record Sent(int people, int skipped) {
  }

  /**
   * Send today's docket to everybody who has something on it.
   *
   * Deliberately takes the whole domain rather than one person: the alternative is a caller looping
   * over members and deciding who to skip, which is the decision this class exists to hold.
   */
  public Sent run(DomainConfig config, Accounts accounts) {
    LocalDate today = accounts.tasks.today();
    int people = 0;
    int skipped = 0;
    List<UserRecord> members;
    try {
      members = accounts.users.recent(500);
    } catch (SQLException ex) {
      LOG.error("docket-members-failed", ex);
      return new Sent(0, 0);
    }
    for (UserRecord person : members) {
      if (!person.isApproved() || person.disabled()) {
        continue;
      }
      String key = accounts.databaseDomain + ":" + person.id();
      if (today.equals(sent.get(key))) {
        continue;
      }
      try {
        Tasks.Sheet sheet = accounts.tasks.sheet(person.id(), accounts.processes);
        if (sheet.today().isEmpty()) {
          // Nothing on the docket, so no email. See the class note: a daily message that arrives
          // with nothing in it teaches somebody to filter the one that matters.
          skipped++;
          sent.put(key, today);
          continue;
        }
        Mailer.Envelope envelope = Mailer.Envelope.to(config, accounts, person.email(), null);
        mailer.sendDocket(envelope, today.toString(), sheet.today().size(),
            render(sheet.today(), null), render(sheet.horizon(), "Coming up"));
        sent.put(key, today);
        people++;
      } catch (SQLException ex) {
        LOG.error("docket-failed user={}", person.id(), ex);
      }
    }
    return new Sent(people, skipped);
  }

  /** the sheet as plain lines; the layout puts them in a box */
  static String render(List<Map<String, Object>> rows, String heading) {
    if (rows == null || rows.isEmpty()) {
      return "";
    }
    ArrayList<String> lines = new ArrayList<>();
    if (heading != null) {
      lines.add(heading + ":");
    }
    for (Map<String, Object> row : rows) {
      StringBuilder line = new StringBuilder();
      line.append("- ").append(row.get("title"));
      Object area = row.get("area");
      if (area != null && !String.valueOf(area).isBlank()) {
        line.append(" (").append(area).append(')');
      }
      Object overdue = row.get("overdue_by_days");
      if (overdue instanceof Number number && number.longValue() > 0) {
        line.append(" -- ").append(number).append(" day(s) late");
      }
      Object inDays = row.get("in_days");
      if (inDays != null) {
        line.append(" -- in ").append(inDays).append(" day(s)");
      }
      Object next = row.get("next_state");
      if (next != null) {
        line.append(" -- next: ").append(next);
      }
      Object streak = row.get("streak");
      if (streak instanceof Number number && number.intValue() > 0) {
        line.append(" -- ").append(number).append(" in a row");
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  /** for a test, and for an operator who wants today's to go again */
  public void forget() {
    sent.clear();
  }
}
