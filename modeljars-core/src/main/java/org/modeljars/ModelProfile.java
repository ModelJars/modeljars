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

/**
 * Generation profile and computed memory fit for one exact catalog artifact.
 *
 * <p>Profiles are published in the aggregate catalog, not in immutable marker JARs, so adding or
 * correcting one never changes a marker coordinate.
 *
 * @param modelAlias catalog model ID
 * @param artifactSha256 SHA-256 of the artifact the profile describes
 * @param generation vendor-published generation settings, when any were found
 * @param memoryFit computed memory fit, when the artifact is a GGUF generator
 * @param repetitionLoop measured repetition-loop stop rates at the documented generation profile;
 *     empty when no run is recorded ("not measured")
 */
public record ModelProfile(
    String modelAlias,
    String artifactSha256,
    Optional<ModelGenerationProfile> generation,
    Optional<ModelMemoryFit> memoryFit,
    List<ModelRepetitionLoopMeasurement> repetitionLoop) {
  /** Rejects null components. */
  public ModelProfile {
    Objects.requireNonNull(modelAlias, "modelAlias");
    Objects.requireNonNull(artifactSha256, "artifactSha256");
    generation = Objects.requireNonNull(generation, "generation");
    memoryFit = Objects.requireNonNull(memoryFit, "memoryFit");
    repetitionLoop = List.copyOf(Objects.requireNonNull(repetitionLoop, "repetitionLoop"));
  }

  /**
   * Creates a profile without repetition-loop measurements.
   *
   * @param modelAlias catalog model ID
   * @param artifactSha256 SHA-256 of the artifact the profile describes
   * @param generation vendor-published generation settings, when any were found
   * @param memoryFit computed memory fit, when the artifact is a GGUF generator
   */
  public ModelProfile(
      String modelAlias,
      String artifactSha256,
      Optional<ModelGenerationProfile> generation,
      Optional<ModelMemoryFit> memoryFit) {
    this(modelAlias, artifactSha256, generation, memoryFit, List.of());
  }
}
