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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelEmbeddingQualificationTest {

  private static final String ARTIFACT_SHA =
      "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439";
  private static final String PROBE_SHA =
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
      double minimumOracleCosine, double maxNormDeviation, boolean qualified) {
    return new ModelEmbeddingQualification(
        "qwen_qwen3_embedding_0_6b_gguf_q8_0",
        "Qwen3-Embedding-0.6B GGUF Q8_0",
        "pure-java",
        "models-0.3.0",
        "oracle-equivalence-v1",
        PROBE_SHA,
        ARTIFACT_SHA,
        639_150_592L,
        "benchmark-results/embedding/qwen3-embedding-0.6b-q8_0.json",
        REPORT_SHA,
        qualified,
        64,
        1024,
        "last-token",
        true,
        "llama.cpp",
        "6ea215d17",
        minimumOracleCosine,
        0.99966,
        0.006005,
        maxNormDeviation,
        environment());
  }

  @Test
  void carriesTheEquivalenceEvidenceThatCertifiesReproduction() {
    ModelEmbeddingQualification qualification = qualification(0.99946, 2.7e-09, true);

    assertEquals("llama.cpp", qualification.oracleBackend());
    assertEquals("6ea215d17", qualification.oracleVersion());
    assertEquals(0.99946, qualification.minimumOracleCosine());
    assertEquals(1024, qualification.embeddingDimension());
    assertEquals("last-token", qualification.pooling());
    assertTrue(qualification.normalized());
  }

  @Test
  void qualifiesAnArtifactThatReproducesTheReferenceImplementation() {
    ModelEmbeddingQualification qualification = qualification(0.99946, 2.7e-09, true);

    assertEquals(EmbeddingUseCaseTier.SEMANTIC_SEARCH, qualification.useCaseTier());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void gradesUnqualifiedEvidenceAsUnqualified() {
    ModelEmbeddingQualification qualification = qualification(0.99946, 2.7e-09, false);

    assertEquals(EmbeddingUseCaseTier.UNQUALIFIED, qualification.useCaseTier());
    assertFalse(qualification.productionUsable());
  }

  @Test
  void rejectsQualifiedEvidenceBelowTheEquivalenceFloor() {
    // A pooling, RoPE or dequantization defect lands far below the floor rather than just under
    // it, so evidence claiming qualification while failing it is a contradiction.
    assertThrows(IllegalArgumentException.class, () -> qualification(0.97, 2.7e-09, true));
  }

  @Test
  void rejectsQualifiedEvidenceWhoseVectorsAreNotUnitLength() {
    // Cosine is scale-invariant, so agreement stays perfect while normalization is missing. Only
    // the length check can catch it, and qualified evidence must not claim both.
    assertThrows(IllegalArgumentException.class, () -> qualification(0.99946, 11.0, true));
  }

  @Test
  void ignoresVectorLengthWhenTheEvidenceDoesNotClaimNormalization() {
    ModelEmbeddingQualification qualification =
        new ModelEmbeddingQualification(
            "id",
            "name",
            "pure-java",
            "models-0.3.0",
            "oracle-equivalence-v1",
            PROBE_SHA,
            ARTIFACT_SHA,
            1L,
            "report.json",
            REPORT_SHA,
            true,
            64,
            1024,
            "last-token",
            false,
            "llama.cpp",
            "6ea215d17",
            0.9999,
            0.9999,
            0.001,
            11.0,
            environment());

    assertTrue(qualification.productionUsable());
  }

  @Test
  void allowsUnqualifiedEvidenceBelowEitherFloor() {
    assertEquals(EmbeddingUseCaseTier.UNQUALIFIED, qualification(0.5, 11.0, false).useCaseTier());
  }

  @Test
  void rejectsACosineOutsideItsValidRange() {
    assertThrows(IllegalArgumentException.class, () -> qualification(1.5, 2.7e-09, true));
    assertThrows(IllegalArgumentException.class, () -> qualification(-1.5, 2.7e-09, false));
  }

  @Test
  void rejectsAnUnknownPoolingStrategy() {
    // Pooling must travel with the evidence: the wrong one degrades retrieval silently rather
    // than failing, so an unreproducible value cannot be recorded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelEmbeddingQualification(
                "id",
                "name",
                "pure-java",
                "models-0.3.0",
                "oracle-equivalence-v1",
                PROBE_SHA,
                ARTIFACT_SHA,
                1L,
                "report.json",
                REPORT_SHA,
                true,
                64,
                1024,
                "magic",
                true,
                "llama.cpp",
                "6ea215d17",
                0.9999,
                0.9999,
                0.001,
                1.0e-9,
                environment()));
  }

  @Test
  void requiresTheOracleToBeNamedAndPinned() {
    // An unpinned oracle makes the evidence unreproducible: reference kernels change, and the
    // agreement band can move with them.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelEmbeddingQualification(
                "id",
                "name",
                "pure-java",
                "models-0.3.0",
                "oracle-equivalence-v1",
                PROBE_SHA,
                ARTIFACT_SHA,
                1L,
                "report.json",
                REPORT_SHA,
                true,
                64,
                1024,
                "last-token",
                true,
                "llama.cpp",
                "  ",
                0.9999,
                0.9999,
                0.001,
                1.0e-9,
                environment()));
  }

  @Test
  void bindsEvidenceToTheExactArtifactAndProbeSet() {
    ModelEmbeddingQualification qualification = qualification(0.99946, 2.7e-09, true);

    assertEquals(ARTIFACT_SHA, qualification.artifactSha256());
    assertEquals(PROBE_SHA, qualification.probeSetSha256());
    assertEquals(REPORT_SHA, qualification.reportSha256());
  }
}
