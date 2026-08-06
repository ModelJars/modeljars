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
 * <p>The counterpart to {@link ModelRagQualification}, certifying a different thing. An embedding
 * model's retrieval quality is a published property of the weights — Qwen3-Embedding-0.6B scores
 * 64.64 on MTEB multilingual retrieval whoever runs it. What a runtime can get wrong is pooling,
 * rotary embeddings, dequantization, and normalization, so this record gates on agreement with a
 * reference implementation over the same bytes, and the published retrieval quality transfers.
 *
 * <p>Two floors, both placed against measurements. Agreement between the pure-Java backend and
 * llama.cpp on Qwen3-Embedding-0.6B Q8_0 measures 0.99950 across varied inputs; mean pooling in
 * place of last-token measures 0.66156. Agreement is not bit-exact: two independent
 * implementations accumulate floating point differently.
 *
 * <p>Cosine is scale-invariant, so an unnormalized runtime agrees with a normalized reference at
 * exactly 1.0. Vector length is therefore gated separately.
 *
 * <p>Every field here is something the equivalence run measures. Throughput and memory are not
 * recorded, because nothing in this policy measures them.
 *
 * <p>Agreement is necessary but not sufficient. Two identically broken implementations would agree
 * perfectly, so the claim rests on the reference being an independent implementation.
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
 * @param maxNormDeviation largest distance from unit length among the produced vectors
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
    double maxNormDeviation,
    ModelQualificationEnvironment environment) {

  /**
   * Agreement below which an artifact is not considered reproduced.
   *
   * <p>Sits below the measured 0.99950 agreement and far above the 0.66156 a wrong pooling
   * produces.
   */
  public static final double MINIMUM_ORACLE_COSINE = 0.999;

  /**
   * How far a vector's length may sit from one before normalization is considered broken.
   *
   * <p>Cosine is scale-invariant, so a runtime that skips L2 normalization agrees with a
   * normalized reference at exactly 1.0, measured against llama.cpp with {@code --embd-normalize
   * -1}. Callers that use a bare dot product as a cosine shortcut depend on unit length.
   */
  public static final double MAX_NORM_DEVIATION = 1.0e-3;

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
    minimumOracleCosine = requireCosine(minimumOracleCosine, "minimumOracleCosine");
    meanOracleCosine = requireCosine(meanOracleCosine, "meanOracleCosine");
    maxComponentDelta = requireMetric(maxComponentDelta, "maxComponentDelta");
    maxNormDeviation = requireMetric(maxNormDeviation, "maxNormDeviation");
    environment = Objects.requireNonNull(environment, "environment");
    if (qualified) {
      if (minimumOracleCosine < MINIMUM_ORACLE_COSINE) {
        throw new IllegalArgumentException(
            "qualified evidence must reproduce the reference implementation to at least "
                + MINIMUM_ORACLE_COSINE);
      }
      if (normalized && maxNormDeviation > MAX_NORM_DEVIATION) {
        throw new IllegalArgumentException(
            "qualified evidence claiming normalized vectors must keep them within "
                + MAX_NORM_DEVIATION
                + " of unit length");
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
