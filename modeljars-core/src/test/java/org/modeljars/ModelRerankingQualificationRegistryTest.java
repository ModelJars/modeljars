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
        rerankingQualification.minilm.medianColdLoadMillis=5000.001
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

  @Test
  void acceptsAQualifiedNonOnnxReferenceAndTheLargerSafetensorsLoadEnvelope() throws Exception {
    String properties =
        """
        modeljars.rerankingQualifications.schemaVersion=1
        modeljars.rerankingQualifications.generatedAt=2026-09-06T19:00:00Z
        modeljars.rerankingQualifications.policyVersion=reranking-reference-and-latency-v2
        modeljars.rerankingQualifications.modelsRevision=5555555555555555555555555555555555555555
        rerankingQualification.mxbai.model=mxbai-rerank-xsmall-v1
        rerankingQualification.mxbai.backend=pure-java
        rerankingQualification.mxbai.backendVersion=models-0.3.31
        rerankingQualification.mxbai.workload=reranking-reference-and-latency-v2
        rerankingQualification.mxbai.artifactSha256=a29bc212faf59c136ad0fd5712ecd2346e7b32c44a25b690625bc9ecebb14b8f
        rerankingQualification.mxbai.artifactSizeBytes=141685186
        rerankingQualification.mxbai.artifactBundleSizeBytes=150335293
        rerankingQualification.mxbai.artifactBundleSha256=f57afc0f62b3571e6b284931b84b2929df284c481644cfcc75a88e3c0a7262c9
        rerankingQualification.mxbai.artifactFile.count=3
        rerankingQualification.mxbai.artifactFile.000.path=config.json
        rerankingQualification.mxbai.artifactFile.000.role=model-configuration
        rerankingQualification.mxbai.artifactFile.000.sha256=470a53befc79da411cc04e466770d9f219f3c14adb276bfa0a58df28774ceade
        rerankingQualification.mxbai.artifactFile.000.sizeBytes=968
        rerankingQualification.mxbai.artifactFile.001.path=model.safetensors
        rerankingQualification.mxbai.artifactFile.001.role=model-weights
        rerankingQualification.mxbai.artifactFile.001.sha256=a29bc212faf59c136ad0fd5712ecd2346e7b32c44a25b690625bc9ecebb14b8f
        rerankingQualification.mxbai.artifactFile.001.sizeBytes=141685186
        rerankingQualification.mxbai.artifactFile.002.path=tokenizer.json
        rerankingQualification.mxbai.artifactFile.002.role=tokenizer
        rerankingQualification.mxbai.artifactFile.002.sha256=305674b4d785287feecfb5f73f24aa75e9b57c87c579cfe24fbd207987d4b4c4
        rerankingQualification.mxbai.artifactFile.002.sizeBytes=8649139
        rerankingQualification.mxbai.report=benchmark-results/reranking/mxbai/performance.json
        rerankingQualification.mxbai.reportSha256=2222222222222222222222222222222222222222222222222222222222222222
        rerankingQualification.mxbai.qualified=true
        rerankingQualification.mxbai.pairs=6
        rerankingQualification.mxbai.maximumReferenceLogitDelta=0.0000030994415283203125
        rerankingQualification.mxbai.topKOrderExact=true
        rerankingQualification.mxbai.medianColdLoadMillis=2556.399
        rerankingQualification.mxbai.maximumPairP95Millis=156.300
        rerankingQualification.mxbai.maximumBatchP95Millis=560.781
        rerankingQualification.mxbai.medianBatchDocumentsPerSecond=17.114
        """;

    var registry =
        ModelRerankingQualificationRegistry.parse(
            new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1)));

    var qualification = registry.qualified().getFirst();
    assertEquals(0.0000030994415283203125, qualification.maximumReferenceLogitDelta());
    assertTrue(qualification.sameArtifactReferenceLogitDelta().isEmpty());
    var bundle = registry.artifactBundleFor("mxbai").orElseThrow();
    assertEquals(150_335_293L, bundle.sizeBytes());
    assertEquals(3, bundle.files().size());
    assertEquals("tokenizer.json", bundle.files().get(2).path());
    assertEquals(2556.399, qualification.medianColdLoadMillis());
  }
}
