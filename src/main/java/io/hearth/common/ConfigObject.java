package io.hearth.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Typed reader over a Jackson ObjectNode, borrowed in spirit from Adama's ConfigObject.
 *
 * The difference is strictness. Every key read is recorded, and {@link #assertKnownKeys} fails
 * on anything left over. A typo in a domain.cfg is a misconfiguration, and a misconfiguration
 * that silently does nothing is the worst kind of security bug -- the operator believes a policy
 * is on when it isn't. We refuse to boot instead.
 */
public class ConfigObject {
  public final ObjectNode node;
  private final String where;
  private final TreeSet<String> touched;

  public ConfigObject(ObjectNode node, String where) {
    this.node = node;
    this.where = where;
    this.touched = new TreeSet<>();
  }

  public int intOf(String key, int defaultValue) throws ConfigException {
    touched.add(key);
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return defaultValue;
    }
    if (!v.isInt()) {
      throw new ConfigException(where + ": '" + key + "' must be an integer");
    }
    return v.intValue();
  }

  public boolean boolOf(String key, boolean defaultValue) throws ConfigException {
    touched.add(key);
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return defaultValue;
    }
    if (!v.isBoolean()) {
      throw new ConfigException(where + ": '" + key + "' must be true or false");
    }
    return v.booleanValue();
  }

  public String strOf(String key, String defaultValue) throws ConfigException {
    touched.add(key);
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return defaultValue;
    }
    if (!v.isTextual()) {
      throw new ConfigException(where + ": '" + key + "' must be a string");
    }
    return v.textValue();
  }

  public String[] stringsOf(String key, String[] defaultValue) throws ConfigException {
    touched.add(key);
    JsonNode vs = node.get(key);
    if (vs == null || vs.isNull()) {
      return defaultValue;
    }
    if (!vs.isArray()) {
      throw new ConfigException(where + ": '" + key + "' must be an array of strings");
    }
    ArrayList<String> strings = new ArrayList<>();
    for (int k = 0; k < vs.size(); k++) {
      JsonNode item = vs.get(k);
      if (!item.isTextual()) {
        throw new ConfigException(where + ": '" + key + "[" + k + "]' must be a string");
      }
      strings.add(item.textValue());
    }
    return strings.toArray(new String[0]);
  }

  /** a nested object; absent yields an empty object so callers can read defaults out of it */
  public ConfigObject child(String key) throws ConfigException {
    touched.add(key);
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return new ConfigObject(node.objectNode(), where + "." + key);
    }
    if (!v.isObject()) {
      throw new ConfigException(where + ": '" + key + "' must be an object");
    }
    return new ConfigObject((ObjectNode) v, where + "." + key);
  }

  /** every key present must have been read by now; anything else is a typo or a stale field */
  public void assertKnownKeys() throws ConfigException {
    List<String> unknown = new ArrayList<>();
    Iterator<String> it = node.fieldNames();
    while (it.hasNext()) {
      String field = it.next();
      if (!touched.contains(field)) {
        unknown.add(field);
      }
    }
    if (!unknown.isEmpty()) {
      throw new ConfigException(where + ": unknown key(s) " + String.join(", ", unknown) + "; known keys are " + String.join(", ", touched));
    }
  }

  /** the keys this reader understands, useful for --verbose and for error messages */
  public TreeSet<String> knownKeys() {
    return new TreeSet<>(touched);
  }
}
