package org.modeljars;

import java.util.Objects;

/**
 * A lower-bound memory estimate containing model weights and the KV cache only.
 *
 * <p>Backend workspaces, graph buffers, repacking, allocator overhead, and the JVM are deliberately
 * excluded because they depend on the selected runtime and hardware.
 *
 * @param contextTokens context-window size used for the estimate
 * @param weightBytes model-weight storage in bytes
 * @param kvCacheBytes KV-cache storage in bytes
 * @param minimumBytes sum of model weights and KV cache in bytes
 * @param kvCachePrecision precision assumed for each KV-cache element
 */
public record ModelMemoryEstimate(
    int contextTokens,
    long weightBytes,
    long kvCacheBytes,
    long minimumBytes,
    KvCachePrecision kvCachePrecision) {
  /** Validates that the estimate contains positive, internally consistent byte counts. */
  public ModelMemoryEstimate {
    if (contextTokens <= 0) {
      throw new IllegalArgumentException("contextTokens must be > 0");
    }
    if (weightBytes <= 0 || kvCacheBytes <= 0 || minimumBytes <= 0) {
      throw new IllegalArgumentException("memory byte counts must be > 0");
    }
    if (minimumBytes != Math.addExact(weightBytes, kvCacheBytes)) {
      throw new IllegalArgumentException("minimumBytes must equal weights plus KV cache");
    }
    kvCachePrecision = Objects.requireNonNull(kvCachePrecision, "kvCachePrecision");
  }

  /**
   * Indicates that runtime-specific memory is outside this lower-bound estimate.
   *
   * @return always {@code true}
   */
  public boolean excludesRuntimeOverhead() {
    return true;
  }
}
