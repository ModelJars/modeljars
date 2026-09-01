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

/**
 * Auditable numerical, ordering, and latency evidence for one exact reranker artifact.
 *
 * <p>A qualified claim must reproduce both the unquantized reference logits and an independent
 * implementation of the same quantized bytes, preserve the expected ranking, and remain inside the
 * measured second-stage latency envelope.
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

  /** Largest accepted absolute logit delta against the unquantized ONNX model. */
  public static final double MAXIMUM_ONNX_LOGIT_DELTA = 0.15;

  /** Largest accepted absolute logit delta against the same quantized artifact. */
  public static final double MAXIMUM_SAME_ARTIFACT_ORACLE_LOGIT_DELTA = 0.05;

  /** Largest accepted median cold-load time on the controlled qualification host. */
  public static final double MAXIMUM_COLD_LOAD_MILLIS = 1_000.0;

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
        requireMetric(maximumSameArtifactOracleLogitDelta, "maximumSameArtifactOracleLogitDelta");
    medianColdLoadMillis = requireMetric(medianColdLoadMillis, "medianColdLoadMillis");
    maximumPairP95Millis = requireMetric(maximumPairP95Millis, "maximumPairP95Millis");
    maximumBatchP95Millis = requireMetric(maximumBatchP95Millis, "maximumBatchP95Millis");
    medianBatchDocumentsPerSecond =
        requireMetric(medianBatchDocumentsPerSecond, "medianBatchDocumentsPerSecond");
    if (qualified
        && (maximumOnnxLogitDelta > MAXIMUM_ONNX_LOGIT_DELTA
            || maximumSameArtifactOracleLogitDelta > MAXIMUM_SAME_ARTIFACT_ORACLE_LOGIT_DELTA
            || !topKOrderExact
            || medianColdLoadMillis > MAXIMUM_COLD_LOAD_MILLIS
            || maximumPairP95Millis > MAXIMUM_PAIR_P95_MILLIS
            || maximumBatchP95Millis > MAXIMUM_BATCH_P95_MILLIS)) {
      throw new IllegalArgumentException(
          "qualified reranking evidence must pass numerical, ordering, and latency gates");
    }
  }

  /** Returns true only when the exact artifact passed every production gate. */
  public boolean productionUsable() {
    return qualified;
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
}
