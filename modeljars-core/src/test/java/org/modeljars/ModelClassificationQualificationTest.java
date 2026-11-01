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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelClassificationQualificationTest {
  private static final String SHA = "a".repeat(64);

  static Map<String, Double> qualityScores() {
    return ModelClassificationQualification.qualityMetrics().stream()
        .collect(java.util.stream.Collectors.toMap(k -> k, k -> .5));
  }

  @Test
  void upstreamFidelityAllowsDisclosedLowAbsoluteAccuracy() {
    var quality = new HashMap<>(qualityScores());
    quality.put("goemotions.microF1", .496);
    assertTrue(evidence(true, true, quality, buckets(3)).qualified());
  }

  static List<ModelClassificationQualification.PerformanceBucket> buckets(int count) {
    var rows = new ArrayList<ModelClassificationQualification.PerformanceBucket>();
    for (int pair = 1; pair <= count; pair++) {
      for (String shape : ModelClassificationQualification.performanceShapes()) {
        rows.add(new ModelClassificationQualification.PerformanceBucket(pair, shape, 30, 1, 1));
      }
    }
    return rows;
  }

  static ModelClassificationQualification evidence(
      boolean qualified,
      boolean released,
      Map<String, Double> quality,
      List<ModelClassificationQualification.PerformanceBucket> buckets) {
    return new ModelClassificationQualification(
        "fixture",
        "rust-ffm",
        "models-0.0.0",
        URI.create("https://example.invalid/report"),
        SHA,
        List.of(
            new ModelArtifactFile(
                "model.safetensors",
                "weights",
                URI.create("https://example.invalid/weights"),
                SHA,
                12),
            new ModelArtifactFile(
                "tokenizer.json",
                "tokenizer",
                URI.create("https://example.invalid/tokenizer"),
                SHA,
                12)),
        quality,
        Math.max(0, quality.values().stream().mapToDouble(v -> .5 - v).max().orElse(0)),
        qualityScores(),
        ModelClassificationQualification.requiredCohortCases(),
        0,
        true,
        buckets,
        released,
        qualified);
  }

  @Test
  void requiresAllThreeCompletePairsAndReleasedRuntime() {
    var quality = qualityScores();
    assertTrue(evidence(true, true, quality, buckets(3)).productionUsable());
    assertFalse(evidence(true, true, quality, buckets(3)).confidenceQualified());
    assertThrows(IllegalArgumentException.class, () -> evidence(true, false, quality, buckets(3)));
    assertThrows(IllegalArgumentException.class, () -> evidence(true, true, quality, buckets(2)));
    var missing = buckets(3);
    missing.removeLast();
    assertThrows(IllegalArgumentException.class, () -> evidence(true, true, quality, missing));
    var duplicate = buckets(3);
    duplicate.add(duplicate.getFirst());
    assertThrows(IllegalArgumentException.class, () -> evidence(true, true, quality, duplicate));
  }

  @Test
  void rejectsOneFailedBucketWithoutAveragingItAway() {
    var rows = buckets(3);
    var first = rows.getFirst();
    rows.set(
        0,
        new ModelClassificationQualification.PerformanceBucket(
            first.pair(), first.shape(), 30, .799, 1));
    assertThrows(IllegalArgumentException.class, () -> evidence(true, true, qualityScores(), rows));
    assertFalse(evidence(false, true, qualityScores(), rows).productionUsable());
  }

  @Test
  void rejectsMissingNonfiniteAndFailedQualityCohorts() {
    var quality = new HashMap<>(qualityScores());
    quality.put("goemotions.microF1", .494);
    assertThrows(IllegalArgumentException.class, () -> evidence(true, true, quality, buckets(3)));
    quality.put("goemotions.microF1", Double.NaN);
    assertThrows(IllegalArgumentException.class, () -> evidence(false, true, quality, buckets(3)));
    quality.remove("goemotions.microF1");
    assertThrows(IllegalArgumentException.class, () -> evidence(false, true, quality, buckets(3)));
  }
}
