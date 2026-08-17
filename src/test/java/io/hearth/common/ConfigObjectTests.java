package io.hearth.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigObjectTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ConfigObject of(String json) throws Exception {
    return new ConfigObject((ObjectNode) MAPPER.readTree(json), "test.cfg");
  }

  @Test
  public void defaultsWhenAbsent() throws Exception {
    ConfigObject config = of("{}");
    assertEquals(7, config.intOf("n", 7));
    assertTrue(config.boolOf("b", true));
    assertEquals("fallback", config.strOf("s", "fallback"));
    assertArrayEquals(new String[]{"a"}, config.stringsOf("list", new String[]{"a"}));
  }

  @Test
  public void defaultsWhenExplicitlyNull() throws Exception {
    ConfigObject config = of("{\"n\":null,\"b\":null,\"s\":null,\"list\":null}");
    assertEquals(7, config.intOf("n", 7));
    assertTrue(config.boolOf("b", true));
    assertEquals("fallback", config.strOf("s", "fallback"));
    assertArrayEquals(new String[0], config.stringsOf("list", new String[0]));
  }

  @Test
  public void valuesWhenPresent() throws Exception {
    ConfigObject config = of("{\"n\":42,\"b\":false,\"s\":\"hi\",\"list\":[\"x\",\"y\"]}");
    assertEquals(42, config.intOf("n", 7));
    assertFalse(config.boolOf("b", true));
    assertEquals("hi", config.strOf("s", "fallback"));
    assertArrayEquals(new String[]{"x", "y"}, config.stringsOf("list", new String[0]));
  }

  @Test
  public void wrongTypesAreRefused() throws Exception {
    expectFailure(of("{\"n\":\"forty-two\"}"), c -> c.intOf("n", 0), "must be an integer");
    expectFailure(of("{\"b\":1}"), c -> c.boolOf("b", false), "must be true or false");
    expectFailure(of("{\"s\":5}"), c -> c.strOf("s", ""), "must be a string");
    expectFailure(of("{\"list\":\"nope\"}"), c -> c.stringsOf("list", null), "must be an array of strings");
    expectFailure(of("{\"list\":[1]}"), c -> c.stringsOf("list", null), "list[0]' must be a string");
    expectFailure(of("{\"kid\":3}"), c -> c.child("kid"), "must be an object");
  }

  @Test
  public void childReadsNested() throws Exception {
    ConfigObject config = of("{\"kid\":{\"n\":9}}");
    assertEquals(9, config.child("kid").intOf("n", 0));
  }

  @Test
  public void missingChildYieldsDefaults() throws Exception {
    assertEquals(3, of("{}").child("kid").intOf("n", 3));
  }

  @Test
  public void unknownKeysAreRefused() throws Exception {
    ConfigObject config = of("{\"known\":1,\"stray\":2}");
    config.intOf("known", 0);
    try {
      config.assertKnownKeys();
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage().contains("unknown key(s) stray"));
      assertTrue("the error should list what IS understood", ex.getMessage().contains("known"));
    }
  }

  @Test
  public void everyKeyReadIsAccepted() throws Exception {
    ConfigObject config = of("{\"a\":1,\"b\":true}");
    config.intOf("a", 0);
    config.boolOf("b", false);
    config.assertKnownKeys();
    assertEquals("[a, b]", config.knownKeys().toString());
  }

  @Test
  public void keysWithDefaultsStillCountAsKnown() throws Exception {
    // reading a key that isn't present still teaches the reader that the key is legal
    ConfigObject config = of("{}");
    config.strOf("name", "x");
    assertTrue(config.knownKeys().contains("name"));
    config.assertKnownKeys();
  }

  private interface Read {
    void run(ConfigObject config) throws ConfigException;
  }

  private static void expectFailure(ConfigObject config, Read read, String expected) {
    try {
      read.run(config);
      fail("expected a refusal mentioning: " + expected);
    } catch (ConfigException ex) {
      assertTrue("wanted '" + expected + "' in: " + ex.getMessage(), ex.getMessage().contains(expected));
    }
  }
}
