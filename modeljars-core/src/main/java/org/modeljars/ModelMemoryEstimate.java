/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
