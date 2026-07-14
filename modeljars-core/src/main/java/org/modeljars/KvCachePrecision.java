package org.modeljars;

/** Element precision used when estimating key/value cache storage. */
public enum KvCachePrecision {
  FLOAT32(4),
  FLOAT16(2),
  INT8(1);

  private final int bytesPerElement;

  KvCachePrecision(int bytesPerElement) {
    this.bytesPerElement = bytesPerElement;
  }

  public int bytesPerElement() {
    return bytesPerElement;
  }
}
