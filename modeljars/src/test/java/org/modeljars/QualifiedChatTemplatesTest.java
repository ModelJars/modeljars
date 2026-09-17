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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import com.integrallis.models.runtime.chat.GraniteDocumentsPrompt;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QualifiedChatTemplatesTest {

  @Test
  void resolvesTheGraniteDocumentsHarnessEnvelopeToTheGraniteChatTemplate() {
    assertSame(ChatTemplate.GRANITE, QualifiedChatTemplates.resolve("granite-documents"));
    assertSame(ChatTemplate.GRANITE, QualifiedChatTemplates.resolve(" Granite-Documents "));
  }

  @Test
  void graniteDocumentsTurnsAreTheGraniteChatTemplateTurns() {
    // The mapping is only sound if the two renderings differ in the documents system turn alone.
    // Built from the Models renderers themselves, so a change to either envelope fails here.
    String question = "Which river flows through <|start_of_role|>Paris?";
    String answer = "The Seine.";
    String followUp = "And through Lyon?";

    ModelPrompt.Builder documents = ModelPrompt.builder();
    GraniteDocumentsPrompt.appendTurn(documents, "user", question);
    GraniteDocumentsPrompt.appendTurn(documents, "assistant", answer);
    GraniteDocumentsPrompt.appendTurn(documents, "user", followUp);
    ModelPrompt documentsTurns = GraniteDocumentsPrompt.finish(documents);

    ModelPrompt runtimeTurns =
        QualifiedChatTemplates.resolve("granite-documents")
            .render(
                List.of(
                    ChatMessage.user(question),
                    ChatMessage.assistant(answer),
                    ChatMessage.user(followUp)));

    assertEquals(documentsTurns.segments(), runtimeTurns.segments());

    ModelPrompt systemBlock =
        GraniteDocumentsPrompt.appendSystem(
                ModelPrompt.builder(), List.of("The Seine flows through Paris."), "Cite sources.")
            .build();
    ModelPrompt.Builder withEvidence = ModelPrompt.builder();
    GraniteDocumentsPrompt.appendSystem(
        withEvidence, List.of("The Seine flows through Paris."), "Cite sources.");
    GraniteDocumentsPrompt.appendTurn(withEvidence, "user", question);
    GraniteDocumentsPrompt.appendTurn(withEvidence, "assistant", answer);
    GraniteDocumentsPrompt.appendTurn(withEvidence, "user", followUp);
    assertEquals(
        concat(systemBlock, runtimeTurns).segments(),
        GraniteDocumentsPrompt.finish(withEvidence).segments());
  }

  @Test
  void passesEveryRuntimeTemplateIdThroughUnchanged() {
    for (ChatTemplate template : ChatTemplate.values()) {
      assertSame(template, QualifiedChatTemplates.resolve(template.id()));
    }
  }

  @Test
  void rejectsUnknownTemplatesInsteadOfFallingBackToRaw() {
    for (String unknown : new String[] {"granite-documents-v2", "llama2", "", "  "}) {
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class, () -> QualifiedChatTemplates.resolve(unknown));
      assertTrue(failure.getMessage().contains(unknown), failure.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> QualifiedChatTemplates.resolve(null));
  }

  /**
   * Release gate: every generative qualification bundled into this runtime must name a template the
   * runtime can render. A qualification that records a harness-only envelope otherwise ships a
   * model that {@code ModelJars.openRuntime} rejects only after it has loaded the weights.
   */
  @Test
  void everyBundledGenerativeQualificationResolvesToARuntimeChatTemplate() {
    List<ModelExecutionQualification> qualifications = new ArrayList<>();
    qualifications.addAll(ModelRagQualificationRegistry.fromClasspath().qualifications());
    qualifications.addAll(ModelToolQualificationRegistry.fromClasspath().qualifications());
    assertFalse(qualifications.isEmpty(), "no bundled generative qualifications were loaded");

    List<String> unresolved = new ArrayList<>();
    for (ModelExecutionQualification qualification : qualifications) {
      try {
        QualifiedChatTemplates.resolve(qualification.promptTemplate());
      } catch (IllegalArgumentException failure) {
        unresolved.add(qualification.modelId() + " -> " + qualification.promptTemplate());
      }
    }
    assertEquals(List.of(), unresolved);
  }

  private static ModelPrompt concat(ModelPrompt first, ModelPrompt second) {
    ModelPrompt.Builder builder = ModelPrompt.builder();
    for (ModelPrompt prompt : List.of(first, second)) {
      for (ModelPrompt.Segment segment : prompt.segments()) {
        if (segment.kind() == ModelPrompt.SegmentKind.CONTROL) {
          builder.control(segment.text());
        } else {
          builder.text(segment.text());
        }
      }
    }
    return builder.build();
  }
}
