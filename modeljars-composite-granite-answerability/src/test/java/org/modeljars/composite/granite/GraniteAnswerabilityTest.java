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
package org.modeljars.composite.granite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.runtime.ActivatedToolCallingModel;
import com.integrallis.models.runtime.chat.GraniteDocumentsPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.modeljars.composite.granite.GraniteAnswerability.Answerability;
import org.modeljars.composite.granite.GraniteAnswerability.Prefix;
import org.modeljars.composite.granite.GraniteAnswerability.Qualification;
import org.modeljars.composite.granite.GraniteAnswerability.Request;
import org.modeljars.composite.granite.GraniteAnswerability.Turn;

class GraniteAnswerabilityTest {
  private static final List<String> DOCUMENTS =
      List.of("Aachen lies in North Rhine-Westphalia.", "Its cathedral was consecrated in 805.");

  private static String text(ModelPrompt prompt) {
    StringBuilder out = new StringBuilder();
    for (ModelPrompt.Segment segment : prompt.segments()) {
      out.append(segment.text());
    }
    return out.toString();
  }

  @Test
  void bindsTheExactPublishedMemberMarkers() {
    assertEquals(
        "org.modeljars.huggingface:ibm-granite.granite-4.1-3b-gguf.q4_k_m:4.1.0-q4_k_m.2",
        GraniteAnswerability.BASE.source());
    assertEquals(
        "org.modeljars.github:modeljars.activated-adapters."
            + "granite-4.1-3b-answerability-alora-integrallis.f32:1.0.0-f32.2",
        GraniteAnswerability.SPECIALIST.source());
  }

  @Test
  void bindsTheFrozenWindowAndItsDecodingBudget() {
    assertEquals(
        "dfb8cd7203418c79bae27306ffc4857f0ab02be7f9f8ddcae793af556e9cab37",
        GraniteAnswerability.WINDOW_SHA256);
    assertEquals(6, GraniteAnswerability.MAX_COMPLETION_TOKENS);
    assertEquals(6, GraniteAnswerability.OPTIONS.maxTokens());
    assertEquals(0f, GraniteAnswerability.OPTIONS.temperature());
  }

  @Test
  void retainsNoCleanHostMeasurementsUntilTheCompositionRunHasHappened() {
    // TODO-COMPOSITION-RUN: replace with the measured Qualification once the clean host has run.
    assertTrue(GraniteAnswerability.QUALIFICATION.isEmpty());
  }

  @Test
  void rendersTheFrozenWindowRetrievalEnvelope() {
    ModelPrompt prompt = GraniteAnswerability.render(Request.of(DOCUMENTS, "Where is Aachen?"));

    ModelPrompt.Builder expected =
        GraniteDocumentsPrompt.appendSystem(ModelPrompt.builder(), DOCUMENTS, null);
    GraniteDocumentsPrompt.appendTurn(expected, "user", "Where is Aachen?");

    assertEquals(text(GraniteDocumentsPrompt.finish(expected)), text(prompt));
  }

  @Test
  void endsEveryRenderedPromptAtTheActivationBoundary() {
    String rendered = text(GraniteAnswerability.render(Request.of(DOCUMENTS, "Where is Aachen?")));

    assertTrue(rendered.endsWith(GraniteDocumentsPrompt.ASSISTANT_MARKER));
    assertTrue(rendered.contains(GraniteDocumentsPrompt.DOCUMENTS_OPEN));
    assertTrue(rendered.contains(GraniteDocumentsPrompt.DOCUMENTS_CLOSE));
  }

  @Test
  void rendersMultiTurnConversationsInOrder() {
    Request request =
        new Request(
            DOCUMENTS,
            List.of(
                Turn.user("Where is Aachen?"), Turn.assistant("In Germany."), Turn.user("When?")));

    String rendered = text(GraniteAnswerability.render(request));

    assertTrue(rendered.indexOf("Where is Aachen?") < rendered.indexOf("In Germany."));
    assertTrue(rendered.indexOf("In Germany.") < rendered.indexOf("When?"));
  }

