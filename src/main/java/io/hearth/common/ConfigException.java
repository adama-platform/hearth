package io.hearth.common;

/**
 * A configuration problem the operator has to fix. These are always fatal at boot; the server
 * never starts with a configuration it doesn't fully understand.
 */
public class ConfigException extends Exception {
  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
