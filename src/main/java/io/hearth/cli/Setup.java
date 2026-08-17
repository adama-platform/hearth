package io.hearth.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ServerConfig;
import io.hearth.mail.AmazonSes;
import io.hearth.mail.Mailer;
import io.hearth.mail.SesConfig;
import io.hearth.common.ConfigObject;
import io.hearth.common.Verbose;
import io.hearth.vhost.Hosts;
import io.hearth.places.WebGeocoder;
import io.hearth.smtp.SmtpConfig;
import io.hearth.web.WebConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

/**
 * The walkthroughs: `--setup`, `--domain-setup`, `--setup-email`, `--test-email`.
 *
 * Each one writes a file somebody could have written by hand, and says what it wrote. That is the
 * standard they are held to -- a walkthrough that produces something you cannot then read and edit
 * has replaced understanding with a wizard, and the first time it is wrong you have nowhere to go.
 *
 * They exist because the three things that are hard to get right on a first install are hard in the
 * same way: the failure is silent and arrives later. A missing admin address means nobody can ever
 * approve anybody. An unverified SES sender means codes vanish with no bounce. A `wildcard` you did
 * not mean means a domain answers for hosts you have never heard of. Asking out loud costs a minute
 * and catches all three.
 */
public class Setup {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Root root;
  private final Ask ask;

  public Setup(Root root, Ask ask) {
    this.root = root;
    this.ask = ask;
  }

  // ---- --setup --------------------------------------------------------------------------------

  /** the server itself: ports, TLS, and what else to do next */
  public boolean server() throws IOException {
    ask.section("setup");
    ask.say("  This writes " + root.configFile().getAbsolutePath());
    ask.blank();
    ask.say("  Everything in it has a working default, so the file is optional and short. What is");
    ask.say("  here is what actually differs between one installation and another.");
    ask.blank();

    if (root.hasConfig()) {
      ask.warn("there is already a config.cfg");
      ask.say("  Its current contents:");
      ask.blank();
      for (String line : Files.readString(root.configFile().toPath()).split("\n")) {
        ask.say("      " + line);
      }
      ask.blank();
      if (!ask.yes("Edit it?", true)) {
        ask.say("  Nothing was changed.");
        return false;
      }
    }

    // Re-running setup over an existing file offers what is in that file, not the built-in
    // defaults. Otherwise the second run is a trap: every answer somebody accepts by pressing
    // return quietly reverts a decision they made the first time, and the file that comes out looks
    // deliberate.
    ObjectNode config = JSON.createObjectNode();
    if (root.hasConfig()) {
      try {
        JsonNode existing = JSON.readTree(root.configFile());
        if (existing.isObject()) {
          config = (ObjectNode) existing;
        }
      } catch (Exception ex) {
        ask.warn("the existing config.cfg did not parse, so this starts from the defaults");
      }
    }
    ask.blank();
    ask.say("  Port 80 is the default and is what a browser tries first. It also has to stay a");
    ask.say("  real web server even with TLS on, because it answers the certificate challenge.");
    int httpPort = ask.number("HTTP port",
        config.path("http-port").asInt(WebConfig.DEFAULT_HTTP_PORT), 1, 65535);
    config.put("http-port", httpPort);

    ask.blank();
    boolean https = ask.yes("Terminate TLS on this server?",
        config.path("enable-https").asBoolean(false));
    config.put("enable-https", https);
    if (https) {
      int httpsPort = ask.number("HTTPS port",
          config.path("https-port").asInt(WebConfig.DEFAULT_HTTPS_PORT), 1, 65535);
      if (httpsPort == httpPort) {
        ask.fail("that is the same as the HTTP port");
        return false;
      }
      config.put("https-port", httpsPort);
      ask.blank();
      ask.say("  HTTP/2 is negotiated during the TLS handshake, and anything that cannot do it");
      ask.say("  gets HTTP/1.1 without noticing. There is no reason to say no.");
      config.put("http2", ask.yes("Offer HTTP/2?", config.path("http2").asBoolean(true)));
    }

    ask.blank();
    ask.say("  Some load balancers terminate TLS themselves and want a plain HTTP port to send");
    ask.say("  people to so they get redirected rather than refused. It cannot be port 80, which");
    ask.say("  is busy being a web server, so it is its own listener and it is off by default.");
    if (ask.yes("Run a redirect-only listener?",
        config.path("enable-http-bounce").asBoolean(false))) {
      config.put("enable-http-bounce", true);
      config.put("http-bounce-port",
          ask.number("Bounce port",
              config.path("http-bounce-port").asInt(WebConfig.DEFAULT_BOUNCE_PORT), 1, 65535));
    }

    ask.blank();
    ask.say("  The clock. This is a program for people who meet in a room, so \"Tuesday evening\"");
    ask.say("  is a fact about a place -- and a box rented in another continent would otherwise");
    ask.say("  put every evening a few hours out. Each community can override it.");
    config.put("timezone", zone(config.path("timezone")
        .asText(java.time.ZoneId.systemDefault().getId())));

    ask.blank();
    ask.say("  Binding 0.0.0.0 listens on every address. Use 127.0.0.1 to stay on this machine.");
    config.put("bind", ask.text("Bind address",
        config.path("bind").asText(WebConfig.DEFAULT_BIND)));

    ask.blank();
    ask.say("  Inbound mail. Off unless you ask: port 25 needs root, and an unconfigured listener");
    ask.say("  on it is found by scanners within the hour. This server never relays -- it accepts");
    ask.say("  only for domains with a config file, and each of those decides for itself with");
    ask.say("  accepts-mail.");
    boolean smtp = ask.yes("Receive email on this server?",
        config.path("smtp").path("enabled").asBoolean(false));
    if (smtp) {
      ObjectNode mail = objectAt(config, "smtp");
      mail.put("enabled", true);
      mail.put("port",
          ask.number("SMTP port", mail.path("port").asInt(SmtpConfig.DEFAULT_PORT), 1, 65535));
      ask.blank();
      ask.say("  Every message is checked with SPF, DKIM and DMARC. Refusing on a published");
      ask.say("  p=reject is doing what the sender's domain asked. Answering no checks everything");
      ask.say("  and refuses nothing, which is worth a week of reading Authentication-Results on");
      ask.say("  mail you know is genuine before you trust it to turn senders away.");
      mail.put("enforce-dmarc", ask.yes("Refuse mail that fails a published DMARC p=reject?",
          mail.path("enforce-dmarc").asBoolean(true)));
      ask.blank();
      ask.say("  For anything to arrive, an MX record has to point at this machine and port "
          + mail.path("port").asInt(SmtpConfig.DEFAULT_PORT)
          + " has to be reachable -- many hosts block 25 by default.");
    } else if (config.path("smtp").isObject()) {
      objectAt(config, "smtp").put("enabled", false);
    }

    write(root.configFile(), config.toPrettyString() + "\n", false);
    ask.ok("wrote " + root.configFile().getName());
    ask.blank();
    ask.say("  What is left:");
    ask.say("      --domain-setup <domain>    add a community; you need at least one");
    if (https) {
      ask.say("      --setup-certs              get certificates, so TLS has something to present");
    }
    ask.say("      --setup-email <domain>     send real email instead of printing to the terminal");
    return true;
  }

