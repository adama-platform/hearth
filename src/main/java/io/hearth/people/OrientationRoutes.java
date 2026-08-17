package io.hearth.people;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Forms;
import io.hearth.web.Landing;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The first three minutes.
 *
 * Somebody who has just proved their address is at the least useful point they will ever be at:
 * they are a row in a table with an email on it, an administrator has nothing to decide from, and
 * they have no reason to come back. Orientation is the shortest walk from there to a person.
 *
 * <pre>
 *   /welcome          what to call you
 *   /welcome?step=2   the community's questions
 *   /welcome?step=3   what happens next
 * </pre>
 *
 * <b>The name is the whole of step one, and it is required.</b> Everything this server sends on
 * somebody's behalf says who is asking, and "ana@example.com invited you" is an address rather
 * than a person -- so a name is not a nicety on a profile, it is the thing an invitation, a comment
 * and a member list all depend on. Asking for it first, on its own, with nothing else on the
 * screen, is the difference between a field people fill in and a field people skip.
 *
 * <b>Step two is the survey, not a sample of it.</b> The same questions, the same merge, the same
 * page underneath -- so a community that adds a question next month has changed what newcomers are
 * asked without anybody editing a welcome flow. There is no orientation copy to keep in step with
 * the survey, because there is no second survey.
 *
 * Skippable at every step and returnable to. A wizard nobody can leave is a wizard people abandon
 * at the browser rather than at the button, and then never come back to.
 */