  @Test
  void readsOnlyTheTwoContractedLabels() {
    assertEquals(Answerability.ANSWERABLE, Answerability.parse("\"answerable\""));
    assertEquals(Answerability.UNANSWERABLE, Answerability.parse("\"unanswerable\""));
    assertEquals(Answerability.ANSWERABLE, Answerability.parse("  \"answerable\"\n"));
    assertEquals(Answerability.UNSTRUCTURED, Answerability.parse("answerable"));
    assertEquals(Answerability.UNSTRUCTURED, Answerability.parse("\"Answerable\""));
    assertEquals(Answerability.UNSTRUCTURED, Answerability.parse("\"answerable\" indeed"));
    assertEquals(Answerability.UNSTRUCTURED, Answerability.parse(""));
    assertEquals(Answerability.UNSTRUCTURED, Answerability.parse(null));
  }

  @Test
  void reportsWhetherAVerdictHonouredTheContract() {
    assertTrue(
        new GraniteAnswerability.Verdict(Answerability.ANSWERABLE, "\"answerable\"", true, 269, 12)
            .structured());
    assertFalse(
        new GraniteAnswerability.Verdict(Answerability.UNSTRUCTURED, "maybe", true, 269, 12)
            .structured());
  }

  @Test
  void mapsEveryPrefixPolicyToItsRuntimeStrategy() {
    assertEquals(ActivatedToolCallingModel.PrefixStrategy.SHARED, Prefix.SHARED.strategy());
    assertEquals(ActivatedToolCallingModel.PrefixStrategy.RECOMPUTED, Prefix.RECOMPUTED.strategy());
    assertEquals(ActivatedToolCallingModel.PrefixStrategy.AUTO, Prefix.AUTOMATIC.strategy());
  }

  @Test
  void refusesRequestsTheSpecialistWasNotQualifiedOn() {
    assertThrows(IllegalArgumentException.class, () -> Request.of(List.of(), "Where is Aachen?"));
    assertThrows(IllegalArgumentException.class, () -> Request.of(List.of("  "), "Where?"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Request(DOCUMENTS, List.of(Turn.assistant("Hi"))));
    assertThrows(IllegalArgumentException.class, () -> new Request(DOCUMENTS, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Turn("system", "Be helpful."));
    assertThrows(IllegalArgumentException.class, () -> Turn.user(" "));
  }

  @Test
  void copiesRequestCollectionsDefensively() {
    List<String> documents = new java.util.ArrayList<>(DOCUMENTS);
    Request request = Request.of(documents, "Where is Aachen?");
    documents.clear();

    assertEquals(2, request.documents().size());
    assertThrows(UnsupportedOperationException.class, () -> request.documents().clear());
    assertThrows(UnsupportedOperationException.class, () -> request.conversation().clear());
  }

  @Test
  void computesTheLatencyImprovementAndMemoryVerdictTheGateReads() {
    Qualification measured =
        new Qualification("a".repeat(40), "0.1.47", 6, 100_000L, 60_000L, 3_000L, 700L, 1_100L);

    assertEquals(0.4, measured.latencyImprovement(), 1e-12);
    assertTrue(measured.reducesUniqueInferenceState());
    assertFalse(
        new Qualification("a".repeat(40), "0.1.47", 6, 100_000L, 60_000L, 3_000L, 1_100L, 700L)
            .reducesUniqueInferenceState());
  }

  @Test
  void refusesIncompleteCleanHostMeasurements() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Qualification("a".repeat(40), "0.1.47", 0, 100L, 60L, 3L, 7L, 11L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Qualification("a".repeat(40), "0.1.47", 6, 0L, 60L, 3L, 7L, 11L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Qualification("a".repeat(40), "0.1.47", 6, 100L, 60L, 3L, 0L, 11L));
  }
}
