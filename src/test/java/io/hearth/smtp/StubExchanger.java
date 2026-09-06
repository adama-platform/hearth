package io.hearth.smtp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mail exchanger that says what a test tells it to and keeps what it was sent.
 *
 * <b>A real socket rather than a mocked client, because the thing worth testing is the
 * conversation.</b> Half of what makes a forwarder work or not is protocol detail -- the order of
 * the commands, dot-stuffing, whether the message arrives byte for byte -- and a mock of the relay
 * would agree with whatever the relay does, which proves only that the code agrees with itself.
 *
 * No TLS here: the relay is constructed with `require-tls` off in these tests, and the property
 * that TLS is required when asked for is checked separately without needing a certificate.
 */
public class StubExchanger implements AutoCloseable {
  /** one delivery, as the far end saw it */
  public record Delivered(String mailFrom, List<String> recipients, String data) {
    /** the message as it arrived, with dot-stuffing undone, which is what a receiver stores */
    public String body() {
      int at = data.indexOf("\r\n\r\n");
      return at < 0 ? "" : data.substring(at + 4);
    }

    public boolean hasHeader(String name) {
      return data.toLowerCase().startsWith(name.toLowerCase() + ":")
          || data.toLowerCase().contains("\r\n" + name.toLowerCase() + ":");
    }

    /** the first value of a header, unfolded */
    public String header(String name) {
      String text = data.replaceAll("\r\n[ \t]+", " ");
      for (String line : text.split("\r\n")) {
        if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
          return line.substring(name.length() + 1).trim();
        }
        if (line.isEmpty()) {
          break;
        }
      }
      return null;
    }
  }

  public final List<Delivered> delivered = new CopyOnWriteArrayList<>();
  private final ServerSocket socket;
  private final Thread thread;
  private volatile String answerToData = "250 OK: queued";
  private volatile String answerToRcpt = "250 OK";
  private volatile boolean running = true;

  public StubExchanger() throws Exception {
    socket = new ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
    thread = new Thread(this::serve, "stub-exchanger");
    thread.setDaemon(true);
    thread.start();
  }

  public int port() {
    return socket.getLocalPort();
  }

  /** what this exchanger says at the end of DATA; a 4xx or 5xx is the point of most of these tests */
  public StubExchanger answersData(String reply) {
    this.answerToData = reply;
    return this;
  }

  public StubExchanger answersRcpt(String reply) {
    this.answerToRcpt = reply;
    return this;
  }

  private void serve() {
    while (running) {
      try (Socket client = socket.accept()) {
        talk(client);
      } catch (Exception ex) {
        // the socket closed, or a test hung up mid-conversation; both are ordinary here
      }
    }
  }

  private void talk(Socket client) throws Exception {
    BufferedReader in = new BufferedReader(
        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
    Writer out = new java.io.OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
    say(out, "220 stub ESMTP");

    String mailFrom = "";
    ArrayList<String> recipients = new ArrayList<>();
    String line;
    while ((line = in.readLine()) != null) {
      String upper = line.toUpperCase();
      if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
        // no STARTTLS advertised: these tests are about the conversation, not the handshake
        say(out, "250-stub\r\n250 8BITMIME");
      } else if (upper.startsWith("MAIL FROM:")) {
        mailFrom = SmtpRouting.extractAddress(line.substring("MAIL FROM:".length()));
        recipients.clear();
        say(out, "250 OK");
      } else if (upper.startsWith("RCPT TO:")) {
        if (!answerToRcpt.startsWith("2")) {
          say(out, answerToRcpt);
          continue;
        }
        recipients.add(SmtpRouting.extractAddress(line.substring("RCPT TO:".length())));
        say(out, answerToRcpt);
      } else if (upper.equals("DATA")) {
        say(out, "354 go ahead");
        StringBuilder body = new StringBuilder();
        String data;
        while ((data = in.readLine()) != null) {
          if (data.equals(".")) {
            break;
          }
          // undo dot-stuffing, exactly as a receiver does; a relay that skips it truncates any
          // message with a line starting in a full stop
          body.append(data.startsWith(".") ? data.substring(1) : data).append("\r\n");
        }
        delivered.add(new Delivered(mailFrom, List.copyOf(recipients), body.toString()));
        say(out, answerToData);
      } else if (upper.equals("QUIT")) {
        say(out, "221 bye");
        return;
      } else {
        say(out, "250 OK");
      }
    }
  }

  private void say(Writer out, String line) throws Exception {
    out.write(line + "\r\n");
    out.flush();
  }

  @Override
  public void close() {
    running = false;
    try {
      socket.close();
    } catch (Exception ex) {
      // going away
    }
    thread.interrupt();
  }
}
