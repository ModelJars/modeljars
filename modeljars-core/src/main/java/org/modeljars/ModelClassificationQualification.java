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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable schema-classification qualification evidence.
 *
 * @param modelId catalog model identity
 * @param backend qualified backend identifier
 * @param backendVersion measured released backend version
 * @param report retained evidence URI
 * @param reportSha256 SHA-256 of the retained evidence
 * @param files complete pinned model snapshot
 * @param quality measured metrics for all required cohorts
 * @param maximumOracleQualityLoss largest measured loss against the publisher runtime
 * @param maximumBaseQualityRegression largest measured loss against original weights
 * @param qualityCases total held-out quality cases
 * @param inferenceErrors failed quality inferences
 * @param numericalParityPassed whether numerical checks passed
 * @param performance complete paired CPU measurements
 * @param releasedRuntimeVerified whether released artifacts reproduced the gates
 * @param qualified whether all catalog admission requirements have passed
 */
public record ModelClassificationQualification(
    String modelId,
    String backend,
    String backendVersion,
    URI report,
    String reportSha256,
    List<ModelArtifactFile> files,
    Map<String, Double> quality,
    double maximumOracleQualityLoss,
    double maximumBaseQualityRegression,
    int qualityCases,
    int inferenceErrors,
    boolean numericalParityPassed,
    List<PerformanceBucket> performance,
    boolean releasedRuntimeVerified,
    boolean qualified) {

  /**
   * One complete timed bucket from one paired CPU replication.
   *
   * @param pair replication index, starting at one
   * @param shape registered workload shape
   * @param samples measured requests after warmup
   * @param throughputRatio Models throughput divided by publisher throughput
   * @param p95LatencyRatio Models p95 latency divided by publisher p95 latency
   */
  public record PerformanceBucket(
      int pair, String shape, int samples, double throughputRatio, double p95LatencyRatio) {
    /** Rejects malformed measurements, including nonfinite ratios. */
    public PerformanceBucket {
      if (pair < 1 || samples < 1)
        throw new IllegalArgumentException("positive pair and sample count required");
      shape = text(shape, "shape");
      if (!Double.isFinite(throughputRatio)
          || throughputRatio <= 0
          || !Double.isFinite(p95LatencyRatio)
          || p95LatencyRatio <= 0) {
        throw new IllegalArgumentException("performance ratios must be finite and positive");
      }
    }

    /**
     * Checks this bucket against the registered CPU gates.
     *
     * @return whether this bucket clears the registered CPU gates
     */
    public boolean passed() {
      return samples >= 30 && throughputRatio >= .8 && p95LatencyRatio <= 1.5;
    }
  }

  /** Validates complete artifact binding and enforces every gate for a qualified claim. */
  public ModelClassificationQualification {
    modelId = text(modelId, "modelId");
    backend = text(backend, "backend");
    if (!backend.equals("rust-ffm"))
      throw new IllegalArgumentException("unsupported classification backend");
    backendVersion = text(backendVersion, "backendVersion");
    report = Objects.requireNonNull(report, "report");
    if (!report.isAbsolute()) throw new IllegalArgumentException("report must be an absolute URI");
    reportSha256 = text(reportSha256, "reportSha256");
    if (!reportSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("report digest must be SHA-256");
    files = List.copyOf(files);
    if (files.size() < 2
        || files.stream().map(ModelArtifactFile::path).distinct().count() != files.size()) {
      throw new IllegalArgumentException("complete, unique snapshot files required");
    }
    quality = Map.copyOf(quality);
    if (!quality.keySet().equals(qualityFloors().keySet()))
      throw new IllegalArgumentException("classification quality cohort coverage differs");
    for (double value : quality.values()) {
      if (!Double.isFinite(value) || value < 0 || value > 1)
        throw new IllegalArgumentException("quality metrics must be in [0,1]");
    }
    if (!Double.isFinite(maximumOracleQualityLoss)
        || maximumOracleQualityLoss < 0
        || !Double.isFinite(maximumBaseQualityRegression)
        || maximumBaseQualityRegression < 0) {
      throw new IllegalArgumentException("quality losses must be finite and nonnegative");
    }
    if (qualityCases < 1 || inferenceErrors < 0)
      throw new IllegalArgumentException("invalid quality outcome counts");
    performance = List.copyOf(performance);
    var unique =
        performance.stream().map(b -> b.pair() + ":" + b.shape()).collect(Collectors.toSet());
    if (unique.size() != performance.size())
      throw new IllegalArgumentException("duplicate performance bucket");
    if (qualified
        && (!releasedRuntimeVerified
            || !numericalParityPassed
            || inferenceErrors != 0
            || qualityCases < 1529
            || maximumOracleQualityLoss > .005
            || maximumBaseQualityRegression > .005
            || !qualityPasses(quality)
            || !performancePasses(performance))) {
      throw new IllegalArgumentException(
          "qualified classification evidence must pass every registered gate and released-runtime verification");
    }
  }

  /**
   * Reports catalog admission for this exact snapshot.
   *
   * @return whether this snapshot has completed every admission step
   */
  public boolean productionUsable() {
    return qualified;
  }

  /**
   * Reports whether classifier scores carry a calibrated-confidence claim.
   *
   * @return false; this policy makes no calibrated-confidence claim
   */
  public boolean confidenceQualified() {
    return false;
  }

  /**
   * Returns the registered absolute quality floors.
   *
   * @return frozen quality floors for original and untouched fresh cohorts
   */
  public static Map<String, Double> qualityFloors() {
    var floors = new LinkedHashMap<String, Double>();
    floors.put("original.jevbench.accuracy", .7);
    floors.put("original.jevbench.choice", .6);
    floors.put("original.jevbench.noul", .7);
    floors.put("original.clinc-banking.macroF1", .7);
    floors.put("original.goemotions.microF1", .7);
    floors.put("original.goemotions.macroF1", .6);
    floors.put("fresh.boolq.accuracy", .7);
    floors.put("fresh.goemotions.microF1", .7);
    floors.put("fresh.goemotions.macroF1", .6);
    for (String domain :
        List.of(
            "banking",
            "credit_cards",
            "auto_and_commute",
            "travel",
            "home",
            "work",
            "kitchen_and_dining",
            "small_talk",
            "utility",
            "meta")) {
      floors.put("fresh.clinc-" + domain + ".macroF1", .7);
    }
    return Map.copyOf(floors);
  }

  /**
   * Returns every registered schema-inclusive CPU workload.
   *
   * @return registered CPU shapes, including schema tokens
   */
  public static Set<String> performanceShapes() {
    var shapes = new ArrayList<String>();
    for (int tokens : new int[] {32, 128, 256, 512}) {
      for (int labels : new int[] {2, 8, 32}) {
        if (tokens == 32 && labels == 32) continue;
        shapes.add("tokens" + tokens + "-labels" + labels + "-tasks1");
      }
    }
    shapes.add("tokens128-labels8-tasks2");
    shapes.add("tokens512-labels8-tasks2");
    return Set.copyOf(shapes);
  }

  /**
   * Matches all configuration, tokenizer and weight files.
   *
   * @param descriptor candidate model descriptor
   * @return whether backend and complete snapshot identity match
   */
  public boolean matches(ModelJarDescriptor descriptor) {
    if (!modelId.equals(descriptor.alias())
        || !descriptor.supportsBackend(backend)
        || !"gliner2".equals(descriptor.architecture())
        || !"safetensors".equals(descriptor.format())) return false;
    return identity(files).equals(identity(descriptor.files()));
  }

  private static Map<String, String> identity(List<ModelArtifactFile> files) {
    return files.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                ModelArtifactFile::path,
                file -> file.sha256() + ":" + file.sizeBytes() + ":" + file.role()));
  }

  private static boolean qualityPasses(Map<String, Double> metrics) {
    return qualityFloors().entrySet().stream()
        .allMatch(gate -> metrics.get(gate.getKey()) >= gate.getValue());
  }

  private static boolean performancePasses(List<PerformanceBucket> buckets) {
    var pairs = buckets.stream().collect(Collectors.groupingBy(PerformanceBucket::pair));
    if (pairs.size() < 3) return false;
    for (int pair = 1; pair <= pairs.size(); pair++) {
      var rows = pairs.get(pair);
      if (rows == null
          || !rows.stream()
              .map(PerformanceBucket::shape)
              .collect(Collectors.toSet())
              .equals(performanceShapes())
          || rows.stream().anyMatch(row -> !row.passed())) return false;
    }
    return true;
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
