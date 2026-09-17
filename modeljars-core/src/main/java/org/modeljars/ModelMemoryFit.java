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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Memory fit COMPUTED from GGUF header metadata; it is not a measurement.
 *
 * <p>{@code totalBytes = weightBytes + fixedOverheadBytes + KV cache}, where each attention layer
 * stores {@code min(context, slidingWindow)} tokens of keys and values at the given KV element
 * type. The fixed overhead is a stated planning constant for the runtime, not a measured value.
 * Recurrent state of hybrid layers is excluded.
 *
 * @param weightBytes artifact bytes
 * @param fixedOverheadBytes stated runtime overhead constant
 * @param contextLength advertised maximum context length
 * @param slidingWindow sliding-window size, when the header declares one
 * @param upperBound whether the KV size is an upper bound (for example an undeclared window
 *     pattern)
 * @param recurrentStateExcluded whether recurrent layer state was excluded
 * @param notes human-readable caveats
 * @param kvCache per-KV-type tables
 */
public record ModelMemoryFit(
    long weightBytes,
    long fixedOverheadBytes,
    int contextLength,
    OptionalInt slidingWindow,
    boolean upperBound,
    boolean recurrentStateExcluded,
    List<String> notes,
    List<KvCacheFit> kvCache) {
  /** Copies collections and rejects invalid sizes. */
  public ModelMemoryFit {
    if (weightBytes <= 0 || fixedOverheadBytes < 0 || contextLength <= 0) {
      throw new IllegalArgumentException("memory fit sizes must be positive");
    }
    slidingWindow = Objects.requireNonNull(slidingWindow, "slidingWindow");
    notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    kvCache = List.copyOf(Objects.requireNonNull(kvCache, "kvCache"));
  }

  /**
   * Returns the table for one KV element type.
   *
   * @param type KV element type, such as {@code f16} or {@code q8_0}
   * @return the table, when computable for this model
   */
  public Optional<KvCacheFit> kvCache(String type) {
    return kvCache.stream().filter(fit -> fit.type().equals(type)).findFirst();
  }

  /**
   * Returns the computed total at one catalog context point.
   *
   * <p>Only the context points recorded in the catalog are available; nothing is interpolated.
   *
   * @param type KV element type, such as {@code f16} or {@code q8_0}
   * @param contextTokens context length recorded in the table
   * @return weights plus overhead plus KV cache, when recorded for this type and context
   */
  public Optional<ContextTotal> contextTotal(String type, int contextTokens) {
    return kvCache(type).stream()
        .flatMap(fit -> fit.contexts().stream())
        .filter(context -> context.contextTokens() == contextTokens)
        .findFirst();
  }

  /**
   * Memory fit for one KV element type.
   *
   * @param type KV element type
   * @param bytesPerToken KV bytes per token for full-attention layers
   * @param slidingWindowBytesPerToken KV bytes per token for sliding-window layers, up to the
   *     window
   * @param contexts totals at the catalog context points
   * @param budgets largest context that fits each memory budget
   */
  public record KvCacheFit(
      String type,
      long bytesPerToken,
      long slidingWindowBytesPerToken,
      List<ContextTotal> contexts,
      List<BudgetFit> budgets) {
    /** Copies collections. */
    public KvCacheFit {
      Objects.requireNonNull(type, "type");
      contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
      budgets = List.copyOf(Objects.requireNonNull(budgets, "budgets"));
    }
  }

  /**
   * Computed memory at one context length.
   *
   * @param contextTokens context length
   * @param kvBytes KV cache bytes
   * @param totalBytes weights plus overhead plus KV cache
   */
  public record ContextTotal(int contextTokens, long kvBytes, long totalBytes) {}

  /**
   * Largest context that fits one memory budget.
   *
   * @param budgetBytes memory budget
   * @param maxContextTokens largest fitting context, or 0 when the model does not fit
   * @param limitedBy {@code memory} or {@code context-length}
   */
  public record BudgetFit(long budgetBytes, int maxContextTokens, String limitedBy) {}
}