  /**
   * Ask for a zone until the answer is one.
   *
   * Checked here rather than at boot, because the failure this prevents is a server that will not
   * start after somebody has walked away from the terminal -- and because "America/New York" with a
   * space in it is the single most likely thing anybody types.
   */
  /** what config.cfg says, or what the machine says when it says nothing */
  private String serverZone() {
    try {
      return io.hearth.common.ServerConfig.read(root.configFile()).zone.getId();
    } catch (io.hearth.common.ConfigException ex) {
      return java.time.ZoneId.systemDefault().getId();
    }
  }

  private String zone(String fallback) throws IOException {
    while (true) {
      String answer = ask.text("Time zone (IANA id, e.g. Europe/London)", fallback);
      try {
        return java.time.ZoneId.of(answer.trim()).getId();
      } catch (java.time.DateTimeException ex) {
        ask.fail("'" + answer + "' is not a zone id. They look like Europe/London,"
            + " America/New_York or Australia/Sydney -- region/City, with an underscore for a"
            + " space. UTC works too.");
      }
    }
  }

  // ---- --domain-setup -------------------------------------------------------------------------

  /** one community: its name, who runs it, and how much of the DNS tree it answers for */
  public boolean domain(String domain) throws IOException {
    ask.section("domain setup");
    if (!Hosts.isValidDomain(domain)) {
      ask.fail("'" + domain + "' is not a valid domain name");
      ask.say("  It has to be lowercase, like example.org or junior.example.org.");
      return false;
    }
    File file = root.domainFile(domain);
    ask.say("  This writes " + file.getAbsolutePath());
    ask.blank();

    ObjectNode existing = null;
    if (file.isFile()) {
      ask.warn("there is already a config for " + domain);
      try {
        existing = (ObjectNode) JSON.readTree(file);
      } catch (Exception ex) {
        ask.warn("and it did not parse, so this will replace it entirely");
      }
      if (!ask.yes("Edit it?", true)) {
        ask.say("  Nothing was changed.");
        return false;
      }
    }
    ObjectNode config = existing == null ? JSON.createObjectNode() : existing;

    ask.blank();
    config.put("name", ask.text("What is this community called?",
        config.path("name").asText(domain)));

    ask.blank();
    // the default is whatever config.cfg says, which is the whole point of asking here: most
    // communities are in the same place as the box, and the one that is not should say so once
    ask.say("  The clock this community keeps. It decides what \"today\" means, what an all-day");
    ask.say("  entry in somebody's calendar covers, and every hour on the availability grid.");
    String boxZone = serverZone();
    String zone = zone(config.path("timezone").asText(boxZone));
    if (zone.equals(boxZone)) {
      // not written: a domain with no timezone key inherits, which is what somebody who accepted
      // the default meant, and it keeps the file to the things that differ
      config.remove("timezone");
      ask.say("  Same as the server, so nothing is written -- it inherits.");
    } else {
      config.put("timezone", zone);
    }

    ask.blank();
    ask.say("  An admin approves everybody else, so at least one address has to be an admin from");
    ask.say("  the start -- otherwise the first person to register waits for somebody who cannot");
    ask.say("  exist. Addresses listed here are admins by fiat and cannot be demoted from inside.");
    String current = "";
    if (config.has("admin_emails") && config.get("admin_emails").isArray()) {
      StringBuilder joined = new StringBuilder();
      for (JsonNode email : config.get("admin_emails")) {
        joined.append(joined.length() > 0 ? "," : "").append(email.asText());
      }
      current = joined.toString();
    }
    String admins = ask.text("Admin email addresses, comma separated", current);
    var list = config.putArray("admin_emails");
    for (String email : admins.split(",")) {
      String clean = email.trim().toLowerCase(Locale.ROOT);
      if (clean.isEmpty()) {
        continue;
      }
      if (clean.indexOf('@') <= 0) {
        ask.fail("'" + clean + "' is not an email address");
        return false;
      }
      list.add(clean);
    }
    if (list.isEmpty()) {
      ask.warn("no admins: nobody will be able to approve anybody until you add one");
    }

    ask.blank();
    ask.say("  Subdomains. Two ways, and they are not the same decision:");
    ask.blank();
    ask.say("    a list  -- " + domain + " also answers for the names you write down, and nothing");
    ask.say("               else. A certificate can cover them, because an authority will issue");
    ask.say("               for a name it can check.");
    ask.say("    wildcard -- it answers for anything underneath. Convenient, and impossible to get");
    ask.say("               a certificate for over HTTP, so TLS will only ever work on the exact");
    ask.say("               name. Anything unclaimed is served rather than refused.");
    ask.blank();
    String currentSubs = joined(config, "subdomains");
    String subs = ask.text("Subdomains to answer for, comma separated (e.g. www)", currentSubs);
    var labels = config.putArray("subdomains");
    for (String raw : subs.split(",")) {
      String label = raw.trim().toLowerCase(Locale.ROOT);
      if (label.isEmpty()) {
        continue;
      }
      // a whole hostname reads fine and is a typo waiting to happen -- "www.other.example" would
      // silently claim a name under a domain this config has nothing to do with
      if (label.endsWith("." + domain)) {
        label = label.substring(0, label.length() - domain.length() - 1);
      }
      if (label.contains(".") && !Hosts.isValidDomain(label + "." + domain)) {
        ask.fail("'" + raw.trim() + "' is not a label under " + domain);
        return false;
      }
      labels.add(label);
    }
    if (labels.size() > 0) {
      ask.say("  Certificates will be requested for each of those as well as for " + domain + ".");
    }

    ask.blank();
    boolean wildcard = ask.yes("Also answer for any *other* subdomain?",
        config.has("wildcard") && config.get("wildcard").asBoolean(false));
    config.put("wildcard", wildcard);
    if (wildcard) {
      ask.warn("no certificate can cover a wildcard here, so https will work only on the names"
          + " above");
    }

    ask.blank();
    ask.say("  Inbound mail. The listener itself is a server-wide setting in config.cfg; this is");
    ask.say("  whether mail addressed to " + domain + " is accepted once it is listening.");
    ask.say("  Nothing relays: a domain with no config file here is refused whatever this says.");
    config.put("accepts-mail", ask.yes("Accept mail for " + domain + "?",
        !config.has("accepts-mail") || config.get("accepts-mail").asBoolean(true)));

    ask.blank();
    ask.say("  Passwordless is the default and the recommendation: a password nobody has is one");
    ask.say("  nobody can leak or need reset. The other modes are 'password' and, for real two");
    ask.say("  factor, 'password_and_code'.");
    String mode = ask.text("Sign-in mode", config.path("login_security").path("mode").asText("passwordless"));
    if (!mode.equals("passwordless")) {
      config.putObject("login_security").put("mode", mode);
    }

    write(file, config.toPrettyString() + "\n", false);
    ask.ok("wrote " + file.getName());
    ask.blank();
    ask.say("  Start the server and open http://" + domain + "/register to make the first account.");
    ask.say("  Run --setup-email " + domain + " when you want real email rather than the terminal.");
    return true;
  }

