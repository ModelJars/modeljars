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
 * Auditable retrieval evidence for one exact embedding artifact.
 *
 * <p>The counterpart to {@link ModelRagQualification}, which grades generation. An embedder is
 * judged on whether it retrieves the right documents, so its evidence is recall, reciprocal rank
 * and nDCG over a pinned corpus with relevance judgements — not answer rates over prompts. Sharing
 * one record would leave most fields meaningless whichever kind of model it described.
 *
 * <p>Three facts travel alongside the metrics because they change what the vectors mean, not merely
 * how good they are: the embedding width, the pooling strategy, and whether vectors were
 * normalized. A consumer that stores vectors produced under different settings has silently
 * corrupted its index, and no metric in this record would reveal it.
 *
 * <p>Every rate is measured against {@code corpusSha256} on the artifact identified by {@code
 * artifactSha256}; neither the corpus nor the artifact may vary without new evidence.
 *
 * @param modelId catalog alias of the qualified model
 * @param model display name and variant of the qualified model
 * @param backend normalized execution backend identifier
 * @param backendVersion exact backend version or build
 * @param workload stable retrieval-workload identifier
 * @param corpusSha256 SHA-256 digest of the judged retrieval corpus
 * @param artifactSha256 SHA-256 digest of the qualified model artifact
 * @param artifactSizeBytes byte size of the qualified model artifact
 * @param report repository-relative path to the retrieval evidence report
 * @param reportSha256 SHA-256 digest of the evidence report
 * @param qualified whether the artifact met the production retrieval policy
 * @param queries number of judged queries the evidence covers
 * @param embeddingDimension width of the vectors the model produces
 * @param pooling how per-position states were reduced to one vector
 * @param normalized whether vectors were scaled to unit length
 * @param recallAtTen share of relevant documents returned within the top ten
 * @param meanReciprocalRank mean reciprocal rank of the first relevant document
 * @param normalizedDiscountedCumulativeGainAtTen nDCG@10 over the judged corpus
 * @param p95EmbedMillis 95th-percentile latency to embed one text
 * @param p50EmbedTextsPerSecond median sustained embedding throughput
 * @param peakRssBytes peak resident set size observed during qualification
 * @param environment host and JVM identity the evidence was produced on
 */
public record ModelEmbeddingQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String workload,
    String corpusSha256,
    String artifactSha256,
    long artifactSizeBytes,
    String report,
    String reportSha256,
    boolean qualified,
    int queries,
    int embeddingDimension,
    String pooling,
    boolean normalized,
    double recallAtTen,
    double meanReciprocalRank,
    double normalizedDiscountedCumulativeGainAtTen,
    double p95EmbedMillis,
    double p50EmbedTextsPerSecond,
    long peakRssBytes,
    ModelQualificationEnvironment environment) {

  /**
   * Retrieval floor below which an artifact cannot be called qualified.
   *
   * <p>Proposed, pending sign-off. Recall is the binding metric for first-stage search: a document
   * the embedder never returns cannot be reranked, grounded on, or answered from, so recall failure
   * is unrecoverable downstream in a way ranking error is not.
   */
  public static final double MINIMUM_RECALL_AT_TEN = 0.85;

  /** Ranking floor for qualification. Proposed, pending sign-off. */
  public static final double MINIMUM_MEAN_RECIPROCAL_RANK = 0.60;

  /** Recall required before retrieval is trusted without a reranking stage. Proposed. */
  public static final double PRECISION_RECALL_AT_TEN = 0.92;

  /** Ranking required before retrieval is trusted without a reranking stage. Proposed. */
  public static final double PRECISION_MEAN_RECIPROCAL_RANK = 0.80;

  /** Pooling strategies the runtime can reproduce, so recorded evidence stays replayable. */
  private static final Set<String> SUPPORTED_POOLING = Set.of("last-token", "mean");

  /** Validates the recorded retrieval evidence and the artifact it certifies. */
  public ModelEmbeddingQualification {
    modelId = requireText(modelId, "modelId");
    model = requireText(model, "model");
    backend = requireText(backend, "backend");
    backendVersion = requireText(backendVersion, "backendVersion");
    workload = requireText(workload, "workload");
    corpusSha256 = requireText(corpusSha256, "corpusSha256");
    artifactSha256 = requireText(artifactSha256, "artifactSha256");
    report = requireText(report, "report");
    reportSha256 = requireText(reportSha256, "reportSha256");
    pooling = requireText(pooling, "pooling");
    if (!SUPPORTED_POOLING.contains(pooling)) {
      throw new IllegalArgumentException(
          "pooling must be one of " + SUPPORTED_POOLING + ", got: " + pooling);
    }
    if (artifactSizeBytes < 1) {
      throw new IllegalArgumentException("artifactSizeBytes must be positive");
    }
    if (queries < 1) {
      throw new IllegalArgumentException("queries must be positive");
    }
    if (embeddingDimension < 1) {
      throw new IllegalArgumentException("embeddingDimension must be positive");
    }
    if (peakRssBytes < 1) {
      throw new IllegalArgumentException("peakRssBytes must be positive");
    }
    recallAtTen = requireRate(recallAtTen, "recallAtTen");
    meanReciprocalRank = requireRate(meanReciprocalRank, "meanReciprocalRank");
    normalizedDiscountedCumulativeGainAtTen =
        requireRate(
            normalizedDiscountedCumulativeGainAtTen, "normalizedDiscountedCumulativeGainAtTen");
    p95EmbedMillis = requireMetric(p95EmbedMillis, "p95EmbedMillis");
    p50EmbedTextsPerSecond = requireMetric(p50EmbedTextsPerSecond, "p50EmbedTextsPerSecond");
    environment = Objects.requireNonNull(environment, "environment");
    if (qualified
        && (recallAtTen < MINIMUM_RECALL_AT_TEN
            || meanReciprocalRank < MINIMUM_MEAN_RECIPROCAL_RANK)) {
      throw new IllegalArgumentException(
          "qualified evidence must meet the recall and reciprocal-rank floors");
    }
  }

  /**
   * Classifies how the artifact met the retrieval quality policy.
   *
   * @return the highest supported embedding use-case tier
   */
  public EmbeddingUseCaseTier useCaseTier() {
    if (!qualified) {
      return EmbeddingUseCaseTier.UNQUALIFIED;
    }
    if (recallAtTen >= PRECISION_RECALL_AT_TEN
        && meanReciprocalRank >= PRECISION_MEAN_RECIPROCAL_RANK) {
      return EmbeddingUseCaseTier.PRECISION_RETRIEVAL;
    }
    return EmbeddingUseCaseTier.SEMANTIC_SEARCH;
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

  private static double requireRate(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(field + " must be a rate in [0, 1], got: " + value);
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
