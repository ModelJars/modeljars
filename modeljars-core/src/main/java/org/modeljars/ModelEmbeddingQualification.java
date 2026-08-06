/*
 * Copyright 2026 Integrallis Software, LLC
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
import java.util.Set;

/**
 * Auditable evidence that one exact embedding artifact is faithfully reproduced by a backend.
 *
 * <p>The counterpart to {@link ModelRagQualification}, but it certifies a different thing. A
 * generator is graded on how well it answers, because answer quality depends on the runtime. An
 * embedding model's retrieval quality is a published property of the weights — Qwen3-Embedding-0.6B
 * scores 64.64 on MTEB multilingual retrieval whoever runs it. What a runtime can get wrong is
 * reproducing the model: pooling, rotary embeddings, dequantization, normalization. So this record
 * gates on agreement with a reference implementation over the same bytes, and the published
 * retrieval quality then transfers by construction.
 *
 * <p>That choice avoids inventing a retrieval threshold. Absolute cutoffs are dataset-dependent —
 * nDCG@10 of 0.65 is strong on one corpus and weak on another — and the best embedding models score
 * near 0.71, so any fixed floor would be either meaningless or arbitrary. Agreement with a
 * reference has an unambiguous correct answer.
 *
 * <p>Measured agreement between the pure-Java backend and llama.cpp on Qwen3-Embedding-0.6B Q8_0
 * sits in a narrow 0.99946 to 0.99984 band across varied inputs. It is not bit-exact and should not
 * be: two independent implementations accumulate floating-point differently. A real defect lands
 * far below the floor rather than just under it.
 *
 * <p>Agreement is necessary but not sufficient. Two identically broken implementations would agree
 * perfectly, which is why the referenced report is expected to carry retrieval diagnostics as
 * corroboration even though they do not gate.
 *
 * @param modelId catalog alias of the qualified model
 * @param model display name and variant of the qualified model
 * @param backend normalized execution backend identifier
 * @param backendVersion exact backend version or build
 * @param workload stable equivalence-workload identifier
 * @param probeSetSha256 SHA-256 digest of the pinned probe texts
 * @param artifactSha256 SHA-256 digest of the qualified model artifact
 * @param artifactSizeBytes byte size of the qualified model artifact
 * @param report repository-relative path to the equivalence evidence report
 * @param reportSha256 SHA-256 digest of the evidence report
 * @param qualified whether the artifact met the production equivalence policy
 * @param probes number of probe texts compared against the oracle
 * @param embeddingDimension width of the vectors the model produces
 * @param pooling how per-position states were reduced to one vector
 * @param normalized whether vectors were scaled to unit length
 * @param oracleBackend reference implementation the vectors were compared against
 * @param oracleVersion exact pinned build of the reference implementation
 * @param minimumOracleCosine lowest cosine agreement observed across the probe set
 * @param meanOracleCosine mean cosine agreement across the probe set
 * @param maxComponentDelta largest absolute per-component difference observed
 * @param p50EmbedTextsPerSecond median sustained embedding throughput
 * @param oracleP50EmbedTextsPerSecond median throughput of the reference implementation
 * @param p95EmbedMillis 95th-percentile latency to embed one text
 * @param peakRssBytes peak resident set size observed during qualification
 * @param environment host and JVM identity the evidence was produced on
 */
