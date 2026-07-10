package org.modeljars;

/** Runtime exception for invalid or unreadable ModelJars metadata. */
public final class ModelJarException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ModelJarException(String message) {
    super(message);
  }

  public ModelJarException(String message, Throwable cause) {
    super(message, cause);
  }
}