  /** an existing array, as the comma-separated line somebody typed to create it */
  /** the object at this key, made if it is missing or is something else entirely */
  private static ObjectNode objectAt(ObjectNode config, String key) {
    JsonNode found = config.get(key);
    return found != null && found.isObject() ? (ObjectNode) found : config.putObject(key);
  }

  private static String joined(ObjectNode config, String key) {
    if (!config.has(key) || !config.get(key).isArray()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode value : config.get(key)) {
      out.append(out.length() > 0 ? "," : "").append(value.asText());
    }
    return out.toString();
  }

  // ---- --setup-email --------------------------------------------------------------------------

  /** the SES block for one domain */
  public boolean email(String domain) throws IOException {
    ask.section("email setup");
    File file = root.domainFile(domain);
    if (!file.isFile()) {
      ask.fail("there is no config for " + domain);
      ask.say("  Run --domain-setup " + domain + " first.");
      return false;
    }
    ObjectNode config;
    try {
      config = (ObjectNode) JSON.readTree(file);
    } catch (Exception ex) {
      ask.fail(file + " did not parse: " + ex.getMessage());
      return false;
    }

    ask.say("  Without this, codes and links print to the terminal the server runs in -- fine for");
    ask.say("  a development box, useless for anybody else.");
    ask.blank();
    ask.say("  Two things have to be true at Amazon before this works:");
    ask.blank();
    ask.say("    1. The sending address, or its whole domain, is verified in SES.");
    ask.say("    2. Your account is out of the SES sandbox, or every recipient is verified too.");
    ask.blank();
    ask.say("  The second one catches people out: in the sandbox SES accepts the send and quietly");
    ask.say("  delivers nothing to an unverified address.");
    ask.blank();
    if (!ask.yes("Is the sending address verified?", false)) {
      ask.say("  Verify it in the SES console first; nothing was changed.");
      return false;
    }

    ObjectNode ses = config.has("ses") && config.get("ses").isObject()
        ? (ObjectNode) config.get("ses") : config.putObject("ses");
    ask.blank();
    String from = ask.text("Send from", ses.path("from").asText("no-reply@" + domain));
    if (from.indexOf('@') <= 0) {
      ask.fail("that is not an email address");
      return false;
    }
    ses.put("from", from);
    ses.put("from-name", ask.text("Show the sender as",
        ses.path("from-name").asText(config.path("name").asText(domain))));
    String replyTo = ask.text("Replies go to", ses.path("reply-to").asText(from));
    ses.put("reply-to", replyTo);
    ses.put("region", ask.text("AWS region", ses.path("region").asText("us-east-1")));

    ask.blank();
    ask.say("  The credentials go into this config file in the clear -- there is nowhere else for");
    ask.say("  them in a single jar with no secret store. Use an IAM user that can do exactly one");
    ask.say("  thing, ses:SendEmail, so a leaked key is a nuisance rather than a disaster.");
    ask.blank();
    ses.put("access-key-id", ask.required("Access key id"));
    ses.put("secret-access-key", ask.secret("Secret access key"));
    ses.put("enabled", true);

    // The calendar's own address, asked here because this is where somebody is already thinking
    // about which addresses exist and which ones are verified.
    //
    // It is a different address from the one above and for a different reason: everything else this
    // server sends is one-way and can come from no-reply@, while a calendar invitation is a
    // conversation -- what somebody's client sends back when they press Accept has to arrive
    // *here*. So this one needs an MX record pointing at this machine, which the sending address
    // does not.
    ObjectNode calendar = config.has("calendar") && config.get("calendar").isObject()
        ? (ObjectNode) config.get("calendar") : config.putObject("calendar");
    String at = from.substring(from.indexOf('@') + 1);
    ask.blank();
    ask.say("  Calendar invitations are a conversation rather than an announcement: when somebody");
    ask.say("  presses Accept, their mail client sends a message back. That address has to be one");
    ask.say("  this server receives at -- an MX record pointing here -- which is not the same thing");
    ask.say("  as the sending address above.");
    ask.blank();
    String events = ask.text("Calendar address",
        calendar.path("events-address").asText("events@" + at));
    if (events.indexOf('@') <= 0) {
      ask.fail("that is not an email address");
      return false;
    }
    calendar.put("events-address", events);
    calendar.put("events-name", ask.text("Show the calendar as",
        calendar.path("events-name").asText(config.path("name").asText(domain) + " Calendar")));

    // 0600 from here on: this file now holds credentials
    write(file, config.toPrettyString() + "\n", true);
    ask.ok("wrote " + file.getName() + ", readable only by this user");
    ask.blank();
    ask.say("  Check it with:  --root " + root.dir() + " --test-email " + domain + " you@example.com");
    ask.blank();
    ask.say("  For the calendar address to work, two more things have to be true:");
    ask.say("    1. an MX record for " + events.substring(events.indexOf('@') + 1)
        + " points at this machine;");
    ask.say("    2. the smtp block in config.cfg is on, so this server is listening.");
    ask.say("  Until both are, invitations refuse to go out rather than sending people an address");
    ask.say("  that swallows their answer.");
    return true;
  }

