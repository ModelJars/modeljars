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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/** Adds live short names, catalog IDs, and custom aliases to JLine completion. */
final class ModelArgumentCompleter implements Completer {
  private static final Set<String> MODEL_COMMANDS =
      Set.of(
          "pull",
          "show",
          "inspect",
          "remove",
          "rm",
          "delete",
          "run",
          "chat",
          "embed",
          "embedding",
          "coordinates",
          "coords",
          "snippet",
          "dependency",
          "deps");
  private static final Set<String> REMOVAL_COMMANDS = Set.of("remove", "rm", "delete");

  private final List<String> catalogNames;
  private final Supplier<Map<String, String>> aliases;
  private final Predicate<String> cached;

  ModelArgumentCompleter(
      List<String> catalogNames, Supplier<Map<String, String>> aliases, Predicate<String> cached) {
    this.catalogNames = List.copyOf(Objects.requireNonNull(catalogNames, "catalogNames"));
    this.aliases = Objects.requireNonNull(aliases, "aliases");
    this.cached = Objects.requireNonNull(cached, "cached");
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    if (line.words().isEmpty()) {
      return;
    }
    String command = line.words().getFirst();
    if (!MODEL_COMMANDS.contains(command) || line.wordIndex() != 1) {
      return;
    }
    String prefix = line.word();
    boolean removal = REMOVAL_COMMANDS.contains(command);
    Map<String, String> currentAliases = aliases.get();
    Set<String> existing = new HashSet<>();
    candidates.forEach(candidate -> existing.add(candidate.value()));

    currentAliases.entrySet().stream()
        .filter(entry -> !removal || cached.test(entry.getValue()))
        .filter(entry -> entry.getKey().startsWith(prefix))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                add(
                    candidates,
                    existing,
                    new Candidate(
                        entry.getKey(),
                        entry.getKey(),
                        "aliases",
                        entry.getValue(),
                        null,
                        null,
                        true)));
    catalogNames.stream()
        .filter(name -> !removal || cached.test(name))
        .filter(name -> name.startsWith(prefix))
        .sorted()
        .forEach(
            name ->
                add(
                    candidates,
                    existing,
                    new Candidate(name, name, "models", null, null, null, true)));
  }

  private static void add(List<Candidate> candidates, Set<String> existing, Candidate candidate) {
    if (existing.add(candidate.value())) {
      candidates.add(candidate);
    }
  }
}
