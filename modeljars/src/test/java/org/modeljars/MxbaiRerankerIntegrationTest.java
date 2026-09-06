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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MxbaiRerankerIntegrationTest {
  private static final String DIRECTORY_PROPERTY = "modeljars.fixtures.mxbaiRerankerDirectory";
  private static final String LIVE_PROPERTY = "modeljars.integration.mxbai.live";
  private static final ModelJar MODEL =
      ModelJar.of("org.modeljars.huggingface:mixedbread-ai.mxbai-rerank-xsmall-v1.f16:1.0.0-f16.1");

  @Test
  void verifiesOpensAndReranksSafetensorsThroughThePublicModelJarsApi(@TempDir Path temporary) {
    String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
    boolean live = Boolean.getBoolean(LIVE_PROPERTY);
    Assumptions.assumeTrue(
        live || !configured.isEmpty(),
        () -> "Set -D" + DIRECTORY_PROPERTY + " or -D" + LIVE_PROPERTY);
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = registry.resolve(MODEL).orElseThrow();
    assertEquals("safetensors", descriptor.format());
    assertEquals("deberta-v2", descriptor.architecture());
    assertEquals(
        List.of("config.json", "model.safetensors", "tokenizer.json"),
        descriptor.files().stream().map(ModelArtifactFile::path).toList());

    if (live) {
      ModelLoadOptions options =
          ModelLoadOptions.builder().cacheDirectory(temporary.resolve("cache")).build();
      try (var runtime = ModelJars.openRerankingRuntime(MODEL, options)) {
        verifyReranking(runtime);
      }
      return;
    }

    Path directory = Path.of(configured).toAbsolutePath().normalize();
    ModelJarInstaller installer = new ModelJarInstaller(registry);
    ModelJars modelJars =
        new ModelJars(
            registry,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) ->
                installer.verifyCached(candidate, directory.resolve("model.safetensors")),
            ModelJars::loadBackend,
            Map::of,
            List::of);

    try (var runtime = modelJars.loadRerankingRuntime(MODEL, ModelLoadOptions.defaults())) {
      verifyReranking(runtime);
    }
  }

  private static void verifyReranking(ModelJarRerankingRuntime runtime) {
    String query = "What is the population of Berlin?";
    List<String> documents =
        List.of(
            "Berlin has a population of about 3.7 million people.",
            "The Eiffel Tower is located in Paris.",
            "Berlin is well known for its museums and its metropolitan area of about six million people.",
            "Domestic cats sleep for a large part of the day.",
            "New York City had an estimated population of 8,804,190 in 2020.",
            "The Berlin Wall divided the city from 1961 until 1989.");

    assertTrue(runtime.qualification().qualified());
    assertEquals(
        List.of(0, 2, 5, 1, 4, 3),
        runtime.model().rerank(query, documents).stream()
            .map(result -> result.originalIndex())
            .toList());
    assertEquals(2.610485553741455, runtime.model().score(query, documents.getFirst()), 0.001);
  }
}
