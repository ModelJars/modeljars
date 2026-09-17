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
package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.modeljars.cli.SpringFixtures.assertContains;

import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelToolQualificationRegistry;
import org.modeljars.cli.SpringIntegration.Flavor;
import org.modeljars.cli.SpringIntegration.Kind;

class SpringIntegrationTest {
  @Test
  void classifiesEveryAdapterBackedCapabilityForSpringAi() {
    assertEquals(Kind.CHAT, SpringIntegration.kind(SpringFixtures.chat(), Flavor.SPRING_AI));
    assertEquals(
        Kind.TOOL_CHAT, SpringIntegration.kind(SpringFixtures.toolChat(), Flavor.SPRING_AI));
    assertEquals(
        Kind.ACTION_TOOLS, SpringIntegration.kind(SpringFixtures.needle(), Flavor.SPRING_AI));
    assertEquals(
        Kind.EMBEDDING, SpringIntegration.kind(SpringFixtures.embedding(), Flavor.SPRING_AI));
    assertEquals(
        Kind.RERANKING, SpringIntegration.kind(SpringFixtures.reranking(), Flavor.SPRING_AI));
  }

  @Test
  void rejectsSpeechAndHybridModelsWithTheSupportedAlternatives() {
    for (ModelJarDescriptor unsupported :
        List.of(SpringFixtures.speech(), SpringFixtures.composite())) {
      for (Flavor flavor : Flavor.values()) {
        IllegalArgumentException failure =
            assertThrows(
                IllegalArgumentException.class, () -> SpringIntegration.kind(unsupported, flavor));
        assertContains(
            failure.getMessage(),
            unsupported.alias(),
            flavor.label(),
            "chat, tool-calling, embedding, and reranking",
            "modeljars demo " + unsupported.alias());
      }
    }
  }

  @Test
  void rejectsNeedleUnderBootBecauseAutoConfigurationCannotRegisterTypedToolRenderers() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> SpringIntegration.kind(SpringFixtures.needle(), Flavor.SPRING_BOOT));

    assertContains(failure.getMessage(), "example_needle_cq2", "--spring-ai", "renderer");
  }

  @Test
  void bootAcceptsChatToolChatEmbeddingAndReranking() {
    assertEquals(Kind.CHAT, SpringIntegration.kind(SpringFixtures.chat(), Flavor.SPRING_BOOT));
    assertEquals(
        Kind.TOOL_CHAT, SpringIntegration.kind(SpringFixtures.toolChat(), Flavor.SPRING_BOOT));
    assertEquals(
        Kind.EMBEDDING, SpringIntegration.kind(SpringFixtures.embedding(), Flavor.SPRING_BOOT));
    assertEquals(
        Kind.RERANKING, SpringIntegration.kind(SpringFixtures.reranking(), Flavor.SPRING_BOOT));
  }

  @Test
  void bundledVersionsAreTheBuildsModelsAndSpringVersions() {
    SpringIntegration.Versions versions = SpringIntegration.Versions.bundled("0.1.42");

    assertEquals("0.1.42", versions.modeljars());
    assertEquals(System.getProperty("modeljars.test.modelsVersion"), versions.models());
    assertEquals(System.getProperty("modeljars.test.springAiVersion"), versions.springAi());
    assertEquals(System.getProperty("modeljars.test.springBootVersion"), versions.springBoot());
  }

  @Test
  void resolvesARuntimeChatTemplateForEveryQualifiedCatalogChatModel() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
    ModelToolQualificationRegistry tools = ModelToolQualificationRegistry.fromClasspath();
    SpringIntegration.ChatTemplates templates = SpringIntegration.ChatTemplates.fromClasspath();

    int checked = 0;
    boolean graniteChecked = false;
    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      boolean qualified =
          rag.qualificationsFor(descriptor).stream().anyMatch(q -> q.productionUsable())
              || tools.qualificationsFor(descriptor).stream().anyMatch(q -> q.productionUsable());
      if (!qualified || descriptor.format().equals("composite")) {
        continue;
      }
      String template =
          templates
              .templateFor(descriptor)
              .orElseThrow(() -> new AssertionError("no template for " + descriptor.alias()));
      // Spring Boot configuration hands this id to ChatTemplate.parse, so it must be a runtime id.
      assertEquals(template, ChatTemplate.parse(template).id(), descriptor.alias());
      if (descriptor.alias().equals("ibm_granite_granite_4_1_3b_gguf_q4_k_m")) {
        // The qualification records the RAG harness's granite-documents envelope.
        assertEquals("granite", template);
        graniteChecked = true;
      }
      checked++;
    }
    assertTrue(checked > 0, "the shipped catalog must contain qualified chat models");
    assertTrue(graniteChecked, "the shipped catalog must contain the qualified Granite 4.1 3B");
  }

  @Test
  void reportsAMissingQualifiedChatTemplateInsteadOfFallingBackToRaw() {
    SpringIntegration.ChatTemplates none = SpringIntegration.ChatTemplates.of(java.util.Map.of());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> SpringIntegration.requireChatTemplate(SpringFixtures.chat(), none));

    assertContains(failure.getMessage(), "example_q4_0", "chat template", "--spring-ai");
    assertFalse(failure.getMessage().contains("raw"));
  }
}
