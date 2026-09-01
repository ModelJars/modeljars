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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelRerankingQualificationRegistryTest {
  private static final String SHA =
      "0752a92bc33289f3fe230d1a33cce471f1a7fe1c8d5a491ca7fff6b068b0fc83";

  @Test
  void parsesAndSelectsQualificationForTheExactArtifact() throws Exception {
    String properties =
        """
        modeljars.rerankingQualifications.schemaVersion=1
        modeljars.rerankingQualifications.generatedAt=2026-09-01T15:31:50Z
        modeljars.rerankingQualifications.policyVersion=reranking-oracle-and-latency-v1
        modeljars.rerankingQualifications.modelsRevision=31183ecb9bd78460f88415b3e2f7625f6bb5b096
        rerankingQualification.minilm.model=MS MARCO MiniLM
        rerankingQualification.minilm.backend=pure-java
        rerankingQualification.minilm.backendVersion=models-0.3.23
        rerankingQualification.minilm.workload=reranking-oracle-and-latency-v1
        rerankingQualification.minilm.artifactSha256=%s
        rerankingQualification.minilm.artifactSizeBytes=19986112
        rerankingQualification.minilm.report=benchmark-results/reranking/performance.json
        rerankingQualification.minilm.reportSha256=114f393304830f279b89dfc7f04e3e6ea7055692b517f58956a39bc8f13c0a4f
        rerankingQualification.minilm.qualified=true
        rerankingQualification.minilm.pairs=6
        rerankingQualification.minilm.maximumOnnxLogitDelta=0.101034
        rerankingQualification.minilm.maximumSameArtifactOracleLogitDelta=0.036392
        rerankingQualification.minilm.topKOrderExact=true
        rerankingQualification.minilm.medianColdLoadMillis=221.346
        rerankingQualification.minilm.maximumPairP95Millis=174.357
        rerankingQualification.minilm.maximumBatchP95Millis=930.176
        rerankingQualification.minilm.medianBatchDocumentsPerSecond=7.875
        """
            .formatted(SHA);

    var registry =
        ModelRerankingQualificationRegistry.parse(
            new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1)));

    assertEquals("31183ecb9bd78460f88415b3e2f7625f6bb5b096", registry.modelsRevision());
    var qualification = registry.qualificationFor(SHA).orElseThrow();
    assertEquals("minilm", qualification.modelId());
    assertEquals("models-0.3.23", qualification.backendVersion());
    assertEquals("reranking-oracle-and-latency-v1", qualification.workload());
    assertEquals(19_986_112L, qualification.artifactSizeBytes());
    assertEquals(
        "114f393304830f279b89dfc7f04e3e6ea7055692b517f58956a39bc8f13c0a4f",
        qualification.reportSha256());
    assertEquals(221.346, qualification.medianColdLoadMillis());
    assertEquals(930.176, qualification.maximumBatchP95Millis());
    assertEquals(7.875, qualification.medianBatchDocumentsPerSecond());
    assertTrue(registry.qualificationFor("f".repeat(64)).isEmpty());
  }

  @Test
  void rejectsQualifiedEvidenceThatExceedsTheColdLoadGate() {
    String properties =
        """
        modeljars.rerankingQualifications.schemaVersion=1
        rerankingQualification.minilm.model=MS MARCO MiniLM
        rerankingQualification.minilm.backend=pure-java
        rerankingQualification.minilm.backendVersion=models-0.3.23
        rerankingQualification.minilm.workload=reranking-oracle-and-latency-v1
        rerankingQualification.minilm.artifactSha256=%s
        rerankingQualification.minilm.artifactSizeBytes=19986112
        rerankingQualification.minilm.report=benchmark-results/reranking/performance.json
        rerankingQualification.minilm.reportSha256=114f393304830f279b89dfc7f04e3e6ea7055692b517f58956a39bc8f13c0a4f
        rerankingQualification.minilm.qualified=true
        rerankingQualification.minilm.pairs=6
        rerankingQualification.minilm.maximumOnnxLogitDelta=0.101034
        rerankingQualification.minilm.maximumSameArtifactOracleLogitDelta=0.036392
        rerankingQualification.minilm.topKOrderExact=true
        rerankingQualification.minilm.medianColdLoadMillis=1000.001
        rerankingQualification.minilm.maximumPairP95Millis=174.357
        rerankingQualification.minilm.maximumBatchP95Millis=930.176
        rerankingQualification.minilm.medianBatchDocumentsPerSecond=7.875
        """
            .formatted(SHA);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelRerankingQualificationRegistry.parse(
                new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1))));
  }
}
