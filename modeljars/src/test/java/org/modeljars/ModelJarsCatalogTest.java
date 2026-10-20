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
import java.util.HashSet;
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
  void carriesCatalogFeaturesSoApplicationsCanRouteOnThem() {
    // The catalog declares properties that are not capabilities -- long-context,
    // noncommercial-research-license, medical-use-warning. Dropping them here is invisible: every
    // other assertion still passes, and the only symptom is that an application cannot express
    // "this needs a long context" without inventing a capability name and declaring it by hand.
    var catalog = ModelJarRegistry.fromClasspath();
    var declared = new HashSet<String>();
    for (var descriptor : catalog.descriptors()) {
      declared.addAll(descriptor.features());
    }
    var reported = new HashSet<String>();
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      reported.addAll(model.features());
    }
    // Discovery reports a subset of the installed catalog, so this asserts the direction that can
    // actually fail: nothing reported may be invented, and if anything is installed and runnable
    // its features must survive the trip.
    assertTrue(declared.containsAll(reported), "discovery invented features: " + reported);
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      assertEquals(
          catalog.descriptors().stream()
              .filter(d -> d.alias().equals(model.id()))
              .findFirst()
              .orElseThrow()
              .features(),
          model.features(),
          model.id());
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

  /**
   * GROUNDING QUALITY IS THE MODEL'S OWN CORRECTNESS, AND ONLY WHERE THE POLICY SAYS SO.
   *
   * <p>{@link RagUseCaseTier#GUARDED_RAG} is defined as production-usable <em>with</em> the
   * grounding and extractive fallback policy. That is a measurement of a pipeline, and the router
   * selects a model, not a pipeline: reporting a guarded artifact's pipeline correctness as its
   * {@code grounding} quality tells an application the model can do something the evidence does not
   * say it can do unaided.
   *
   * <p>MEASURED 2026-10-20 that this is not hypothetical. The Llama-3.2-1B artifact below is
   * GUARDED_RAG with a pipeline rate of 1.0 and a model-only rate of 0.111. A demo fleet wired it
   * in without the guard, declared its grounding quality as 0.70 by hand, and measured 23 points
   * behind a frontier model on HotpotQA while producing none of 50 required abstentions.
   *
   * <p>Every qualified artifact in this catalog is currently GUARDED_RAG, so the correct number of
   * grounding scores is zero. That is the point rather than a gap: absence is what the router is
   * now built to handle, and a quality floor refuses it.
   */
  @Test
  void groundingQualityIsOnlyReportedForArtifactsQualifiedToAnswerUnguarded() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    int guarded = 0;
    int generative = 0;
    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      var qualifications = rag.qualificationsFor(descriptor);
      if (qualifications.isEmpty()) {
        continue;
      }
      var quality = catalog.qualityFor(descriptor, java.util.Set.of("grounding"));
      boolean anyGenerative =
          qualifications.stream().anyMatch(q -> q.useCaseTier() == RagUseCaseTier.GENERATIVE_RAG);
      if (anyGenerative) {
        generative++;
        double expected =
            qualifications.stream()
                .filter(q -> q.useCaseTier() == RagUseCaseTier.GENERATIVE_RAG)
                .mapToDouble(ModelRagQualification::rawCorrectAnswerRate)
                .max()
                .orElseThrow();
        assertEquals(expected, quality.get("grounding"), 1.0e-9, descriptor.alias());
      } else {
        guarded++;
        assertFalse(
            quality.containsKey("grounding"),
            descriptor.alias() + " is not qualified to answer unguarded");
      }
    }
    assertTrue(guarded + generative > 0, "the catalog must carry RAG qualifications to test");
    // Stated rather than assumed: if an artifact ever reaches GENERATIVE_RAG, this test starts
    // checking its number instead, and the count here is what tells a reader which world they are
    // in when they read the assertion above.
    assertEquals(
        0,
        generative,
        "no catalogued artifact is qualified to answer RAG unguarded; "
            + guarded
            + " are qualified only with the grounding policy");
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