  // ---- --setup-gps ----------------------------------------------------------------------------

  /**
   * Choose a geocoding service, and understand what you are choosing.
   *
   * <b>The key is the easy part.</b> What this walkthrough exists for is that most geocoding
   * services do not let you keep the answer -- and keeping the answer is exactly what a coordinate
   * written onto a place row is. Google requires results to be used with a Google map; Mapbox
   * charges differently for storing them; HERE has the largest free tier of any of them and caps
   * caching at thirty days. An operator who picks one of those from a comparison table is in breach
   * of the terms on the day the first place is saved, and nothing will tell them.
   *
   * So the three offered here are the three that permit it, and the screen says why the famous ones
   * are missing. That is the same reasoning as the certificate walkthrough: the expensive mistake is
   * made before anybody types anything, so the walkthrough is mostly words.
   */
  public boolean gps() throws IOException {
    ask.section("geocoding");
    ask.say("  Addresses are turned into coordinates: places in the address book, so a map link");
    ask.say("  works and an event arriving by email is matched to somewhere you already have; and");
    ask.say("  members who said where they are, so whoever books a hall can see how far people");
    ask.say("  would have to come.");
    ask.blank();
    ask.say("  This is already on, using OpenStreetMap's Nominatim, which needs no account and no");
    ask.say("  key. You are here to choose something else, or to say who to contact. Turning it");
    ask.say("  off entirely is one line in config.cfg: \"gps\": { \"enabled\": false }.");
    ask.blank();
    ask.say("  It does send an address somebody typed to another company. The default one is a");
    ask.say("  charitable foundation and costs nothing; the other two are businesses.");
    ask.blank();
    ask.say("  Three services, and the reason the famous ones are not among them:");
    ask.blank();
    ask.say("    Google, Mapbox and HERE all restrict keeping the answer. Google wants results used");
    ask.say("    with a Google map, Mapbox charges a different rate for storing them, and HERE --");
    ask.say("    which has the largest free tier of the lot -- caps caching at thirty days. This");
    ask.say("    server writes the coordinate onto the place and keeps it, so all three would put");
    ask.say("    you in breach on the day you saved your first address.");
    ask.blank();
    int number = 1;
    for (WebGeocoder.Service service : WebGeocoder.Service.values()) {
      ask.say("    " + number++ + ". " + service.name() + " -- " + service.terms());
      ask.say("       " + service.where());
      ask.blank();
    }

    String chosen = ask.text("Which one", "nominatim");
    WebGeocoder.Service service = WebGeocoder.Service.of(chosen);
    if (service == null) {
      ask.fail("that is not one of them; nothing was changed");
      return false;
    }

    File file = root.configFile();
    ObjectNode config;
    try {
      config = file.isFile() ? (ObjectNode) JSON.readTree(file) : JSON.createObjectNode();
    } catch (Exception ex) {
      ask.fail(file + " did not parse: " + ex.getMessage());
      return false;
    }
    ObjectNode gps = config.has("gps") && config.get("gps").isObject()
        ? (ObjectNode) config.get("gps") : config.putObject("gps");
    gps.put("service", service.name());

    if (service.needsKey()) {
      ask.blank();
      ask.say("  Get a key from " + service.where() + " and paste it here. It goes into config.cfg");
      ask.say("  in the clear, like the mail credentials -- there is nowhere else for it in a");
      ask.say("  single jar with no secret store.");
      gps.put("key", ask.required("API key"));
    } else {
      gps.remove("key");
      ask.blank();
      ask.say("  No key. What Nominatim asks for instead is that you identify yourself: their");
      ask.say("  policy requires a way to reach whoever runs this server, and a client that does");
      ask.say("  not give one can be blocked without warning -- which looks, from here, like");
      ask.say("  geocoding quietly stopping.");
      ask.say("  They also ask for at most one request a second, no bulk work, and that results are");
      ask.say("  cached rather than asked for twice. This server does all three: it looks a place");
      ask.say("  up once, when it is saved, and keeps the answer.");
    }
    ask.blank();
    gps.put("contact", ask.required("An email address they could reach you at"));
    gps.put("enabled", true);

    write(file, config.toPrettyString() + "\n", service.needsKey());
    ask.ok("wrote " + file.getName()
        + (service.needsKey() ? ", readable only by this user" : ""));
    ask.blank();
    ask.say("  Saving a place in the admin section will now look up its address. A place that was");
    ask.say("  saved before this has no coordinate until it is saved again.");
    return true;
  }

