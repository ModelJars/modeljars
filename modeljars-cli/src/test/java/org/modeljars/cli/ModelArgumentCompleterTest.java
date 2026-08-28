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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

class ModelArgumentCompleterTest {
  @Test
  void completesCatalogNamesAndNicknamesForModelCommands() {
    ModelArgumentCompleter completer =
        new ModelArgumentCompleter(
            List.of("qwen3_0_6b_q4_0", "embeddinggemma_300m_q8_0"),
            () -> Map.of("qwen", "qwen3_0_6b_q4_0"),
            ignored -> true);

    List<String> pull = complete(completer, "pull qw");
    List<String> show = complete(completer, "show ");

    assertEquals(List.of("qwen", "qwen3_0_6b_q4_0"), pull);
    assertTrue(show.contains("qwen"));
    assertTrue(show.contains("embeddinggemma_300m_q8_0"));
  }

  @Test
  void limitsRemovalCompletionToCachedModelsAndSupportsEveryRemovalAlias() {
    ModelArgumentCompleter completer =
        new ModelArgumentCompleter(
            List.of("cached_model", "remote_model"), Map::of, name -> name.equals("cached_model"));

    for (String command : List.of("remove", "rm", "delete")) {
      assertEquals(List.of("cached_model"), complete(completer, command + " "));
    }
  }

  private static List<String> complete(ModelArgumentCompleter completer, String line) {
    var parsed = new DefaultParser().parse(line, line.length());
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, parsed, candidates);
    return candidates.stream().map(Candidate::value).sorted().toList();
  }
}
