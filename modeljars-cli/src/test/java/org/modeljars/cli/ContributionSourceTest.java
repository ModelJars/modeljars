package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContributionSourceTest {
  @Test
  void normalizesSupportedHuggingFaceRepositoryForms() {
    assertEquals("Qwen/Qwen2.5-0.5B-Instruct", ContributionSource.parse("Qwen/Qwen2.5-0.5B-Instruct").repository());
    assertEquals("Qwen/Qwen2.5-0.5B-Instruct", ContributionSource.parse("hf://Qwen/Qwen2.5-0.5B-Instruct").repository());
    assertEquals(
        "Qwen/Qwen2.5-0.5B-Instruct",
        ContributionSource.parse("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct").repository());
  }

  @Test
  void rejectsNonHuggingFaceAndPathLikeSources() {
    assertThrows(IllegalArgumentException.class, () -> ContributionSource.parse("https://example.com/owner/model"));
    assertThrows(IllegalArgumentException.class, () -> ContributionSource.parse("owner/../model"));
    assertThrows(IllegalArgumentException.class, () -> ContributionSource.parse("owner"));
  }
}
