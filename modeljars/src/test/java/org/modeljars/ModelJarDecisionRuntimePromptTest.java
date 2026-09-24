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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.decisions.Noul;
import org.junit.jupiter.api.Test;

/**
 * THE CRITERION MUST REACH THE MODEL.
 *
 * <p>MEASURED 2026-09-23 against the published 0.1.50: five different questions about one contract
 * all returned 0.233783, because the prompt was composed from the evidence and the lettered options
 * and never from {@code space.question()}. Nothing failed. A decision runtime that ignores the
 * question still returns a well-formed, confidently wrong distribution, which is the worst shape a
 * defect can take, so the composition is asserted directly.
 */
final class ModelJarDecisionRuntimePromptTest {

  @Test
  void theCriterionIsInTheComposedPrompt() {
    String prompt =
        ModelJarDecisionRuntime.evidencePrefix(
            new Noul("Does the cap apply to data breaches?"), "The cap does not apply.");

    assertTrue(prompt.contains("The cap does not apply."), prompt);
    assertTrue(prompt.contains("Does the cap apply to data breaches?"), prompt);
  }

  @Test
  void twoCriteriaOverOneEvidenceComposeDifferently() {
    String evidence = "Northwind pays 42,500 dollars monthly.";

    assertNotEquals(
        ModelJarDecisionRuntime.evidencePrefix(new Noul("Is the fee stated?"), evidence),
        ModelJarDecisionRuntime.evidencePrefix(new Noul("Is uptime stated?"), evidence));
  }

  @Test
  void theEvidenceLeadsSoItCanBeSharedAcrossCriteria() {
    String evidence = "Northwind pays 42,500 dollars monthly.";

    assertTrue(
        ModelJarDecisionRuntime.evidencePrefix(new Noul("Anything?"), evidence)
            .startsWith(evidence));
  }
}
