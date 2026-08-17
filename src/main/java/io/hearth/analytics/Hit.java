package io.hearth.analytics;

/**
 * One request, as the access log remembers it.
 *
 * Flat and primitive for the same reason the mutation event is: this is the thing a future external
 * analytics service would receive, and a record holding object references could not leave the JVM.
 *
 * userId is here because "which of my members is doing this" is the question an operator actually
 * has, and it can only be answered if session resolution puts it here at request time.
 */
public record Hit(long seq, long atMillis, String domain, String method, String uri, int status,
                  long durationMicros, String ip, Long userId, String agent, String referer) {

  public boolean isError() {
    return status >= 400;
  }

  public boolean bySomebodyKnown() {
    return userId != null;
  }

  /** the path with any query string removed, which is what a "top pages" list wants */
  public String path() {
    int question = uri.indexOf('?');
    return question < 0 ? uri : uri.substring(0, question);
  }

  public long durationMillis() {
    return durationMicros / 1000;
  }
}
