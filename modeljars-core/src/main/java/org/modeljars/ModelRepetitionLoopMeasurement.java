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
 * MEASURED repetition-loop stop rate of one exact artifact at its documented generation profile.
 *
 * <p>Recorded in {@code catalog/generation-safety.json} only from a real run with the Models
 * repetition-loop detector enabled ({@code RepetitionLoopDetection}); the stop count is the run's
 * {@code repetitionLoopStops()} delta. A model with no recorded run has no measurement, which means
 * "not measured", never a zero rate. The rate depends on the detector thresholds, which are
 * therefore part of the value.
 *
 * @param backend execution backend of the run
 * @param workload workload whose cases were generated
 * @param modelsVersion Models release that ran the detector
 * @param modelsRevision Models commit holding the raw report
 * @param maxSpan detector longest repeating span, in tokens
 * @param minRepeats detector consecutive copies required
 * @param minLoopTokens detector minimum tokens covered by the repeating run
 * @param generations completed generations
 * @param stops generations stopped with {@code REPETITION_LOOP}
 * @param report repository-relative raw report path
 * @param reportSha256 SHA-256 of the raw report
 */
public record ModelRepetitionLoopMeasurement(
    String backend,
    String workload,
    String modelsVersion,
    String modelsRevision,
    int maxSpan,
    int minRepeats,
    int minLoopTokens,
    int generations,
    int stops,
    String report,
    String reportSha256) {
  /** Rejects missing identity, a disabled detector, and inconsistent counts. */
  public ModelRepetitionLoopMeasurement {
    Objects.requireNonNull(backend, "backend");
    Objects.requireNonNull(workload, "workload");
    Objects.requireNonNull(modelsVersion, "modelsVersion");
    Objects.requireNonNull(modelsRevision, "modelsRevision");
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(reportSha256, "reportSha256");
    if (maxSpan <= 0 || minRepeats < 2 || minLoopTokens < 0) {
      throw new IllegalArgumentException("repetition-loop detector must be enabled");
    }
    if (generations < 1 || stops < 0 || stops > generations) {
      throw new IllegalArgumentException("stops must be between 0 and generations");
    }
  }

  /**
   * Returns the fraction of generations the detector stopped.
   *
   * @return {@code stops / generations}
   */
  public double stopRate() {
    return (double) stops / generations;
  }
}
