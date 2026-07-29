package org.modeljars;

/** Element precision used when estimating key/value cache storage. */
public enum KvCachePrecision {
  /** 32-bit floating-point key/value elements. */
  FLOAT32(4),

  /** 16-bit floating-point key/value elements. */
  FLOAT16(2),

  /** 8-bit integer key/value elements. */
  INT8(1);

  private final int bytesPerElement;

  KvCachePrecision(int bytesPerElement) {
    this.bytesPerElement = bytesPerElement;
  }

  /**
   * Returns the storage required by one cache element.
   *
   * @return bytes per element
   */
  public int bytesPerElement() {
    return bytesPerElement;
  }
}
