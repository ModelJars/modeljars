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
    assertEquals(VersionRange.parse("[1.0.0,2.0.0)"), selected.versionRange().orElseThrow());
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
