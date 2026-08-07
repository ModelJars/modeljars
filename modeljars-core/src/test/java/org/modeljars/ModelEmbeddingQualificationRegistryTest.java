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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelEmbeddingQualificationRegistryTest {

  private static final String PROPERTIES =
      """
      modeljars.embeddingQualifications.schemaVersion=1
      modeljars.embeddingQualifications.generatedAt=2026-08-06T16:40:00Z
      modeljars.embeddingQualifications.policyVersion=oracle-equivalence-v1
      modeljars.embeddingQualifications.modelsRevision=fa26f46ca56facab129b2cc12404a5a8af0d07a5
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.model=Qwen3-Embedding-0.6B GGUF Q8_0
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.backend=pure-java
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.artifactSha256=06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.qualified=true
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.probes=8
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.embeddingDimension=1024
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.pooling=last-token
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.normalized=true
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.oracleBackend=llama.cpp
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.oracleVersion=6ea215d17
      embeddingQualification.qwen_qwen3_embedding_0_6b_gguf_q8_0.minimumOracleCosine=0.9995014497617521
      """;

  private static ModelEmbeddingQualificationRegistry parse(String properties) throws IOException {
    return ModelEmbeddingQualificationRegistry.parse(
        new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void readsTheEquivalenceEvidenceRecordedForAnArtifact() throws IOException {
    ModelEmbeddingQualificationRegistry registry = parse(PROPERTIES);

    assertEquals("oracle-equivalence-v1", registry.policyVersion());
    assertEquals(1, registry.qualified().size());

    ModelEmbeddingQualificationRegistry.Entry entry = registry.qualified().get(0);
    assertEquals("qwen_qwen3_embedding_0_6b_gguf_q8_0", entry.modelId());
    assertEquals("llama.cpp", entry.oracleBackend());
    assertEquals("last-token", entry.pooling());
    assertEquals(1024, entry.embeddingDimension());
    assertEquals(0.9995014497617521, entry.minimumOracleCosine());
    assertTrue(entry.qualified());
  }

  @Test
  void bindsEvidenceToTheExactArtifactDigest() throws IOException {
    ModelEmbeddingQualificationRegistry registry = parse(PROPERTIES);

    assertTrue(
        registry
            .qualificationFor("06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439")
            .isPresent());
    assertTrue(registry.qualificationFor("f".repeat(64)).isEmpty());
  }

  @Test
  void omitsEvidenceThatDidNotQualify() throws IOException {
    ModelEmbeddingQualificationRegistry registry =
        parse(PROPERTIES.replace(".qualified=true", ".qualified=false"));

    assertTrue(registry.qualified().isEmpty());
    assertEquals(1, registry.entries().size());
    assertFalse(registry.entries().get(0).qualified());
  }

  @Test
  void rejectsAnUnsupportedSchemaVersion() {
    assertThrows(
        ModelJarException.class,
        () -> parse(PROPERTIES.replace("schemaVersion=1", "schemaVersion=2")));
  }

  @Test
  void readsAnEmptyManifestWithoutEntries() throws IOException {
    ModelEmbeddingQualificationRegistry registry =
        parse(
            """
            modeljars.embeddingQualifications.schemaVersion=1
            modeljars.embeddingQualifications.generatedAt=2026-08-06T16:40:00Z
            modeljars.embeddingQualifications.policyVersion=oracle-equivalence-v1
            modeljars.embeddingQualifications.modelsRevision=fa26f46ca56facab129b2cc12404a5a8af0d07a5
            """);

    assertTrue(registry.entries().isEmpty());
    assertTrue(registry.qualified().isEmpty());
  }
}
