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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelAliasStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void persistsAliasesAndReloadsThem() {
    Path aliases = temporaryDirectory.resolve("aliases.properties");
    ModelAliasStore store = new ModelAliasStore(aliases);

    store.set("qwen", "qwen3_0_6b_q4_0", Set.of("qwen3_0_6b_q4_0"));

    assertEquals(Map.of("qwen", "qwen3_0_6b_q4_0"), new ModelAliasStore(aliases).aliases());
  }

  @Test
  void rejectsNamesThatClashWithCatalogAliases() {
    ModelAliasStore store = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.set("qwen3_0_6b_q4_0", "other_model", Set.of("qwen3_0_6b_q4_0")));

    assertTrue(failure.getMessage().contains("catalog model name"));
  }

  @Test
  void rejectsCaseVariantsOfCatalogAliases() {
    ModelAliasStore store = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));

    assertThrows(
        IllegalArgumentException.class,
        () -> store.set("QWEN3_0_6B_Q4_0", "other_model", Set.of("qwen3_0_6b_q4_0")));
  }

  @Test
  void rejectsInvalidShellNamesAndRemovesAliases() {
    ModelAliasStore store = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));
    assertThrows(
        IllegalArgumentException.class, () -> store.set("not a name", "qwen3_0_6b_q4_0", Set.of()));
    store.set("qwen", "qwen3_0_6b_q4_0", Set.of());

    assertTrue(store.remove("qwen"));
    assertTrue(store.aliases().isEmpty());
  }
}
