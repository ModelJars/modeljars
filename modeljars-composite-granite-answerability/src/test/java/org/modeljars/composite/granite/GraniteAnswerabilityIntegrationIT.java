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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarActivatedRuntime;
import org.modeljars.composite.granite.GraniteAnswerability.Answerability;
import org.modeljars.composite.granite.GraniteAnswerability.Prefix;
import org.modeljars.composite.granite.GraniteAnswerability.Request;
import org.modeljars.composite.granite.GraniteAnswerability.Verdict;

/**
 * Real-weight run of the published recipe. Both members are downloaded and verified by ModelJars
 * from their marker descriptors; nothing here fetches model bytes itself.
 */
class GraniteAnswerabilityIntegrationIT {
  private static final List<String> DOCUMENTS =
      List.of(
          "Aachen is a spa city in North Rhine-Westphalia, Germany, near the Belgian and Dutch"
              + " borders.",
          "Aachen Cathedral was consecrated in 805 and became the first German UNESCO World"
              + " Heritage site in 1978.");

  @Test
  void runsThePublishedRecipeThroughModelJars() {
    try (ModelJarActivatedRuntime hybrid = GraniteAnswerability.open()) {
      assertEquals(
          GraniteAnswerability.BASE.source(),
          hybrid.baseDescriptor().markerCoordinate().toString());
      assertEquals(
          GraniteAnswerability.SPECIALIST.source(),
          hybrid.adapterDescriptor().markerCoordinate().toString());

      Request answerable = Request.of(DOCUMENTS, "In which German state is Aachen?");
      Request unanswerable = Request.of(DOCUMENTS, "What is the current population of Reykjavik?");

      Verdict sharedAnswerable = GraniteAnswerability.classify(hybrid, answerable, Prefix.SHARED);
      Verdict sharedUnanswerable =
          GraniteAnswerability.classify(hybrid, unanswerable, Prefix.SHARED);

      assertEquals(Answerability.ANSWERABLE, sharedAnswerable.answerability());
      assertEquals(Answerability.UNANSWERABLE, sharedUnanswerable.answerability());
      assertTrue(sharedAnswerable.structured());
      assertTrue(sharedAnswerable.physicallySharesPrefix());
      assertTrue(sharedAnswerable.sharedPrefixTokens() > 0);
    }
  }

  @Test
  void producesTheSameVerdictWhetherOrNotThePrefixIsPhysicallyShared() {
    try (ModelJarActivatedRuntime hybrid = GraniteAnswerability.open()) {
      Request request = Request.of(DOCUMENTS, "When was Aachen Cathedral consecrated?");

      Verdict shared = GraniteAnswerability.classify(hybrid, request, Prefix.SHARED);
      Verdict recomputed = GraniteAnswerability.classify(hybrid, request, Prefix.RECOMPUTED);

      assertEquals(shared.output(), recomputed.output());
      assertEquals(shared.sharedPrefixTokens(), recomputed.sharedPrefixTokens());
      assertTrue(shared.physicallySharesPrefix());
      assertFalse(recomputed.physicallySharesPrefix());
    }
  }
}