public class OrientationRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(OrientationRoutes.class);

  private final Templates templates;
  private final Verbose verbose;

  public OrientationRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  /**
   * Does this person still need it?
   *
   * One question -- have they said what to call them -- because that is the one thing downstream
   * of this that other people see. Somebody who has a name and has never answered a question is
   * not stuck; they are somebody who will be asked again the next time the community asks.
   */
  public static boolean needsIt(Accounts accounts, UserRecord me) {
    if (me == null) {
      return false;
    }
    try {
      return accounts.people.profileOf(me.id()).displayName().isBlank();
    } catch (SQLException ex) {
      // never make a database problem into a redirect loop
      return false;
    }
  }

  /**
   * Somebody who has been here before, and whom the community has since asked something new.
   *
   * <b>The wizard coming back is the point.</b> A survey that is only asked on the first day
   * measures what a community wanted to know on the day somebody joined, and then goes stale --
   * whereas the questions worth asking are the ones that arrive when something changes. So a
   * returning member with unanswered questions gets the same three-at-a-time screen they got on
   * their first day, framed as what it is: the people running this place are asking for something.
   *
   * Only on a fresh sign-in, and only when the survey has something to say. Somebody who skips is
   * not asked again until the next time they sign in, which is the difference between a nudge and a
   * nag.
   */
  public static boolean hasMoreToAsk(Accounts accounts, UserRecord me) {
    if (me == null) {
      return false;
    }
    try {
      if (!accounts.people.profileOf(me.id()).oriented()) {
        return false;
      }
      return accounts.survey.remainingFor(me.id()) > 0;
    } catch (SQLException ex) {
      return false;
    }
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    if (session == null) {
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(),
              Landing.carry(config.urls.login, Landing.here(req))});
      return;
    }
    try {
      UserRecord me = accounts.users.byId(session.userId());
      if (me == null) {
        // a live session whose account has gone: the same answer as signed out, including the way
        // back, because from where somebody is standing that is exactly what happened
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                Landing.carry(config.urls.login, Landing.here(req))});
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, recorder);
        return;
      }
      // where they left off, unless they asked for a particular step. Somebody who closed the tab
      // half way through comes back to where they were rather than to a name box they already
      // filled in, and a step in the address bar is still honoured -- it is their own account and
      // there is nothing behind these screens to protect.
      ProfileRecord profile = accounts.people.profileOf(me.id());
      Integer asked = stepOf(req);
      int step = asked != null ? asked : Math.min(3, profile.orientationStep() + 1);
      show(config, accounts, ctx, req, me, step, null, recorder,
          asked == null && profile.orientationStep() > 0);
    } catch (SQLException ex) {
      LOG.error("orientation-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, int step, String problem,
                    WebHandler.Recorder recorder) throws SQLException {
    show(config, accounts, ctx, req, me, step, problem, recorder, false);
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, int step, String problem,
                    WebHandler.Recorder recorder, boolean resumed) throws SQLException {
    show(config, accounts, ctx, req, me, step, problem, recorder, resumed,
        accounts.people.profileOf(me.id()).oriented());
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, int step, String problem,
                    WebHandler.Recorder recorder, boolean resumed, boolean returning)
      throws SQLException {
    ProfileRecord profile = accounts.people.profileOf(me.id());
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, "Welcome");
    model.put("csrf", csrf);
    model.put("action", config.urls.orientation);
    model.put("problem", problem);
    model.put("step", step);
    model.put("resumed", resumed && step > 1);
    // somebody coming back to a question they have not seen before is a different screen from
    // somebody arriving for the first time, and it should say so rather than pretending
    model.put("returning", returning);
    model.put("onName", step <= 1);
    model.put("onQuestions", step == 2);
    model.put("onDone", step >= 3);
    model.put("form_display_name", profile.displayName());
    model.put("form_location", profile.location());
    model.put("form_about", profile.about());
    model.put("approved", accounts.access.isApproved(me));
    model.put("selfUrl", config.urls.self);
    model.put("surveyUrl", config.urls.survey);
    model.put("homeUrl", config.urls.afterLogin);

    if (step == 2 && config.has(io.hearth.vhost.Surface.survey)) {
      AnswerSheet answers = accounts.people.answersOf(me.id());
      List<Question> all = accounts.people.publishedQuestions();
      List<Question> outstanding = SurveyForm.outstanding(all, answers);
      // three at a time. A wall of boxes is a thing people close, and a community whose list has
      // grown to forty questions should still be asking a newcomer three.
      List<Question> now = SurveyForm.chunk(outstanding);
      model.put("questions", SurveyForm.rows(now, answers));
      model.put("anyQuestions", !now.isEmpty());
      model.put("questionCount", now.size());
      model.put("remaining", outstanding.size());
      model.put("more", Math.max(0, outstanding.size() - now.size()));
      model.put("anyMore", outstanding.size() > now.size());
      model.put("answeredSoFar", all.size() - outstanding.size());
      model.put("total", all.size());
    }
    model.put("hasSurvey", config.has(io.hearth.vhost.Surface.survey));
    if (step >= 3) {
      // reaching the last screen is the end of it, however they got there -- including by skipping
      // the questions, which is a decision rather than a failure to finish
      accounts.people.markOrientation(me.id(), ProfileRecord.ORIENTED);
    }

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("welcome", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accounts.security, csrf)});
  }

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    if (form.bodyTooLarge()
        || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
            Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, config.urls.orientation);
      return;
    }
    String action = String.valueOf(form.get("action"));
    if (action.equals("name")) {
      String name = orEmpty(form.get("display_name")).trim();
      if (name.isEmpty()) {
        // the one required thing in the whole flow, refused in words rather than by a browser
        // bubble, because this is the step that explains why it is required
        show(config, accounts, ctx, req, me, 1,
            "A name is the one thing we do need -- it is what everybody else sees.", recorder);
        return;
      }
      ProfileRecord existing = accounts.people.profileOf(me.id());
      String about = orEmpty(form.text("about"));
      String location = orEmpty(form.get("location"));
      // after reading and before writing: the oversize list fills in as fields are read
      if (form.tooLong() != null) {
        show(config, accounts, ctx, req, me, 1, "That was longer than the box allows.", recorder);
        return;
      }
      // the headline and the links are not asked for here and must survive being left alone --
      // somebody who has already written a profile can still land back on this page
      accounts.people.saveProfile(me.id(), name, existing.headline(), about, location,
          existing.links());
      accounts.people.markOrientation(me.id(), 1);
      verbose.detail("orientation: " + me.email() + " is " + name);
      redirect(ctx, req, recorder, config.urls.orientation
          + (config.has(io.hearth.vhost.Surface.survey) ? "?step=2" : "?step=3"));
      return;
    }
    if (action.equals("answers")) {
      // no erasing on the way in: every box here is a question nobody has answered, so an empty one
      // is somebody skipping rather than withdrawing something they said
      int changed = SurveyForm.merge(accounts, me.id(), form, false);
      accounts.people.markOrientation(me.id(), 2);
      accounts.survey.forget(me.id());
      verbose.detail("orientation: " + me.email() + " answered " + changed);
      // ...and if there are more, the next three rather than the finish line. Somebody who has
      // just answered three is the most likely person in the community to answer three more, and
      // the alternative is a screen that says "done" while five questions are still waiting.
      boolean more = changed > 0 && !SurveyForm.outstanding(
          accounts.people.publishedQuestions(), accounts.people.answersOf(me.id())).isEmpty();
      redirect(ctx, req, recorder, config.urls.orientation + (more ? "?step=2" : "?step=3"));
      return;
    }
    redirect(ctx, req, recorder, config.urls.orientation);
  }

  /** which step was asked for, or null for "wherever they got to" */
  private static Integer stepOf(FullHttpRequest req) {
    String raw = Forms.query(req.uri(), "step");
    if (raw == null) {
      return null;
    }
    try {
      return Math.max(1, Math.min(3, Integer.parseInt(raw.trim())));
    } catch (NumberFormatException ex) {
      return 1;
    }
  }

  private void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title) {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", title + " · " + config.name);
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    return model;
  }
}