  // ---- --test-email ---------------------------------------------------------------------------

  /** actually send one, and say exactly what came back */
  public boolean testEmail(String domain, String to) throws IOException {
    ask.section("test email");
    File file = root.domainFile(domain);
    if (!file.isFile()) {
      ask.fail("there is no config for " + domain);
      return false;
    }
    SesConfig ses;
    String communityName;
    try {
      ObjectNode config = (ObjectNode) JSON.readTree(file);
      communityName = config.path("name").asText(domain);
      JsonNode block = config.get("ses");
      ses = block != null && block.isObject()
          ? new SesConfig(new ConfigObject((ObjectNode) block, "ses"))
          : SesConfig.off();
    } catch (Exception ex) {
      ask.fail(file + ": " + ex.getMessage());
      return false;
    }
    if (!ses.enabled) {
      ask.fail("email is not set up for " + domain);
      ask.say("  Run --setup-email " + domain + " first.");
      return false;
    }
    if (to == null || to.indexOf('@') <= 0) {
      ask.fail("give an address to send to: --test-email " + domain + " you@example.com");
      return false;
    }

    ask.say("  Sending as " + ses.from + " through " + ses.region + " to " + to);
    ask.blank();
    Mailer mailer = new AmazonSes(ses, Verbose.OFF);
    Mailer.Outcome outcome = mailer.sendLoginCode(
        new Mailer.Envelope(domain, communityName, to, null), "123456");
    if (outcome.delivered()) {
      ask.ok("SES accepted it: " + outcome.detail());
      ask.blank();
      ask.say("  Accepted is not the same as delivered. If nothing arrives, the usual causes are");
      ask.say("  the SES sandbox (the recipient has to be verified too) and the spam folder.");
      return true;
    }
    ask.fail(outcome.detail());
    ask.blank();
    ask.say("  Common causes, in the order they happen:");
    ask.say("    - the sending address is not verified in SES");
    ask.say("    - the key is for a different region than " + ses.region);
    ask.say("    - the IAM user cannot ses:SendEmail");
    return false;
  }

  // ---- writing --------------------------------------------------------------------------------

  /** write a config, optionally locked down because it now holds credentials */
  private void write(File file, String contents, boolean secret) throws IOException {
    File parent = file.getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      throw new IOException("could not create " + parent);
    }
    if (secret && !file.exists()) {
      Files.createFile(file.toPath());
    }
    if (secret) {
      try {
        Files.setPosixFilePermissions(file.toPath(),
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      } catch (UnsupportedOperationException | IOException ex) {
        ask.warn("could not restrict permissions on " + file.getName() + "; check them by hand");
      }
    }
    Files.writeString(file.toPath(), contents);
  }

  /** the built-in defaults, for the operator who would rather write the file themselves */
  public static String configTemplate() {
    return ServerConfig.template();
  }
}
