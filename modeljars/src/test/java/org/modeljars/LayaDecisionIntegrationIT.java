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

import com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionAnswer;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionModel;
import com.integrallis.models.backend.purejava.bert.ModernBertDecisionPrompt;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The second step of the published route to a typed-decision model, on the real weights.
 *
 * <p>{@code LayaDecisionCatalogRouteTest} pins the first step and the refusal that sends a caller
 * here. This opens what {@code locate} returned and asks it one question, which is the only way to
 * know the two-step route actually ends in an answer.
 *
 * <p>It became possible with models 0.5.4: {@code ModernBertDecisionModel} ships in {@code
 * backend-java}, and 0.5.3 carried the {@code ...purejava.bert} package with no {@code ModernBert*}
 * class in it at all.
 */
class LayaDecisionIntegrationIT {

  private static final String MODEL_ID = "laya_q8_0";

  @Test
  void decidesOneTypedQuestionThroughTheRouteTheRefusalNames() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    Optional<Path> located = catalog.locate(MODEL_ID);
    Assumptions.assumeTrue(
        located.isPresent(), () -> MODEL_ID + " is not installed on this machine");

    // The first case of the committed JevBench cohort, with its label taken from the cohort file
    // rather than from a run. This is a smoke test of the published route, not a re-measurement:
    // LayaJevBenchQualificationIT in the models repository owns the accuracy number.
    try (ModernBertDecisionModel model = ModernBertDecisionModel.open(located.orElseThrow())) {
      ModernBertDecisionAnswer answer =
          model.decide(
              QuestionType.CHOICE,
              "Which intent does the user's message express?",
              List.of(
                  ModernBertDecisionPrompt.Option.of("track_order"),
                  ModernBertDecisionPrompt.Option.of("cancel_order"),
                  ModernBertDecisionPrompt.Option.of("change_address"),
                  ModernBertDecisionPrompt.Option.of("report_damage"),
                  ModernBertDecisionPrompt.Option.of("billing_question")),
              "Where is my package? I ordered it last week and it still hasn't arrived.");

      assertEquals(5, answer.probabilities().length);
      assertEquals(0, answer.chosen(), "easy-intent-00 is labelled track_order in the cohort");
      // Five options land in `choice:3-5`, a bucket the artifact declares a usable temperature
      // for, so this probability is a fitted one and the record says so.
      assertEquals("choice:3-5", answer.temperatureBucket());
      assertFalse(answer.uncalibrated(), "choice:3-5 is a calibrated bucket");
      assertTrue(
          answer.confidence() > 0.0f && answer.confidence() <= 1.0f,
          () -> "confidence out of range: " + answer.confidence());
    }
  }
}
