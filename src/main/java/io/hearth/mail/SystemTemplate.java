package io.hearth.mail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every message this server sends, as something a community can rewrite.
 *
 * <b>The words are the community's; the shape is not.</b> That split is the whole design here. A
 * template gives the subject, the opening line and the paragraphs -- the parts that should sound
 * like the people who run this place rather than like software. The layout around them stays in
 * code: tables rather than divs, inline styles, a bulletproof button, the plain-text half, and the
 * footer that says why this arrived and what interacting means. Letting somebody edit *that* would
 * mean a community one paste away from a message that renders as a wall of markup in Outlook, or
 * one whose plain-text half quietly lost the promise the HTML half makes.
 *
 * <b>Defaults live here rather than in the database.</b> A row exists only once somebody has
 * changed something, so a community that has never opened the screen still sends good messages and
 * upgrading the software improves them -- the same argument as the legal documents, for the same
 * reason. Seeding the table at boot would freeze every community's wording on the day it started.
 *
 * <b>The parameters are declared, and the editor lists them.</b> A template referring to something
 * that does not exist renders as nothing at all, which is a message that goes out with a hole in it
 * and nobody noticing -- so each flow says what it has, the screen prints them, and a preview shows
 * the result before it reaches anybody.
 */
public enum SystemTemplate {
  register_code("Creating an account",
      "Your {{community}} code",
      "Somebody asked to create an account at {{community}}.",
      "Use this code to finish. It is good for {{minutes}} minutes and can be used once.",
      "code", "minutes"),

  login_code("Signing in",
      "Your {{community}} sign-in code",
      "Somebody asked to sign in to {{community}}.",
      "Use this code to finish. It is good for {{minutes}} minutes and can be used once.",
      "code", "minutes"),

  password_reset("Resetting a password",
      "Choose a new password for {{community}}",
      "Somebody asked to reset the password on your {{community}} account.",
      "If it was not you, nothing has happened yet and you can ignore this.",
      "code", "link", "minutes"),

  password_changed("After a password changes",
      "Your {{community}} password changed",
      "The password on your {{community}} account was changed.",
      "If that was not you, come and find an administrator now -- somebody else may have got in.",
      ""),

  two_factor("The second step",
      "Your {{community}} code",
      "One more step to sign in to {{community}}.",
      "Use this code to finish. It is good for {{minutes}} minutes.",
      "code", "minutes");

  /** what an operator calls it on the screen */
  public final String label;
  public final String subject;
  public final String lead;
  public final String body;
  private final List<String> parameters;

  SystemTemplate(String label, String subject, String lead, String body, String... parameters) {
    this.label = label;
    this.subject = subject;
    this.lead = lead;
    this.body = body;
    this.parameters = List.of(parameters).stream().filter(name -> !name.isEmpty()).toList();
  }

  /**
   * Everything this flow can refer to, plus what every flow has.
   *
   * The shared ones are the community and where it lives, because almost every sentence worth
   * writing mentions one of them -- and because a template that could not say the community's name
   * would be a template nobody could make sound like their own.
   */
  public List<String> availableParameters() {
    java.util.ArrayList<String> all = new java.util.ArrayList<>(
        List.of("community", "domain", "site"));
    all.addAll(parameters);
    return all;
  }

  public static SystemTemplate of(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.trim().toLowerCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** the three parts, as the editor and the renderer both want them */
  public Map<String, String> defaults() {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    out.put("subject", subject);
    out.put("lead", lead);
    out.put("body", body);
    return out;
  }
}
