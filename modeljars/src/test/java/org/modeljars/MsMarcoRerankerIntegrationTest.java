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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.modeljars.catalog.Cstr_Ms_Marco_Minilm_L6_V2_Gguf_Q4_K_Imatrix_G7c_F7.MODEL;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MsMarcoRerankerIntegrationTest {
  private static final String ARTIFACT_PROPERTY = "modeljars.fixtures.msMarcoReranker";

  @Test
  void verifiesOpensAndReranksThroughThePublicModelJarsApi() {
    String configured = System.getProperty(ARTIFACT_PROPERTY, "").trim();
    Assumptions.assumeFalse(configured.isEmpty(), () -> "Set -D" + ARTIFACT_PROPERTY);
    Path artifact = Path.of(configured).toAbsolutePath().normalize();
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = registry.resolve(MODEL).orElseThrow();
    ModelJarInstaller installer = new ModelJarInstaller(registry);
    ModelJars modelJars =
        new ModelJars(
            registry,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> installer.verifyCached(candidate, artifact),
            ModelJars::loadBackend,
            Map::of,
            List::of);

    var documents =
        List.of(
            "Berlin has a population of 3,520,031 registered inhabitants in an area of 891.82 square kilometers.",
            "Paris is the capital and most populous city of France.",
            "Berlin is well known for its museums and its metropolitan area of about six million people.",
            "Domestic cats sleep for a large part of the day.",
            "New York City had an estimated population of 8,804,190 in 2020.",
            "The Berlin Wall divided the city from 1961 until 1989.");

    try (var runtime = modelJars.loadRerankingRuntime(MODEL, ModelLoadOptions.defaults())) {
      var ranked = runtime.model().rerank("How many people live in Berlin?", documents);

      assertEquals(descriptor, runtime.descriptor());
      assertTrue(runtime.qualification().qualified());
      assertEquals(
          List.of(0, 2), ranked.stream().limit(2).map(result -> result.originalIndex()).toList());
      assertEquals(8.815807342529297, ranked.get(0).score(), 0.000001);
    }
  }
}
