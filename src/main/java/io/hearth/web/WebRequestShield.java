package io.hearth.web;

/**
 * First-pass URI filter, lifted in spirit from Adama's shield.
 *
 * This is not the security boundary -- the security boundary is that we only serve what a
 * domain.cfg describes. This is noise control. The internet spends all day probing for WordPress
 * and phpMyAdmin on every address it can find, and a single-operator server should not fill its
 * logs with that. Anything here is a technology this server does not contain, so a request for it
 * is a scanner and gets a flat 410.
 */
public class WebRequestShield {
  public static boolean block(String uri) {
    if (uri == null || uri.isEmpty()) {
      return true;
    }
    // .well-known has to stay open: ACME lives there, and so will security.txt
    if (uri.startsWith("/.well-known")) {
      return false;
    }
    if (uri.startsWith("/.")) {
      return true;
    }
    // path traversal attempts never reach a handler
    if (uri.contains("..")) {
      return true;
    }
    if (uri.indexOf(0) >= 0) {
      return true;
    }
    // WordPress
    if (uri.startsWith("/wp-")) {
      return true;
    }
    if (uri.startsWith("/xmlrpc.php")) {
      return true;
    }
    // PHP / CGI / ASP / JSP probes
    if (uri.startsWith("/cgi-bin/") || uri.startsWith("/cgi/")) {
      return true;
    }
    if (uri.endsWith(".php") || uri.endsWith(".asp") || uri.endsWith(".aspx") || uri.endsWith(".jsp") || uri.endsWith(".cgi")) {
      return true;
    }
    // database admin tools
    if (uri.startsWith("/phpmyadmin") || uri.startsWith("/pma") || uri.startsWith("/adminer")) {
      return true;
    }
    if (uri.startsWith("/myadmin") || uri.startsWith("/mysql") || uri.startsWith("/dbadmin") || uri.startsWith("/sql")) {
      return true;
    }
    // JVM / Spring / DevOps introspection
    if (uri.startsWith("/actuator") || uri.startsWith("/jolokia") || uri.startsWith("/jmx")) {
      return true;
    }
    if (uri.startsWith("/heapdump") || uri.startsWith("/threaddump") || uri.startsWith("/trace")) {
      return true;
    }
    // management consoles and server status pages
    if (uri.startsWith("/server-status") || uri.startsWith("/server-info") || uri.startsWith("/status.")) {
      return true;
    }
    if (uri.startsWith("/portal/") || uri.startsWith("/Portal/")) {
      return true;
    }
    // Exchange / OWA
    if (uri.startsWith("/owa/") || uri.startsWith("/ecp/") || uri.startsWith("/autodiscover")) {
      return true;
    }
    // VPN appliances
    if (uri.startsWith("/vpn/") || uri.startsWith("/sslvpn") || uri.startsWith("/dana-na")) {
      return true;
    }
    // API doc scanners
    if (uri.startsWith("/swagger") || uri.startsWith("/v2/api-docs") || uri.startsWith("/v3/api-docs")) {
      return true;
    }
    // search / data engines
    if (uri.startsWith("/solr/") || uri.startsWith("/elasticsearch")) {
      return true;
    }
    if (uri.startsWith("/_search") || uri.startsWith("/_cat") || uri.startsWith("/_cluster") || uri.startsWith("/_nodes")) {
      return true;
    }
    // framework internals
    if (uri.startsWith("/telescope/") || uri.startsWith("/vendor/") || uri.startsWith("/node_modules/") || uri.startsWith("/rails/")) {
      return true;
    }
    if (uri.startsWith("/web.config")) {
      return true;
    }
    return false;
  }
}
