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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContributionDraftTest {
  @Test
  void rendersVerifiedCandidateFactsWithoutClaimingRuntimeQualification() {
    ContributionDraft draft =
        new ContributionDraft(
            "Qwen/Demo",
            URI.create("https://huggingface.co/Qwen/Demo"),
            "a".repeat(40),
            "Demo BF16",
            "safetensors",
            Optional.of("qwen2"),
            Optional.of("Apache-2.0"),
            List.of("text-generation", "chat"),
            List.of("general"),
            List.of(
                new ContributionFile("model.safetensors", "model-weights", "b".repeat(64), 1024)));

    String markdown = draft.markdown();

    assertTrue(markdown.contains("ModelJars candidate submission"));
    assertTrue(markdown.contains("`safetensors`"));
    assertTrue(markdown.contains("`" + "b".repeat(64) + "`"));
    assertTrue(
        markdown.contains("Candidate intake does not claim Models compatibility or qualification"));
    assertTrue(markdown.contains("<!-- modeljars-candidate-v1 -->"));
    assertFalse(markdown.contains("qualified: true"));
  }
}
