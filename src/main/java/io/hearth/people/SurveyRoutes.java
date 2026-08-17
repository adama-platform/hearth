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
 * The survey, as a place rather than a tab.
 *
 * <b>This is the profile, from the community's side.</b> A profile is what somebody chose to say
 * about themselves once; the survey is what the community keeps asking, and the answers are the
 * only structured thing about a member that an administrator or a model can reason over -- who
 * drives, who can host, what people want more of, who is finding it hard at the moment. A community
 * changes, so the questions change, and the useful thing is not a form filled in on the first day
 * but a small standing conversation that is easy to add one answer to.
 *
 * That is why it moved out of a tab on somebody's own page. Buried under a profile it was a chore
 * with a badge on it; as a page of its own it can say plainly what is outstanding, and it can be
 * linked to from anywhere -- "we added a question about lifts, three minutes".
 *
 * <pre>
 *   /survey           what is left to answer
 *   /survey?all=1     everything, including what you have already said, to change
 * </pre>
 *
 * The two views are one page and one form. Splitting them into two URLs with two handlers would
 * have meant two places to get the merge rule wrong, and the merge rule -- absent means leave
 * alone, empty means erase -- is the whole correctness of the thing.
 */
public class SurveyRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(SurveyRoutes.class);

  private final Templates templates;
  private final Verbose verbose;

  public SurveyRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
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
        save(config, accounts, ctx, req, me, recorder);
        return;
      }
      show(config, accounts, ctx, req, me, recorder, null);
    } catch (SQLException ex) {
      LOG.error("survey-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder, String done)
      throws SQLException {
    boolean all = Forms.query(req.uri(), "all") != null;
    List<Question> questions = accounts.people.publishedQuestions();
    AnswerSheet answers = accounts.people.answersOf(me.id());
    List<Question> outstanding = SurveyForm.outstanding(questions, answers);

    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, "Questions");
    model.put("csrf", csrf);
    model.put("action", config.urls.survey);
    model.put("done", saidSo(done == null ? Forms.query(req.uri(), "done") : done));
    model.put("all", all);
    model.put("allUrl", config.urls.survey + "?all=1");
    model.put("todoUrl", config.urls.survey);
    model.put("selfUrl", config.urls.self);
    model.put("remaining", outstanding.size());
    model.put("anyRemaining", !outstanding.isEmpty());
    model.put("answered", questions.size() - outstanding.size());
    model.put("total", questions.size());
    model.put("anyQuestions", !questions.isEmpty());
    model.put("upToDate", outstanding.isEmpty());
    // the two views are one form: what is left, or everything. A submission carries whichever
    // boxes were on screen, and the merge leaves the rest alone.
    List<Map<String, Object>> rows = SurveyForm.rows(all ? questions : outstanding, answers);
    model.put("questions", rows);
    model.put("anyShown", !rows.isEmpty());

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("survey", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accounts.security, csrf)});
  }

  /**
   * Saving is a merge, never a replace.
   *
   * The page usually shows a handful of the questions that exist, so treating a submission as the
   * new state of the sheet would erase last month's answers every time somebody answered a new
   * question -- and the page would look like it had worked. A key that is absent is left alone; a
   * key mapped to null is an erasure, which somebody can legitimately want.
   */
  private void save(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.survey;
    if (form.bodyTooLarge()
        || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
            Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    // clearing an answer is allowed here and nowhere else: this is the page somebody opens to
    // change their mind, and putting a question back on the list is a thing they can mean
    int changed = SurveyForm.merge(accounts, me.id(), form, true);
    verbose.detail("survey: " + me.email() + " answered " + changed + " question(s)");
    boolean all = form.get("all") != null;
    redirect(ctx, req, recorder, where + (all ? "?all=1&" : "?") + "done=" + changed);
  }

  /**
   * The count in the redirect, said out loud.
   *
   * The number rides in the URL because a redirect is the only thing that survives the POST, and it
   * becomes a sentence here rather than there -- "0 answers saved" is a page claiming to have done
   * something, and somebody who pressed save on a form they had not touched deserves to be told
   * that nothing happened rather than thanked for it.
   */
  private static String saidSo(String count) {
    if (count == null || count.isBlank()) {
      return null;
    }
    int saved;
    try {
      saved = Integer.parseInt(count.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
    if (saved <= 0) {
      return "Nothing to save.";
    }
    return saved == 1 ? "One answer saved. Thank you." : saved + " answers saved. Thank you.";
  }

  private void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
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