public record ModelEmbeddingQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String workload,
    String probeSetSha256,
    String artifactSha256,
    long artifactSizeBytes,
    String report,
    String reportSha256,
    boolean qualified,
    int probes,
    int embeddingDimension,
    String pooling,
    boolean normalized,
    String oracleBackend,
    String oracleVersion,
    double minimumOracleCosine,
    double meanOracleCosine,
    double maxComponentDelta,
    double p50EmbedTextsPerSecond,
    double oracleP50EmbedTextsPerSecond,
    double p95EmbedMillis,
    long peakRssBytes,
    ModelQualificationEnvironment environment) {

  /**
   * Agreement below which an artifact is not considered reproduced.
   *
   * <p>Set beneath the measured 0.99946 floor so legitimate floating-point divergence passes, and
   * far above where a pooling, rotary-embedding or dequantization defect would land.
   */
  public static final double MINIMUM_ORACLE_COSINE = 0.999;

  /**
   * Throughput floor as a share of the reference implementation.
   *
   * <p>Matches the generation policy's requirement of at least 80% of Ollama decode speed, so one
   * convention covers both kinds of artifact and neither ages with hardware.
   */
  public static final double MINIMUM_ORACLE_THROUGHPUT_RATIO = 0.80;

  /** Reference implementations whose embedding output this policy accepts as authoritative. */
  private static final Set<String> SUPPORTED_ORACLES = Set.of("llama.cpp", "ollama");

  /** Pooling strategies the runtime can reproduce, so recorded evidence stays replayable. */
  private static final Set<String> SUPPORTED_POOLING = Set.of("last-token", "mean");

  /** Validates the recorded equivalence evidence and the artifact it certifies. */
  public ModelEmbeddingQualification {
    modelId = requireText(modelId, "modelId");
    model = requireText(model, "model");
    backend = requireText(backend, "backend");
    backendVersion = requireText(backendVersion, "backendVersion");
    workload = requireText(workload, "workload");
    probeSetSha256 = requireText(probeSetSha256, "probeSetSha256");
    artifactSha256 = requireText(artifactSha256, "artifactSha256");
    report = requireText(report, "report");
    reportSha256 = requireText(reportSha256, "reportSha256");
    pooling = requireText(pooling, "pooling");
    oracleBackend = requireText(oracleBackend, "oracleBackend");
    oracleVersion = requireText(oracleVersion, "oracleVersion");
    if (!SUPPORTED_POOLING.contains(pooling)) {
      throw new IllegalArgumentException(
          "pooling must be one of " + SUPPORTED_POOLING + ", got: " + pooling);
    }
    if (!SUPPORTED_ORACLES.contains(oracleBackend)) {
      throw new IllegalArgumentException(
          "oracleBackend must be one of " + SUPPORTED_ORACLES + ", got: " + oracleBackend);
    }
    if (artifactSizeBytes < 1) {
      throw new IllegalArgumentException("artifactSizeBytes must be positive");
    }
    if (probes < 1) {
      throw new IllegalArgumentException("probes must be positive");
    }
    if (embeddingDimension < 1) {
      throw new IllegalArgumentException("embeddingDimension must be positive");
    }
    if (peakRssBytes < 1) {
      throw new IllegalArgumentException("peakRssBytes must be positive");
    }
    minimumOracleCosine = requireCosine(minimumOracleCosine, "minimumOracleCosine");
    meanOracleCosine = requireCosine(meanOracleCosine, "meanOracleCosine");
    maxComponentDelta = requireMetric(maxComponentDelta, "maxComponentDelta");
    p50EmbedTextsPerSecond = requireMetric(p50EmbedTextsPerSecond, "p50EmbedTextsPerSecond");
    oracleP50EmbedTextsPerSecond =
        requireMetric(oracleP50EmbedTextsPerSecond, "oracleP50EmbedTextsPerSecond");
    p95EmbedMillis = requireMetric(p95EmbedMillis, "p95EmbedMillis");
    environment = Objects.requireNonNull(environment, "environment");
    if (qualified) {
      if (minimumOracleCosine < MINIMUM_ORACLE_COSINE) {
        throw new IllegalArgumentException(
            "qualified evidence must reproduce the reference implementation to at least "
                + MINIMUM_ORACLE_COSINE);
      }
      double ratio =
          oracleP50EmbedTextsPerSecond == 0.0
              ? Double.POSITIVE_INFINITY
              : p50EmbedTextsPerSecond / oracleP50EmbedTextsPerSecond;
      if (ratio < MINIMUM_ORACLE_THROUGHPUT_RATIO) {
        throw new IllegalArgumentException(
            "qualified evidence must sustain at least "
                + MINIMUM_ORACLE_THROUGHPUT_RATIO
                + " of reference throughput");
      }
    }
  }

  /**
   * Classifies how the artifact met the production equivalence policy.
   *
   * <p>Only two outcomes today: the runtime either reproduces the model or it does not. A finer
   * grading would have to rest on retrieval evidence this policy does not yet produce, and grading
   * on fidelity alone would be meaningless — 0.9999 and 0.9995 support identical use cases.
   *
   * @return the highest supported embedding use-case tier
   */
  public EmbeddingUseCaseTier useCaseTier() {
    return qualified ? EmbeddingUseCaseTier.SEMANTIC_SEARCH : EmbeddingUseCaseTier.UNQUALIFIED;
  }

  /**
   * Returns true only when the exact artifact passed the production policy.
   *
   * @return whether the exact model artifact is production usable
   */
  public boolean productionUsable() {
    return qualified;
  }

  /**
   * Returns throughput as a share of the reference implementation.
   *
   * @return sustained throughput divided by the oracle's, or infinity when the oracle recorded none
   */
  public double oracleThroughputRatio() {
    return oracleP50EmbedTextsPerSecond == 0.0
        ? Double.POSITIVE_INFINITY
        : p50EmbedTextsPerSecond / oracleP50EmbedTextsPerSecond;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static double requireCosine(double value, String field) {
    if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
      throw new IllegalArgumentException(field + " must be a cosine in [-1, 1], got: " + value);
    }
    return value;
  }

  private static double requireMetric(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(field + " must be finite and >= 0, got: " + value);
    }
    return value;
  }
}
