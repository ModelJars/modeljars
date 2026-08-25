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
                new ContributionFile(
                    "model.safetensors", "model-weights", "b".repeat(64), 1024)));

    String markdown = draft.markdown();

    assertTrue(markdown.contains("ModelJars candidate submission"));
    assertTrue(markdown.contains("`safetensors`"));
    assertTrue(markdown.contains("`" + "b".repeat(64) + "`"));
    assertTrue(markdown.contains("Candidate intake does not claim Models compatibility or qualification"));
    assertTrue(markdown.contains("<!-- modeljars-candidate-v1 -->"));
    assertFalse(markdown.contains("qualified: true"));
  }
}
