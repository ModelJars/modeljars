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

import com.integrallis.models.api.catalog.DiscoveredModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The published route to a typed-decision model, as far as this repository can see it.
 *
 * <p>The route has two steps rather than one, deliberately. {@code ModelJars.openRuntime} cannot
 * serve a typed-decision model -- it selects a generative {@code InferenceBackend}, and {@code
 * PureJavaBackend} dispatches {@code bert} and {@code nomic-bert}, not {@code modern-bert} -- so
 * the catalog reports where the weights are and the decoder's own entry point runs them. A two-step
 * route is only defensible if the one-step route says so rather than failing in the loader after a
 * download, which is what the refusal test pins.
 *
 * <p>The second step, {@code ModernBertDecisionModel.open(path).decide(...)}, opens the real
 * weights, so it lives in {@code LayaDecisionIntegrationIT} and runs on its own task with the
 * Vector API module added -- the repository convention for a real-weight gate.
 */
class LayaDecisionCatalogRouteTest {

  private static final String MODEL_ID = "laya_q8_0";

  @Test
  void reportsWhereTheWeightsAreWhenTheArtifactIsInstalled() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    Optional<Path> located = catalog.locate(MODEL_ID);
    Assumptions.assumeTrue(
        located.isPresent(), () -> MODEL_ID + " is not installed on this machine");
    Path artifact = located.orElseThrow();
    assertTrue(Files.isRegularFile(artifact), () -> "locate reported a missing file: " + artifact);
    // The digest the catalog pins is what a caller is entitled to receive, so the size is the
    // cheapest check that locate did not hand back some other file of the same name.
    assertEquals(
        449_397_600L, artifact.toFile().length(), () -> "unexpected artifact at " + artifact);
  }

  @Test
  void refusesTheGenerativeRouteAndNamesTheRouteThatWorks() {
    // The catalog advertises pure-java for this model because the pure-Java decoder executes it.
    // That must not read as "openRuntime works", and the only way to know the refusal is real is
    // to ask the published descriptor for it rather than a hand-built one.
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    Optional<ModelJarDescriptor> descriptor =
        registry.descriptors().stream().filter(d -> MODEL_ID.equals(d.alias())).findFirst();
    Assumptions.assumeTrue(descriptor.isPresent(), () -> MODEL_ID + " is not on the classpath");

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () -> ModelJars.requireGenerativeModel(descriptor.orElseThrow(), "openRuntime"));
    assertTrue(
        failure.getMessage().contains("typed-decision model, not a text generator"),
        failure.getMessage());
    assertTrue(failure.getMessage().contains("ModelJarsCatalog.locate"), failure.getMessage());
    assertTrue(failure.getMessage().contains("ModernBertDecisionModel.open"), failure.getMessage());
    // install() is what fetches; locate() only reports a cached copy. Naming only locate sends a
    // reader with a cold cache to a call that returns empty -- which is how the released-catalog
    // probe failed on its first cold run.
    assertTrue(failure.getMessage().contains("ModelJarInstaller.install"), failure.getMessage());
  }

  @Test
  void advertisesTheDecisionTaskAndItsMeasuredAccuracyToTheRouter() {
    // What a router actually sees. The tag is how a caller naming the `decision` task reaches it;
    // the capability is what a capability requirement filters on; the quality is the model's own
    // measured accuracy. Calibration is deliberately absent -- whether the published probability
    // can be thresholded is a usage constraint, not a measure of how good the model is, and
    // publishing (1 - ECE) as a score would invent a metric nobody measured.
    Optional<DiscoveredModel> discovered =
        new ModelJarsCatalog()
            .discover().stream().filter(model -> MODEL_ID.equals(model.id())).findFirst();
    Assumptions.assumeTrue(
        discovered.isPresent(), () -> MODEL_ID + " is not installed on this machine");
    DiscoveredModel model = discovered.orElseThrow();

    assertEquals(List.of("decision"), List.copyOf(model.tags()));
    // Reported exactly, not as the task tags again. A typed-decision model is the first whose tag
    // is named differently from its capability, so this is the assertion that keeps the two apart.
    assertTrue(model.capabilities().contains("typed-decision"), model.capabilities().toString());
    assertFalse(model.capabilities().contains("text-generation"), model.capabilities().toString());
    assertEquals(0.783333, model.quality().get("decision"), 1.0e-6);
    assertFalse(model.quality().containsKey("grounding"), model.quality().toString());
    assertTrue(
        model.features().contains("decision-confidence-uncalibrated"), model.features().toString());
  }
}
