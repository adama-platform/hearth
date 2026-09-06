package io.hearth.smtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What has to be true elsewhere for forwarded mail to arrive, generated from what is configured here.
 *
 * <b>This exists because the hard part of running a forwarder is not the code.</b> Every piece of
 * this server can be right and the mail still land in spam, because forwarding breaks the two things
 * a receiver uses to decide whether a message is real. What fixes that is four DNS records, one
 * setting inside Google Workspace and a reverse DNS entry at the hosting provider -- none of which
 * this server can see, all of which it knows the correct value of.
 *
 * <b>Generated rather than written down</b>, which is the same reasoning the agent guidance follows.
 * A page of instructions in a document is a page that is wrong the first time somebody changes a
 * selector; a page built from the running configuration says the selector this server is actually
 * signing with, and the key it is actually holding.
 *
 * <b>What this cannot check, it says it cannot check.</b> Whether the MX record points here, whether
 * reverse DNS matches, whether the inbound gateway is set -- this server has no way to know any of
 * them from the inside, so each carries the command that answers it rather than a green tick that
 * would be a guess.
 */
public final class Workspace {
  /** what Gmail wants a forwarder to be doing, in the order somebody does them */
  private Workspace() {
  }

  /** one thing to put somewhere else: a DNS record, a setting, or a check to run */
  public record Step(String name, String kind, String value, String why, boolean copyable) {
  }

  public record Group(String title, String intro, List<Step> steps) {
  }

  /**
   * The DNS a forwarding domain needs.
   *
   * @param domain   the domain whose mail is being forwarded
   * @param hostname the name this server answers to on port 25, which is also what MX points at
   * @param keys     the signing key, or null when there is none -- the DKIM entry then says so
   *                 rather than printing a record that would publish nothing
   */
  public static Group dns(String domain, String hostname, MailKeys keys) {
    ArrayList<Step> steps = new ArrayList<>();
    steps.add(new Step(domain, "MX", "10 " + hostname,
        "Mail for the domain comes here first. This is the change that makes this server the front"
            + " door; until it is made, nothing else on this page has any effect.", true));
    // SPF is about the *envelope* sender, and after SRS that is this domain -- which is exactly why
    // it has to list this machine even though this machine sends nobody's original mail.
    steps.add(new Step(domain, "TXT", "v=spf1 a:" + hostname + " ~all",
        "Forwarded mail leaves here with a return path at this domain, so this domain has to say"
            + " that this machine may send for it. Without it every forwarded message fails SPF at"
            + " the far end. `~all` rather than `-all` while you are settling in: a soft fail is"
            + " marked, a hard fail is deleted.", true));
    if (keys != null) {
      steps.add(new Step(keys.dnsName(domain), "TXT", keys.dnsRecord(),
          "The public half of the key this server signs with. One key signs for every domain here,"
              + " so this same record goes on each of them. Paste it as one string -- a control"
              + " panel that splits it across lines is the usual reason a signature verifies"
              + " nowhere.", true));
    } else {
      steps.add(new Step(ForwardConfig.DEFAULT_SELECTOR + "._domainkey." + domain, "TXT",
          "(no signing key: forwarding is off, or the key could not be opened)",
          "With no key, mail forwards unsigned. It will still be delivered and it will be trusted"
              + " less.", false));
    }
    steps.add(new Step("_dmarc." + domain, "TXT",
        "v=DMARC1; p=none; rua=mailto:postmaster@" + domain,
        "Start at `p=none`, which asks for reports and refuses nothing. Read a fortnight of them"
            + " before moving to quarantine: turning on a policy you have not watched is how a"
            + " domain deletes its own mail.", true));
    steps.add(new Step("the machine's public address", "PTR", hostname,
        "Reverse DNS, set at whoever rents you the machine rather than in your own zone. Google"
            + " checks that the address connecting has a name, and that the name points back to the"
            + " address. A machine with no PTR is treated as a home broadband line, which is where"
            + " most spam comes from.", false));
    return new Group("DNS for " + domain,
        "Four records in your own zone and one at your hosting provider. Every one of them exists"
            + " because forwarding breaks something a receiver checks, and each is the repair.",
        steps);
  }

