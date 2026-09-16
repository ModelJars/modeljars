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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Vendor-published generation settings read from files at a model's pinned revision.
 *
 * <p>Every value is optional: a value the pinned files do not publish is absent rather than
 * guessed. {@link #provenance()} maps each recorded value to {@code sourceId:key} references into
 * {@link #sources()}. Keys are {@code sampling.<name>}, {@code eosTokenId.<id>}, {@code
 * reasoning.markers}, and {@code reasoning.thinkingDefault}.
 *
 * @param sources pinned files the values were read from
 * @param sampling recommended sampling values keyed by {@code temperature}, {@code topP}, {@code
 *     topK}, {@code minP}, and {@code repetitionPenalty}
 * @param doSample whether the vendor configuration enables sampling
 * @param eosTokenIds every end-of-sequence token id the pinned files declare
 * @param reasoningOpenToken token that opens a reasoning block
 * @param reasoningCloseToken token that closes a reasoning block
 * @param thinkingDefault whether the chat template enables thinking by default
 * @param provenance value key to {@code sourceId:key} references
 * @param conflicts value key to disagreeing {@code sourceId:key=value} entries
 */
public record ModelGenerationProfile(
    List<ModelProfileSource> sources,
    Map<String, Double> sampling,
    Optional<Boolean> doSample,
    List<Integer> eosTokenIds,
    Optional<ReasoningToken> reasoningOpenToken,
    Optional<ReasoningToken> reasoningCloseToken,
    Optional<Boolean> thinkingDefault,
    Map<String, List<String>> provenance,
    Map<String, List<String>> conflicts) {
  /** Copies every collection and rejects null components. */
  public ModelGenerationProfile {
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    sampling = Map.copyOf(Objects.requireNonNull(sampling, "sampling"));
    doSample = Objects.requireNonNull(doSample, "doSample");
    eosTokenIds = List.copyOf(Objects.requireNonNull(eosTokenIds, "eosTokenIds"));
    reasoningOpenToken = Objects.requireNonNull(reasoningOpenToken, "reasoningOpenToken");
    reasoningCloseToken = Objects.requireNonNull(reasoningCloseToken, "reasoningCloseToken");
    thinkingDefault = Objects.requireNonNull(thinkingDefault, "thinkingDefault");
    provenance = Map.copyOf(Objects.requireNonNull(provenance, "provenance"));
    conflicts = Map.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
  }

  /**
   * A reasoning marker token.
   *
   * @param text token text
   * @param id vocabulary id
   */
  public record ReasoningToken(String text, int id) {
    /** Validates the token. */
    public ReasoningToken {
      if (text == null || text.isEmpty() || id < 0) {
        throw new IllegalArgumentException("reasoning token requires text and a non-negative id");
      }
    }
  }

  /**
   * Returns the recommended temperature.
   *
   * @return temperature, when published
   */
  public OptionalDouble temperature() {
    return value("temperature");
  }

  /**
   * Returns the recommended nucleus-sampling probability.
   *
   * @return top-p, when published
   */
  public OptionalDouble topP() {
    return value("topP");
  }

  /**
   * Returns the recommended top-k cutoff.
   *
   * @return top-k, when published
   */
  public OptionalInt topK() {
    OptionalDouble value = value("topK");
    return value.isPresent() ? OptionalInt.of((int) value.getAsDouble()) : OptionalInt.empty();
  }

  /**
   * Returns the recommended minimum-probability cutoff.
   *
   * @return min-p, when published
   */
  public OptionalDouble minP() {
    return value("minP");
  }

  /**
   * Returns the recommended repetition penalty.
   *
   * @return repetition penalty, when published
   */
  public OptionalDouble repetitionPenalty() {
    return value("repetitionPenalty");
  }

  private OptionalDouble value(String name) {
    Double value = sampling.get(name);
    return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
  }
}
