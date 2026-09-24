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
        prompt(new Noul("Does the cap apply to data breaches?"), "The cap does not apply.");

    assertTrue(prompt.contains("The cap does not apply."), prompt);
    assertTrue(prompt.contains("Does the cap apply to data breaches?"), prompt);
  }

  @Test
  void twoCriteriaOverOneEvidenceComposeDifferently() {
    String evidence = "Northwind pays 42,500 dollars monthly.";

    assertNotEquals(
        prompt(new Noul("Is the fee stated?"), evidence),
        prompt(new Noul("Is uptime stated?"), evidence));
  }

  @Test
  void theSharedPartLeadsSoItCanBeReusedAcrossCriteria() {
    String evidence = "Northwind pays 42,500 dollars monthly.";

    assertTrue(
        ModelJarDecisionRuntime.sharedPrefix(new Noul("Anything?"), evidence).startsWith(evidence));
  }

  /**
   * THE RUBRIC IS SHARED AND THE LETTERS ARE NOT, AND THE SPLIT IS WHERE THE LATENCY IS.
   *
   * <p>Everything in the shared prefix is prefilled once for a batch of questions; everything after
   * it costs 18.5 ms a token per question, MEASURED 2026-09-24. The rubric is most of the added
   * tokens, so which side of the split it falls on decides whether a decision with a rubric is a
   * fifth of a second or most of one.
   *
   * <p>It is also where the accuracy is, and the two agree for once. Over 120 JevBench items:
   * rubric and letters both after the criterion scored 86.1, both before it 79.6, rubric before and
   * letters after <b>88.9</b>.
   */
  @Test
  void theRubricIsSharedAndTheLettersFollowTheCriterion() {
    String evidence = "Northwind pays 42,500 dollars monthly.";
    Noul space = new Noul("Is the fee stated?", "A figure and a period are given", "No figure");

    String shared = ModelJarDecisionRuntime.sharedPrefix(space, evidence);
    String suffix = ModelJarDecisionRuntime.questionSuffix(space);

    assertEquals(
        """
        Northwind pays 42,500 dollars monthly.
        Options:
        - false: No figure
        - true: A figure and a period are given""",
        shared);
    assertEquals(
        """

        Is the fee stated?
        A: false
        B: true
        Answer:""",
        suffix);
  }

  /** Without a rubric the shared part is the evidence alone, exactly as it always was. */
  @Test
  void aSpaceWithoutARubricSharesOnlyTheEvidence() {
    String evidence = "Northwind pays 42,500 dollars monthly.";

    assertEquals(
        evidence, ModelJarDecisionRuntime.sharedPrefix(new Noul("Is the fee stated?"), evidence));
  }

  private static String prompt(Noul space, String evidence) {
    return ModelJarDecisionRuntime.sharedPrefix(space, evidence)
        + ModelJarDecisionRuntime.questionSuffix(space);
  }
}
