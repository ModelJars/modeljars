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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A typed-decision model is discoverable, and is not mistaken for a text generator.
 *
 * <p>Before this, {@code discover()} admitted only {@code text-generation}, so a decision model was
 * invisible to the router no matter how well it had qualified — the real blocker on publishing one,
 * underneath any question about gates.
 *
 * <p>The two halves of that have to hold together. Admitting it is useless if the router then hands
 * it a chat prompt: it answers a typed question over a state in one non-autoregressive pass and has
 * no continuation to produce, so that would be a category error rather than a quality trade-off.
 * The capability makes it visible and the {@code decision} tag is what keeps it out of chat
 * routing.
 */
class ModelJarsCatalogDecisionTest {

  @Test
  void mapsTheTypedDecisionCapabilityToItsOwnTag() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    Set<String> tags = catalog.tagsFor(Set.of("typed-decision"));

    assertTrue(tags.contains("decision"), "typed-decision must carry the decision tag: " + tags);
    assertFalse(tags.contains("chat"), "a decision model must not be tagged for chat: " + tags);
  }

  @Test
  void aDecisionModelIsNotTaggedForChatEvenAlongsideOtherCapabilities() {
    // The guard that matters. A marker listing both would otherwise leak into chat routing, and the
    // router would select it for a prompt it cannot answer.
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    Set<String> tags = catalog.tagsFor(Set.of("typed-decision", "reasoning"));

    assertTrue(tags.contains("decision"), tags.toString());
    assertTrue(tags.contains("reasoning"), tags.toString());
    assertFalse(tags.contains("chat"), "chat must come only from the chat capability: " + tags);
  }

  @Test
  void everyDiscoveredModelCarriesATagThatMatchesWhatItCanActuallyDo() {
    // Property over whatever is installed: a model tagged `decision` must declare the capability,
    // and one tagged `chat` must declare that. The mapping is the only thing standing between a
    // catalogued model and a router that trusts the tag.
    for (DiscoveredModel model : new ModelJarsCatalog().discover()) {
      if (model.tags().contains("decision")) {
        assertTrue(
            model.capabilities().contains("typed-decision"),
            model.id() + " is tagged decision without declaring typed-decision");
      }
      if (model.tags().contains("chat")) {
        assertTrue(
            model.capabilities().contains("chat"),
            model.id() + " is tagged chat without declaring chat");
      }
    }
  }

  @Test
  void aDecisionQualityIsExportedOnlyWhenEvidenceBindsToTheExactArtifact() {
    // The quality a router compares decision models on is the model's own accuracy. It must come
    // from evidence bound to these exact bytes -- no evidence, no quality, rather than a default.
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    for (ModelJarDescriptor descriptor : ModelJarRegistry.fromClasspath().descriptors()) {
      Map<String, Double> quality = catalog.qualityFor(descriptor, Set.of("decision"));
      boolean hasEvidence =
          ModelDecisionQualificationRegistry.fromClasspath()
              .qualificationFor(descriptor)
              .isPresent();
      assertEquals(
          hasEvidence,
          quality.containsKey("decision"),
          descriptor.alias()
              + ": a decision quality must appear exactly when digest-bound evidence does");
    }
  }

  @Test
  void calibrationIsNeverPublishedAsAQualityScore() {
    // Calibration is a usage constraint, not a measure of how good the model is, and it does not
    // transfer -- a temperature is fitted to a distribution. Publishing (1 - ECE) as a quality
    // would invent a metric nobody measured, so no such key may exist.
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    for (ModelJarDescriptor descriptor : ModelJarRegistry.fromClasspath().descriptors()) {
      Map<String, Double> quality = catalog.qualityFor(descriptor, Set.of("decision"));
      assertFalse(
          quality.keySet().stream().anyMatch(key -> key.contains("calibrat")),
          descriptor.alias() + " published a calibration-derived quality: " + quality.keySet());
      assertFalse(
          quality.containsKey("decision-confidence"),
          descriptor.alias() + " published a confidence quality: " + quality.keySet());
    }
  }

  @Test
  void aModelWithNoDecisionTagGetsNoDecisionQualityEvenWithEvidence() {
    // The tag is what gates the lookup, so a text generator cannot inherit a decision score.
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    for (ModelJarDescriptor descriptor : ModelJarRegistry.fromClasspath().descriptors()) {
      assertFalse(
          catalog.qualityFor(descriptor, Set.of("chat")).containsKey("decision"),
          descriptor.alias() + " got a decision quality without the decision tag");
    }
  }

  @Test
  void anUnknownCapabilityContributesNoTagRatherThanAGuess() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    assertEquals(
        Set.of(),
        catalog.tagsFor(Set.of("interpretive-dance")),
        "an unmapped capability must contribute nothing; inventing a tag would route work to a "
            + "model nobody measured on it");
  }
}
