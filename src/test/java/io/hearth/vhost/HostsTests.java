package io.hearth.vhost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostsTests {
  @Test
  public void plain() {
    assertEquals("example.com", Hosts.normalize("example.com"));
    assertEquals("blog.example.com", Hosts.normalize("blog.example.com"));
    assertEquals("localhost", Hosts.normalize("localhost"));
  }

  @Test
  public void portIsStripped() {
    assertEquals("localhost", Hosts.normalize("localhost:8080"));
    assertEquals("example.com", Hosts.normalize("example.com:443"));
  }

  @Test
  public void caseIsFolded() {
    assertEquals("example.com", Hosts.normalize("EXAMPLE.COM"));
    assertEquals("example.com", Hosts.normalize("Example.Com"));
  }

  @Test
  public void trailingDotIsFolded() {
    assertEquals("example.com", Hosts.normalize("example.com."));
    assertEquals("example.com", Hosts.normalize("example.com.:8080"));
  }

  @Test
  public void whitespaceIsTrimmed() {
    assertEquals("example.com", Hosts.normalize("  example.com  "));
  }

  @Test
  public void rejectsNothing() {
    assertNull(Hosts.normalize(null));
    assertNull(Hosts.normalize(""));
    assertNull(Hosts.normalize("   "));
    assertNull(Hosts.normalize(":8080"));
  }

  @Test
  public void rejectsAddresses() {
    assertNull(Hosts.normalize("127.0.0.1"));
    assertNull(Hosts.normalize("127.0.0.1:8080"));
    assertNull(Hosts.normalize("[::1]"));
    assertNull(Hosts.normalize("[::1]:8080"));
    assertNull(Hosts.normalize("::1"));
  }

  @Test
  public void rejectsMalformed() {
    assertNull(Hosts.normalize("example..com"));
    assertNull(Hosts.normalize(".example.com"));
    assertNull(Hosts.normalize("-example.com"));
    assertNull(Hosts.normalize("example-.com"));
    assertNull(Hosts.normalize("exa_mple.com"));
    assertNull(Hosts.normalize("example.com/../etc"));
    assertNull(Hosts.normalize("exam ple.com"));
    assertNull(Hosts.normalize("exa\0mple.com"));
  }

  @Test
  public void rejectsOversized() {
    assertNull(Hosts.normalize("a".repeat(64) + ".com"));
    StringBuilder sb = new StringBuilder();
    while (sb.length() < Hosts.MAX_DOMAIN_LENGTH) {
      sb.append("abcdefgh.");
    }
    assertNull(Hosts.normalize(sb + "com"));
  }

  @Test
  public void labels() {
    assertTrue(Hosts.isValidLabel("a"));
    assertTrue(Hosts.isValidLabel("example"));
    assertTrue(Hosts.isValidLabel("ex-ample"));
    assertTrue(Hosts.isValidLabel("x1"));
    assertTrue(Hosts.isValidLabel("1"));
    assertFalse(Hosts.isValidLabel(""));
    assertFalse(Hosts.isValidLabel(null));
    assertFalse(Hosts.isValidLabel("Example"));
    assertFalse(Hosts.isValidLabel("_static"));
    assertFalse(Hosts.isValidLabel("-x"));
    assertFalse(Hosts.isValidLabel("x-"));
    assertFalse(Hosts.isValidLabel("a.b"));
    assertFalse(Hosts.isValidLabel("a".repeat(64)));
  }

  @Test
  public void counting() {
    assertEquals(1, Hosts.labelCount("localhost"));
    assertEquals(2, Hosts.labelCount("example.com"));
    assertEquals(3, Hosts.labelCount("blog.example.com"));
  }
}
