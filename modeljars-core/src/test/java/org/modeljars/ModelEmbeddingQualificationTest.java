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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelEmbeddingQualificationTest {

  private static final String ARTIFACT_SHA =
      "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439";
  private static final String CORPUS_SHA =
      "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45";
  private static final String REPORT_SHA =
      "85bfa1d4d855997ccb99ef60c53c4d22a4ba02f1f9cc21447087c82dae334153";

  private static ModelQualificationEnvironment environment() {
    return new ModelQualificationEnvironment(
        "vectors-bench",
        "Linux",
        "6.8.0-124-generic",
        "amd64",
        "AMD EPYC-Milan Processor",
        8,
        32_857_444_352L,
        1_073_741_824L,
        "25.0.3",
        "GraalVM Community",
        "OpenJDK 64-Bit Server VM");
  }

  private static ModelEmbeddingQualification qualification(
      double recallAtTen, double meanReciprocalRank, boolean qualified) {
    return new ModelEmbeddingQualification(
        "qwen_qwen3_embedding_0_6b_gguf_q8_0",
        "Qwen3-Embedding-0.6B GGUF Q8_0",
        "pure-java",
        "models-0.3.0",
        "beir-scifact",
        CORPUS_SHA,
        ARTIFACT_SHA,
        639_150_592L,
        "benchmark-results/embedding/qwen3-embedding-0.6b-q8_0.json",
        REPORT_SHA,
        qualified,
        300,
        1024,
        "last-token",
        true,
        recallAtTen,
        meanReciprocalRank,
        0.71,
        48.2,
        21.5,
        1_391_558_656L,
        environment());
  }

  @Test
  void carriesTheRetrievalEvidenceThatDistinguishesAnEmbedder() {
    ModelEmbeddingQualification qualification = qualification(0.95, 0.88, true);

    assertEquals(1024, qualification.embeddingDimension());
    assertEquals("last-token", qualification.pooling());
    assertTrue(qualification.normalized());
    assertEquals(0.95, qualification.recallAtTen());
    assertEquals(0.88, qualification.meanReciprocalRank());
  }

  @Test
  void gradesAStrongRetrieverAsPrecisionGrade() {
    assertEquals(
        EmbeddingUseCaseTier.PRECISION_RETRIEVAL, qualification(0.95, 0.88, true).useCaseTier());
  }

  @Test
  void gradesAnAdequateRetrieverAsSemanticSearch() {
    // Clears the retrieval floor but not the precision floor: usable for first-stage search,
    // where a reranker or a grounding policy still stands between it and an answer.
    assertEquals(
        EmbeddingUseCaseTier.SEMANTIC_SEARCH, qualification(0.90, 0.72, true).useCaseTier());
  }

  @Test
  void gradesUnqualifiedEvidenceAsUnqualified() {
    ModelEmbeddingQualification qualification = qualification(0.95, 0.88, false);

    assertEquals(EmbeddingUseCaseTier.UNQUALIFIED, qualification.useCaseTier());
    assertFalse(qualification.productionUsable());
  }

  @Test
  void rejectsQualifiedEvidenceBelowTheRetrievalFloor() {
    // The floor is what "qualified" means; evidence that claims it while failing it is a
    // contradiction the record must not be able to represent.
    assertThrows(IllegalArgumentException.class, () -> qualification(0.50, 0.40, true));
  }

  @Test
  void allowsUnqualifiedEvidenceBelowTheFloor() {
    ModelEmbeddingQualification rejected = qualification(0.50, 0.40, false);

    assertEquals(EmbeddingUseCaseTier.UNQUALIFIED, rejected.useCaseTier());
  }

  @Test
  void rejectsRatesOutsideTheUnitInterval() {
    assertThrows(IllegalArgumentException.class, () -> qualification(1.5, 0.88, true));
    assertThrows(IllegalArgumentException.class, () -> qualification(0.95, -0.1, true));
  }

  @Test
  void rejectsAnImpossibleEmbeddingWidth() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelEmbeddingQualification(
                "id",
                "name",
                "pure-java",
                "models-0.3.0",
                "beir-scifact",
                CORPUS_SHA,
                ARTIFACT_SHA,
                1L,
                "report.json",
                REPORT_SHA,
                true,
                300,
                0,
                "last-token",
                true,
                0.95,
                0.88,
                0.71,
                48.2,
                21.5,
                1L,
                environment()));
  }

  @Test
  void rejectsAnUnknownPoolingStrategy() {
    // Pooling must travel with the model: using the wrong one degrades retrieval silently
    // rather than failing, so an unrecognised value cannot be recorded as evidence.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelEmbeddingQualification(
                "id",
                "name",
                "pure-java",
                "models-0.3.0",
                "beir-scifact",
                CORPUS_SHA,
                ARTIFACT_SHA,
                1L,
                "report.json",
                REPORT_SHA,
                true,
                300,
                1024,
                "magic",
                true,
                0.95,
                0.88,
                0.71,
                48.2,
                21.5,
                1L,
                environment()));
  }

  @Test
  void matchesOnlyTheExactArtifactItCertified() {
    ModelEmbeddingQualification qualification = qualification(0.95, 0.88, true);

    assertEquals(ARTIFACT_SHA, qualification.artifactSha256());
    assertEquals(CORPUS_SHA, qualification.corpusSha256());
    assertEquals(REPORT_SHA, qualification.reportSha256());
  }
}
