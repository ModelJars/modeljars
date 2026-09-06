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

import java.util.OptionalDouble;

/**
 * Auditable numerical, ordering, and latency evidence for one exact reranker artifact.
 *
 * <p>A qualified claim must reproduce logits from an independent implementation of the exact
 * artifact, preserve the expected ranking, and remain inside the measured second-stage latency
 * envelope. Quantized artifacts can additionally retain comparison with their unquantized source.
 *
 * @param modelId stable ModelJars catalog identifier
 * @param model human-readable upstream model name
 * @param backend Models backend used for qualification
 * @param backendVersion exact Models revision or version used for qualification
 * @param workload versioned qualification workload identifier
 * @param artifactSha256 SHA-256 digest of the qualified model bytes
 * @param artifactSizeBytes size of the qualified model artifact
 * @param report revision-pinned qualification report URI
 * @param reportSha256 SHA-256 digest of the qualification report
 * @param qualified whether the artifact passed every admission gate
 * @param pairs number of query-document pairs in the correctness workload
 * @param maximumOnnxLogitDelta largest absolute logit delta from the primary reference; the
 *     component retains its original name for binary compatibility
 * @param maximumSameArtifactOracleLogitDelta largest absolute logit delta from an optional second
 *     reference implementation, or {@link Double#NaN} when the primary reference already consumed
 *     the exact artifact; the component retains its original name for binary compatibility
 * @param topKOrderExact whether the retained top-k ordering exactly matched the reference
 * @param medianColdLoadMillis median cold-load time on the controlled host
 * @param maximumPairP95Millis largest pair-scoring p95 across controlled processes
 * @param maximumBatchP95Millis largest batch-scoring p95 across controlled processes
 * @param medianBatchDocumentsPerSecond median controlled batch throughput
 */
public record ModelRerankingQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String workload,
    String artifactSha256,
    long artifactSizeBytes,
    String report,
    String reportSha256,
    boolean qualified,
    int pairs,
    double maximumOnnxLogitDelta,
    double maximumSameArtifactOracleLogitDelta,
    boolean topKOrderExact,
    double medianColdLoadMillis,
    double maximumPairP95Millis,
    double maximumBatchP95Millis,
    double medianBatchDocumentsPerSecond) {

  /** Largest accepted absolute logit delta against the primary reference runtime. */
  public static final double MAXIMUM_ONNX_LOGIT_DELTA = 0.15;

  /** Largest accepted absolute logit delta against an independent exact-artifact implementation. */
  public static final double MAXIMUM_SAME_ARTIFACT_ORACLE_LOGIT_DELTA = 0.05;

  /** Largest accepted median cold-load time on the controlled qualification host. */
  public static final double MAXIMUM_COLD_LOAD_MILLIS = 5_000.0;

  /** Largest accepted pair p95 on the controlled qualification host. */
  public static final double MAXIMUM_PAIR_P95_MILLIS = 250.0;

  /** Largest accepted six-document batch p95 on the controlled qualification host. */
  public static final double MAXIMUM_BATCH_P95_MILLIS = 1_200.0;

  /** Validates a qualification claim and binds it to an exact artifact and evidence report. */
  public ModelRerankingQualification {
    modelId = requireText(modelId, "modelId");
    model = requireText(model, "model");
    backend = requireText(backend, "backend");
    backendVersion = requireText(backendVersion, "backendVersion");
    workload = requireText(workload, "workload");
    artifactSha256 = requireDigest(artifactSha256, "artifactSha256");
    report = requireText(report, "report");
    reportSha256 = requireDigest(reportSha256, "reportSha256");
    if (artifactSizeBytes < 1) {
      throw new IllegalArgumentException("artifactSizeBytes must be positive");
    }
    if (pairs < 1) {
      throw new IllegalArgumentException("pairs must be positive");
    }
    maximumOnnxLogitDelta = requireMetric(maximumOnnxLogitDelta, "maximumOnnxLogitDelta");
    maximumSameArtifactOracleLogitDelta =
        requireOptionalMetric(
            maximumSameArtifactOracleLogitDelta, "maximumSameArtifactOracleLogitDelta");
    medianColdLoadMillis = requireMetric(medianColdLoadMillis, "medianColdLoadMillis");
    maximumPairP95Millis = requireMetric(maximumPairP95Millis, "maximumPairP95Millis");
    maximumBatchP95Millis = requireMetric(maximumBatchP95Millis, "maximumBatchP95Millis");
    medianBatchDocumentsPerSecond =
        requireMetric(medianBatchDocumentsPerSecond, "medianBatchDocumentsPerSecond");
    if (qualified
        && (maximumOnnxLogitDelta > MAXIMUM_ONNX_LOGIT_DELTA
            || (Double.isFinite(maximumSameArtifactOracleLogitDelta)
                && maximumSameArtifactOracleLogitDelta > MAXIMUM_SAME_ARTIFACT_ORACLE_LOGIT_DELTA)
            || !topKOrderExact
            || medianColdLoadMillis > MAXIMUM_COLD_LOAD_MILLIS
            || maximumPairP95Millis > MAXIMUM_PAIR_P95_MILLIS
            || maximumBatchP95Millis > MAXIMUM_BATCH_P95_MILLIS)) {
      throw new IllegalArgumentException(
          "qualified reranking evidence must pass numerical, ordering, and latency gates");
    }
  }

  /**
   * Returns true only when the exact artifact passed every production gate.
   *
   * @return whether the qualification is production-usable
   */
  public boolean productionUsable() {
    return qualified;
  }

  /**
   * Returns the largest absolute logit delta from the qualification's primary reference runtime.
   *
   * @return largest primary-reference logit delta
   */
  public double maximumReferenceLogitDelta() {
    return maximumOnnxLogitDelta;
  }

  /**
   * Returns the largest absolute logit delta from an optional second reference implementation.
   *
   * <p>The value is {@link Double#NaN} when no second reference was recorded. Use {@link
   * #sameArtifactReferenceLogitDelta()} for an explicit optional result.
   *
   * @return largest exact-artifact logit delta
   */
  public double maximumSameArtifactReferenceLogitDelta() {
    return maximumSameArtifactOracleLogitDelta;
  }

  /**
   * Returns the optional second-reference comparison without a sentinel value.
   *
   * @return the second-reference delta, or empty when the exact-artifact primary was sufficient
   */
  public OptionalDouble sameArtifactReferenceLogitDelta() {
    return Double.isFinite(maximumSameArtifactOracleLogitDelta)
        ? OptionalDouble.of(maximumSameArtifactOracleLogitDelta)
        : OptionalDouble.empty();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static String requireDigest(String value, String field) {
    String digest = requireText(value, field).toLowerCase(java.util.Locale.ROOT);
    if (!digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a SHA-256 digest");
    }
    return digest;
  }

  private static double requireMetric(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(field + " must be finite and >= 0, got: " + value);
    }
    return value;
  }

  private static double requireOptionalMetric(double value, String field) {
    if (Double.isNaN(value)) {
      return value;
    }
    return requireMetric(value, field);
  }
}
