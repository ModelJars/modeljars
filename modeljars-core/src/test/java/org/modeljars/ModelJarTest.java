package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelJarTest {

  @Test
  void createsAnImmutableModelSelectorWithFluentRefinements() {
    ModelJar source = ModelJar.of("  hf://example/Nano  ");
    ModelJar selected =
        source
            .version("[1.0.0,2.0.0)")
            .variant("Q4_K_M")
            .backend("RUST-FFM")
            .capability("TEXT-GENERATION");

    assertEquals("hf://example/Nano", source.source());
    assertTrue(source.versionRange().isEmpty());
    assertEquals(
        VersionRange.parse("[1.0.0,2.0.0)"), selected.versionRange().orElseThrow());
    assertEquals("q4_k_m", selected.variant().orElseThrow());
    assertEquals("rust-ffm", selected.backend().orElseThrow());
    assertEquals("text-generation", selected.capability().orElseThrow());
  }

  @Test
  void rejectsABlankSource() {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> ModelJar.of(" "));

    assertEquals("source must not be blank", error.getMessage());
  }
}
