package org.modeljars;

/** Runtime exception for invalid or unreadable ModelJars metadata. */
public final class ModelJarException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a diagnostic message.
   *
   * @param message diagnostic message
   */
  public ModelJarException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a diagnostic message and underlying cause.
   *
   * @param message diagnostic message
   * @param cause underlying failure
   */
  public ModelJarException(String message, Throwable cause) {
    super(message, cause);
  }
}
