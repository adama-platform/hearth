package io.hearth.store;

/** A database that does not match what the code expects, and could not be brought into line. */
public class SchemaException extends Exception {
  public SchemaException(String message) {
    super(message);
  }

  public SchemaException(String message, Throwable cause) {
    super(message, cause);
  }
}
