package io.hearth.web;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebRequestShieldTests {
  @Test
  public void realPathsPass() {
    assertFalse(WebRequestShield.block("/"));
    assertFalse(WebRequestShield.block("/index.html"));
    assertFalse(WebRequestShield.block("/blog/2026/a-post"));
    assertFalse(WebRequestShield.block("/admin"));
    assertFalse(WebRequestShield.block("/style.css?v=2"));
  }

  @Test
  public void wellKnownStaysOpen() {
    assertFalse(WebRequestShield.block("/.well-known/acme-challenge/token"));
    assertFalse(WebRequestShield.block("/.well-known/security.txt"));
  }

  @Test
  public void hiddenPathsBlocked() {
    assertTrue(WebRequestShield.block("/.env"));
    assertTrue(WebRequestShield.block("/.git/config"));
  }

  @Test
  public void traversalBlocked() {
    assertTrue(WebRequestShield.block("/../../etc/passwd"));
    assertTrue(WebRequestShield.block("/static/../../secret"));
  }

  @Test
  public void scannerNoiseBlocked() {
    assertTrue(WebRequestShield.block("/wp-login.php"));
    assertTrue(WebRequestShield.block("/wp-admin/"));
    assertTrue(WebRequestShield.block("/xmlrpc.php"));
    assertTrue(WebRequestShield.block("/phpmyadmin/index.php"));
    assertTrue(WebRequestShield.block("/actuator/env"));
    assertTrue(WebRequestShield.block("/cgi-bin/luci"));
    assertTrue(WebRequestShield.block("/owa/auth/logon.aspx"));
    assertTrue(WebRequestShield.block("/swagger-ui.html"));
    assertTrue(WebRequestShield.block("/vendor/phpunit/phpunit/eval-stdin.php"));
  }

  @Test
  public void emptyBlocked() {
    assertTrue(WebRequestShield.block(null));
    assertTrue(WebRequestShield.block(""));
  }
}
