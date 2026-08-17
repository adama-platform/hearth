package io.hearth.smtp;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Prints an arriving message to the terminal, in the shape a person reads.
 *
 * The inbound twin of {@link io.hearth.mail.DevBoxMailer}, and for the same reason: while a feature
 * is being built, the useful thing is to *see* it happen. This says who it came from, who it was
 * for, which community it routed to, and enough of the message to recognise it -- and nothing else,
 * because a terminal is not a mail store.
 *
 * It always accepts, which is right for what this is. A receiver that actually does something with
 * the mail will have opinions about what to refuse, and those belong there rather than here.
 */
public class TerminalMailReceiver implements MailReceiver {
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final String CYAN = "[36m";
  private static final String DIM = "[2m";
  private static final String BOLD = "[1m";
  private static final String RESET = "[0m";
  private static final int PREVIEW = 600;

  private final PrintStream out;
  private final boolean color;

  public TerminalMailReceiver() {
    this(System.out, System.console() != null && System.getenv("NO_COLOR") == null);
  }

  public TerminalMailReceiver(PrintStream out, boolean color) {
    this.out = out;
    this.color = color;
  }

  @Override
  public Outcome receive(Envelope envelope) {
    StringBuilder sb = new StringBuilder();
    sb.append('\n');
    sb.append(paint(CYAN, "  +-- mail arrived -----------------------------------------")).append('\n');
    sb.append(paint(DIM, "  | " + LocalTime.now().format(CLOCK) + "  " + envelope.size()
        + " bytes from " + envelope.remoteAddress())).append('\n');
    sb.append(paint(DIM, "  | for:  ")).append(envelope.domain()).append('\n');
    sb.append(paint(DIM, "  | from: ")).append(envelope.from()).append('\n');
    sb.append(paint(DIM, "  | to:   ")).append(String.join(", ", envelope.recipients())).append('\n');

    Map<String, String> headers = envelope.headers();
    // the checks stamped their findings on the front, so this reads them back rather than
    // recomputing anything -- which is the point of the header existing
    String auth = headers.get("authentication-results");
    if (auth != null) {
      sb.append(paint(DIM, "  | auth: ")).append(auth).append('\n');
    }
    String subject = envelope.subject();
    if (!subject.isBlank()) {
      sb.append(paint(DIM, "  |")).append('\n');
      sb.append(paint(DIM, "  | ")).append(paint(BOLD, subject)).append('\n');
    }
    // What the message claims about itself, shown only when it disagrees with what the connection
    // said. That gap is the whole reason the envelope is kept separately, and seeing it once is
    // worth more than reading about it.
    String claimed = headers.get("from");
    if (claimed != null && envelope.from() != null && !claimed.contains(envelope.from())) {
      sb.append(paint(DIM, "  | header From: " + claimed + "  (envelope says otherwise)"))
          .append('\n');
    }

    String preview = envelope.bodyPreview(PREVIEW);
    if (!preview.isEmpty()) {
      sb.append(paint(DIM, "  |")).append('\n');
      for (String line : preview.split("\n")) {
        sb.append(paint(DIM, "  | ")).append(line).append('\n');
      }
    }
    sb.append(paint(CYAN, "  +---------------------------------------------------------")).append('\n');
    out.print(sb);
    out.flush();
    return Outcome.accepted("logged to the terminal");
  }

  private String paint(String code, String text) {
    return color ? code + text + RESET : text;
  }
}
