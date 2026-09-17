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
import org.modeljars.ModelComponentQualificationRegistry;
import org.modeljars.ModelEmbeddingQualificationRegistry;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualification;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelRerankingQualificationRegistry;
import org.modeljars.ModelSpeechQualificationRegistry;
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
    var rerankingQualifications = ModelRerankingQualificationRegistry.fromClasspath();
    var speechQualifications = ModelSpeechQualificationRegistry.fromClasspath();
    var componentQualifications = ModelComponentQualificationRegistry.fromClasspath();

    // Every public capability has its own evidence gate. Passing any gate is sufficient to publish,
    // so the aggregate catalog is the union of all qualification registries. A composition
    // component is qualified by the component registry rather than by a capability gate: it is not
    // a standalone model, and it reaches the runtime catalog because a qualified composition
    // references it, so that registry belongs in the union too.
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
    var rerankingQualified =
        rerankingQualifications.qualified().stream()
            .map(ModelRerankingQualificationRegistry.Entry::modelId)
            .collect(Collectors.toSet());
    var speechQualified =
        speechQualifications.qualified().stream()
            .map(ModelSpeechQualificationRegistry.Entry::modelId)
            .collect(Collectors.toSet());
    var componentQualified =
        componentQualifications.entries().stream()
            .filter(ModelComponentQualificationRegistry.Entry::qualified)
            .map(ModelComponentQualificationRegistry.Entry::modelId)
            .collect(Collectors.toSet());
    var allQualified = new java.util.HashSet<>(ragQualified);
    allQualified.addAll(embeddingQualified);
    allQualified.addAll(toolQualified);
    allQualified.addAll(rerankingQualified);
    allQualified.addAll(speechQualified);
    allQualified.addAll(componentQualified);

    var physicalDescriptors =
        descriptors.stream()
            .filter(descriptor -> !"composite".equals(descriptor.format()))
            .toList();
    var compositeDescriptors =
        descriptors.stream().filter(descriptor -> "composite".equals(descriptor.format())).toList();

    assertEquals(allQualified.size(), physicalDescriptors.size());
    assertEquals(
        allQualified,
        physicalDescriptors.stream()
            .map(descriptor -> descriptor.alias())
            .collect(Collectors.toSet()));
    // A composite descriptor reaches the runtime catalog only through a qualified composition, so
    // the invariant is that each one names a qualified composition, not that none exist.
    assertEquals(
        catalogQualifiedCompositionIds(),
        compositeDescriptors.stream()
            .map(descriptor -> descriptor.alias())
            .collect(Collectors.toSet()));
    assertTrue(
        physicalDescriptors.stream()
            .allMatch(
                descriptor ->
                    !qualifications.qualificationsFor(descriptor).isEmpty()
                        || !toolQualifications.qualificationsFor(descriptor).isEmpty()
                        || descriptor
                            .sha256()
                            .flatMap(rerankingQualifications::qualificationFor)
                            .isPresent()
                        || descriptor
                            .sha256()
                            .flatMap(embeddingQualifications::qualificationFor)
                            .isPresent()
                        || descriptor
                            .sha256()
                            .flatMap(speechQualifications::qualificationFor)
                            .isPresent()
                        || componentQualified.contains(descriptor.alias())));
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

  /**
   * The composition ids the catalog declares. A composite descriptor reaches the runtime catalog
   * only because a qualified composition references it, so this is the set a composite descriptor
   * may name.
   */
  private static java.util.Set<String> catalogQualifiedCompositionIds() {
    var path = java.nio.file.Path.of("..", "catalog", "compositions.json");
    if (!java.nio.file.Files.exists(path)) {
      return java.util.Set.of();
    }
    String json;
    try {
      json = java.nio.file.Files.readString(path);
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
    var ids = new java.util.HashSet<String>();
    var marker = "\"id\"";
    var index = json.indexOf(marker);
    while (index >= 0) {
      var colon = json.indexOf(':', index + marker.length());
      var open = colon < 0 ? -1 : json.indexOf('"', colon + 1);
      var close = open < 0 ? -1 : json.indexOf('"', open + 1);
      if (close < 0) {
        break;
      }
      ids.add(json.substring(open + 1, close));
      index = json.indexOf(marker, close + 1);
    }
    return ids;
  }
}
