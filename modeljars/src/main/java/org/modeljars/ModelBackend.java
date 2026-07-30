package org.modeljars;

/**
 * Backend policy used when opening a qualified ModelJar.
 *
 * <p>{@link #AUTO} selects the backend recorded by the artifact's production qualification.
 */
public enum ModelBackend {
  /** Select the qualified backend automatically. */
  AUTO(null),

  /** Require the Java Vector API backend. */
  JAVA("pure-java"),

  /** Require the Models-owned Rust FFM kernel backend. */
  NATIVE("rust-ffm");

  private final String backendId;

  ModelBackend(String backendId) {
    this.backendId = backendId;
  }

  String backendId() {
    if (backendId == null) {
      throw new IllegalStateException("AUTO does not have a fixed backend ID");
    }
    return backendId;
  }
}
