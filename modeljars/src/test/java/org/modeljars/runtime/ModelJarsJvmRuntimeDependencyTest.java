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
package org.modeljars.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelEmbeddingQualificationRegistry;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualification;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelToolQualification;
import org.modeljars.ModelToolQualificationRegistry;
import org.modeljars.ModelVersion;
import org.modeljars.catalog.Qwen3_0_6b_Q4_0;

class ModelJarsJvmRuntimeDependencyTest {
  @Test
  void exposesTheModelJarsApiThroughTheJvmRuntimeDependency() {
    assertEquals("1.2.3", ModelVersion.parse("1.2.3").toString());
  }

  @Test
  void aggregateTestCatalogContainsOnlyQualifiedModels() {
    var descriptors = ModelJarRegistry.fromClasspath().descriptors();
    var qualifications = ModelRagQualificationRegistry.fromClasspath();
    var embeddingQualifications = ModelEmbeddingQualificationRegistry.fromClasspath();
    var toolQualifications = ModelToolQualificationRegistry.fromClasspath();

    // A generator qualifies through RAG or tool conformance, and an embedder through reference
    // equivalence. Any one is sufficient to publish, so the catalog is the union of all three.
    var ragQualified =
        qualifications.qualified().stream()
            .map(ModelRagQualification::modelId)
            .collect(Collectors.toSet());
    var embeddingQualified =
        embeddingQualifications.qualified().stream()
            .map(ModelEmbeddingQualificationRegistry.Entry::modelId)
            .collect(Collectors.toSet());
    var toolQualified =
        toolQualifications.qualified().stream()
            .map(ModelToolQualification::modelId)
            .collect(Collectors.toSet());
    var allQualified = new java.util.HashSet<>(ragQualified);
    allQualified.addAll(embeddingQualified);
    allQualified.addAll(toolQualified);

    assertEquals(allQualified.size(), descriptors.size());
    assertEquals(
        allQualified,
        descriptors.stream().map(descriptor -> descriptor.alias()).collect(Collectors.toSet()));
    assertTrue(
        descriptors.stream()
            .allMatch(
                descriptor ->
                    !qualifications.qualificationsFor(descriptor).isEmpty()
                        || !toolQualifications.qualificationsFor(descriptor).isEmpty()
                        || descriptor
                            .sha256()
                            .flatMap(embeddingQualifications::qualificationFor)
                            .isPresent()));
    assertNull(
        getClass()
            .getClassLoader()
            .getResource(
                "META-INF/modeljars/models/wordtour_glove_6b_300d_optimal/wordtour_opt.txt"));
  }

  @Test
  void runtimeCarriesCurrentRejectionsThatOverrideStaleMarkerEvidence() {
    ModelRagQualification rejected =
        ModelRagQualificationRegistry.fromClasspath().qualifications().stream()
            .filter(
                qualification ->
                    qualification.modelId().equals("h2oai_h2o_danube3_500m_chat_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertFalse(rejected.productionUsable());
    assertEquals("FAILED_MODEL_CONTRIBUTION_GATE", rejected.verdict());
  }

  @Test
  void exposesBothModelsBackendsThroughTheJvmRuntimeDependency() {
    assertEquals("PureJavaBackend", PureJavaBackend.class.getSimpleName());
    assertEquals("RustFfmBackend", RustFfmBackend.class.getSimpleName());
  }

  @Test
  void exposesGeneratedReferencesForQualifiedModels() {
    var descriptor = ModelJarRegistry.fromClasspath().resolve(Qwen3_0_6b_Q4_0.MODEL).orElseThrow();

    assertEquals("qwen3_0_6b_q4_0", descriptor.alias());
  }
}
