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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.catalog.DiscoveredModel;
import com.integrallis.models.api.catalog.ModelCatalogProvider;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Tests for reporting installed ModelJars to the router.
 *
 * <p>The catalogue and the machine both vary, so these assert the properties that must hold
 * whatever is installed rather than a fixed list of models. What matters is that nothing is
 * invented: an unmeasured figure must be reported as absent, not as a plausible number.
 */
class ModelJarsCatalogTest {

  @Test
  void isRegisteredForServiceLoaderDiscovery() {
    // Without the services file the catalog is dead code, and nothing else would notice.
    assertTrue(
        ServiceLoader.load(ModelCatalogProvider.class).stream()
            .anyMatch(provider -> provider.type().equals(ModelJarsCatalog.class)),
        "ModelJarsCatalog is not registered in META-INF/services");
  }

  @Test
  void contributesWithoutBeingAskedFor() {
    // Unlike on-device platform intelligence, these models are installed deliberately.
    assertFalse(new ModelJarsCatalog().requiresOptIn());
  }

  @Test
  void namesItselfForDiagnostics() {
    assertEquals("modeljars", new ModelJarsCatalog().name());
  }

  @Test
  void reportsOnlyRunnableGenerationModels() {
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      assertTrue(model.local(), model.id());
      assertEquals(0.0, model.costPerMillionInputTokens(), model.id());
      assertEquals(0.0, model.costPerMillionOutputTokens(), model.id());
      // A context window is what the router filters on before it scores anything, so a model
      // without one cannot be routed to at all.
      assertTrue(model.contextWindow() > 0, model.id());
    }
  }

  @Test
  void reportsSizeSoUnmeasuredModelsCanBeEstimated() {
    // Local generation is memory-bandwidth bound, so size is what the router's estimator
    // calibrates on. Omitting it forces a fallback to pessimistic constants.
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      assertTrue(model.sizeBytes() > 0, model.id());
    }
  }

  @Test
  void leavesThroughputAbsentRatherThanApproximateWhenNoProfileMatches() {
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      if (model.performance() == null) {
        continue;
      }
      // A reported profile has to be a real measurement, not a placeholder.
      assertTrue(model.performance().timeToFirstTokenMillis() > 0, model.id());
      assertTrue(model.performance().tokensPerSecond() > 0, model.id());
    }
  }

  @Test
  void ragQualificationsDoNotInventUnrelatedTaskQuality() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
    ModelToolQualificationRegistry tools = ModelToolQualificationRegistry.fromClasspath();
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    java.util.Set<String> unrelated =
        java.util.Set.of("chat", "code", "math", "reasoning", "sql", "translation");
    int measuredRagModels = 0;
    int measuredToolModels = 0;
    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      if (rag.qualificationsFor(descriptor).stream()
          .anyMatch(q -> q.qualified() && q.rawCorrectAnswerRate() > 0)) {
        measuredRagModels++;
        assertTrue(catalog.qualityFor(descriptor, unrelated).isEmpty(), descriptor.alias());
      }
      var qualifiedTools =
          tools.qualificationsFor(descriptor).stream()
              .filter(ModelToolQualification::qualified)
              .toList();
      var quality = catalog.qualityFor(descriptor, java.util.Set.of("tool-use"));
      if (qualifiedTools.isEmpty()) {
        assertTrue(quality.isEmpty(), descriptor.alias());
      } else {
        measuredToolModels++;
        double expected =
            qualifiedTools.stream()
                .mapToDouble(
                    q ->
                        Math.min(
                            Math.min(q.toolSelectionExactRate(), q.schemaValidityRate()),
                            Math.min(q.expectedArgumentAccuracy(), q.refusalAccuracy())))
                .max()
                .orElseThrow();
        assertEquals(java.util.Map.of("tool-use", expected), quality, descriptor.alias());
      }
    }
    assertTrue(measuredRagModels > 0, "test must exercise actual RAG qualifications");
    assertTrue(measuredToolModels > 0, "test must exercise actual tool qualifications");
  }

  @Test
  void onlyTagsTasksTheCatalogueVocabularyCovers() {
    List<String> routerTasks =
        List.of(
            "chat",
            "code",
            "creative",
            "extraction",
            "math",
            "reasoning",
            "sql",
            "summarization",
            "tool-use",
            "translation");
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      // An unmapped capability must not leak through as a tag the classifier never emits, because
      // the router filters on an exact tag match and the model would silently become ineligible.
      assertTrue(routerTasks.containsAll(model.tags()), model.id() + " tags " + model.tags());
    }
  }
}