  /**
   * The one setting inside Google Workspace that matters, and the three that look like it and are
   * wrong.
   *
   * The inbound gateway is the whole of it. Without it Gmail sees a machine it has never heard of
   * delivering mail that fails SPF for gmail.com, which is the exact signature of a forgery; with
   * it, Gmail knows the connecting address is a gateway and reads the original sender out of the
   * headers this server added instead.
   */
  public static Group workspace(String domain, String hostname) {
    ArrayList<Step> steps = new ArrayList<>();
    steps.add(new Step("Add this machine as an inbound gateway", "setting",
        "Admin console -> Apps -> Google Workspace -> Gmail -> Spam, phishing and malware"
            + " -> Inbound gateway",
        "Add the public address of " + hostname + " to the gateway IP list. This is the setting"
            + " that makes forwarding work: it tells Gmail that mail from this address has already"
            + " been through a gateway, so it judges the original sender rather than judging this"
            + " machine for failing SPF on somebody else's behalf.", false));
    steps.add(new Step("Automatically detect external IP", "setting", "on",
        "Inside the same panel. It lets Gmail find the sending address further back in the Received"
            + " chain, which is what this server writes on the way through.", false));
    steps.add(new Step("Require TLS for connections from these gateways", "setting", "on",
        "This server delivers over TLS and verifies the certificate, so turning this on costs"
            + " nothing and closes the case where somebody else's machine claims to be your"
            + " gateway.", false));
    steps.add(new Step("Reject all mail not from gateways", "setting", "leave off, at first",
        "Correct once every path into the domain goes through this machine, and a way to lose mail"
            + " before that -- calendar invitations and anything Google generates internally do not"
            + " come through your gateway.", false));
    steps.add(new Step("Email allowlist", "setting", "leave empty",
        "It looks like the right answer and is not. An allowlisted address skips spam filtering"
            + " entirely, so the day something gets through this server, it lands in an inbox with"
            + " nothing else looking at it. The inbound gateway is the setting that does this"
            + " properly.", false));
    steps.add(new Step("Postmaster Tools", "elsewhere", "postmaster.google.com",
        "Add " + domain + " and watch the reputation and spam-rate graphs. It is the only view you"
            + " get of what Gmail thinks of this machine, and the first place a problem shows up.",
        false));
    steps.add(new Step("The destination has to be a real mailbox", "setting", "",
        "Mail is delivered to the destination domain's own mail exchangers. If the address you are"
            + " forwarding to is at a Workspace domain, that domain has to still be registered in"
            + " the Workspace account -- its MX pointing here does not stop Google accepting mail"
            + " for it.", false));
    return new Group("Google Workspace",
        "One setting matters and two of the obvious ones are traps.", steps);
  }

  /**
   * The commands that answer what this server cannot.
   *
   * Printed rather than run. Every one of them asks the internet a question about this machine from
   * the outside, which is the only vantage point from which the answer means anything -- a server
   * checking its own reverse DNS from inside its own network has been fooled by a split-horizon
   * resolver more than once.
   */
  public static Group checks(String domain, String hostname, MailKeys keys) {
    ArrayList<Step> steps = new ArrayList<>();
    steps.add(new Step("Does the world send mail here?", "command",
        "dig +short MX " + domain, "It should answer with " + hostname + ".", true));
    steps.add(new Step("Does this machine have a name?", "command",
        "dig +short -x $(dig +short " + hostname + ")",
        "It should answer with " + hostname + ". Anything else, including nothing, is the reverse"
            + " DNS entry your hosting provider has not set.", true));
    steps.add(new Step("Is the key published?", "command",
        "dig +short TXT " + (keys == null ? ForwardConfig.DEFAULT_SELECTOR : keys.selector())
            + "._domainkey." + domain,
        "It should answer with one long string starting `v=DKIM1`. Several short quoted strings"
            + " joined by spaces is fine; several *records* is not.", true));
    steps.add(new Step("What does SPF say?", "command", "dig +short TXT " + domain,
        "One record beginning `v=spf1`, naming this machine. Two SPF records is the same as none --"
            + " the specification says a domain with more than one is in error.", true));
    steps.add(new Step("Does a real message survive?", "command",
        "send one from an outside account, then open " + "/admin/mail/log",
        "The log says what the three checks made of it here and what the far end said back. That is"
            + " the whole answer, and it is worth doing before you move a real address.", false));
    return new Group("Checking it from outside",
        "This server cannot see any of these from where it is standing, so it prints the questions"
            + " rather than pretending to know.", steps);
  }

  /**
   * What this server *can* see about its own configuration, said plainly.
   *
   * Deliberately about this box and nothing beyond it. Every item here is a fact the process
   * already holds, so none of it can be stale and none of it is a guess about somebody else's
   * network.
   */
  public static List<Map<String, Object>> readiness(SmtpConfig smtp, MailKeys keys, int rules,
                                                    int addresses) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    rows.add(state("Inbound mail", smtp.enabled,
        smtp.enabled ? "listening on port " + smtp.port : "off; nothing arrives here at all"));
    rows.add(state("Forwarding", smtp.forwarding.enabled, smtp.forwarding.describe()));
    rows.add(state("Signing key", keys != null,
        keys == null ? "none, so mail forwards unsigned"
            : "selector " + keys.selector() + ", published per domain"));
    // Enforcing DMARC matters far more for a forwarder than for a mailbox.
    //
    // A forwarder that passes on mail failing the sender's own p=reject is delivering, in its own
    // name, a message the domain owner asked the world to refuse -- and it is this machine's
    // address that Gmail records as having sent it.
    rows.add(state("Refusing what fails DMARC p=reject", smtp.enforceDmarc,
        smtp.enforceDmarc ? "on, which is what a forwarder wants"
            : "off; forwarding spam in your own name is how a machine loses its reputation"));
    rows.add(state("TLS on delivery", smtp.forwarding.requireTls,
        smtp.forwarding.requireTls ? "required" : "optional; a receiver without it gets plain text"));
    rows.add(state("Rules", rules > 0,
        rules == 0 ? "none, so nothing is forwarded yet" : rules + " rule(s)"));
    rows.add(state("Addresses", addresses > 0,
        addresses == 0 ? "none named" : addresses + " address(es)"));
    return rows;
  }

  private static Map<String, Object> state(String what, boolean good, String detail) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("what", what);
    row.put("good", good);
    row.put("detail", detail);
    return row;
  }

  /** every group, in the order somebody works through them */
  public static List<Group> all(String domain, String hostname, MailKeys keys) {
    return List.of(dns(domain, hostname, keys), workspace(domain, hostname),
        checks(domain, hostname, keys));
  }
}
