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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.modeljars.ModelJarDescriptor;

/** Derives the shortest useful, unambiguous command names from the active catalog. */
final class GeneratedModelAliases {
  private static final Pattern PRODUCT_WITH_VERSION =
      Pattern.compile("^([a-z][a-z0-9]*?)([0-9]+(?:\\.[0-9]+)?)$");
  private static final Pattern SIZE = Pattern.compile("^(?:[a-z])?[0-9]+(?:\\.[0-9]+)?[bmk]$");
  private static final Set<String> PUBLISHERS =
      Set.of("cactus", "compute", "google", "ibm", "meta", "microsoft", "nvidia", "openai");
  private static final List<String> SPECIALIZATIONS =
      List.of(
          "embedding",
          "reranker",
          "coder",
          "code",
          "math",
          "tts",
          "transit",
          "medical",
          "finance",
          "legal",
          "translation",
          "sql");
  private static final Set<String> PACKAGING =
      Set.of(
          "gguf",
          "cact",
          "safetensors",
          "instruct",
          "chat",
          "it",
          "qat",
          "customvoice",
          "optimal",
          "model");

  private GeneratedModelAliases() {}

  static Map<String, String> from(Collection<ModelJarDescriptor> descriptors) {
    Objects.requireNonNull(descriptors, "descriptors");
    List<Identity> identities =
        descriptors.stream()
            .sorted(Comparator.comparing(ModelJarDescriptor::alias))
            .map(GeneratedModelAliases::identity)
            .toList();
    Map<String, Integer> occurrences = new HashMap<>();
    identities.forEach(
        identity ->
            identity.candidates().stream()
                .map(Candidate::value)
                .distinct()
                .forEach(value -> occurrences.merge(value, 1, Integer::sum)));

    Map<String, String> generated = new LinkedHashMap<>();
    for (Identity identity : identities) {
      String selected =
          identity.candidates().stream()
              .filter(candidate -> occurrences.getOrDefault(candidate.value(), 0) == 1)
              .min(Candidate.ORDER)
              .map(Candidate::value)
              .orElse(identity.descriptor().alias());
      if (generated.putIfAbsent(selected, identity.descriptor().alias()) != null) {
        throw new IllegalStateException("Generated model alias is not unique: " + selected);
      }
    }
    Map<String, String> sorted = new LinkedHashMap<>();
    generated.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
    return Collections.unmodifiableMap(sorted);
  }

  private static Identity identity(ModelJarDescriptor descriptor) {
    String normalized = normalize(descriptor.name().orElse(descriptor.alias()));
    List<String> tokens =
        new ArrayList<>(List.of(normalized.split("-")))
            .stream().filter(token -> !token.isBlank()).toList();
    int productIndex = 0;
    while (productIndex < tokens.size() - 1 && PUBLISHERS.contains(tokens.get(productIndex))) {
      productIndex++;
    }
    String product = tokens.get(productIndex);
    Matcher productMatcher = PRODUCT_WITH_VERSION.matcher(product);
    String brand = product;
    String attachedVersion = null;
    if (productMatcher.matches() && productMatcher.group(1).length() > 1) {
      brand = productMatcher.group(1);
      attachedVersion = productMatcher.group(2);
    }
    if (tokens.contains("minilm")) {
      brand = "minilm";
      attachedVersion = null;
    }

    String specialization =
        descriptor.capabilities().contains("reranking")
            ? "reranker"
            : SPECIALIZATIONS.stream().filter(tokens::contains).findFirst().orElse(null);
    String base = specialization == null ? brand : brand + "-" + specialization;
    Set<String> excluded = new HashSet<>(PACKAGING);
    excluded.add(brand);
    excluded.add(descriptor.format().toLowerCase(Locale.ROOT));
    excluded.addAll(List.of(normalize(descriptor.quantization()).split("-")));
    if (specialization != null) {
      excluded.add(specialization);
    }

    List<Component> components = new ArrayList<>();
    tokens.stream()
        .filter(SIZE.asMatchPredicate())
        .forEach(token -> add(components, new Component(token, 0)));
    if (attachedVersion != null) {
      add(components, new Component(attachedVersion, 1));
    }
    for (int index = productIndex + 1; index < tokens.size(); index++) {
      String token = tokens.get(index);
      if (token.matches("[0-9]+(?:\\.[0-9]+)?") && !excluded.contains(token)) {
        add(components, new Component(token, 1));
      }
    }
    tokens.stream()
        .filter(token -> !excluded.contains(token))
        .filter(token -> !SIZE.matcher(token).matches())
        .filter(token -> !token.matches("[0-9]+(?:\\.[0-9]+)?"))
        .filter(token -> !isQuantizationToken(token))
        .forEach(token -> add(components, new Component(token, 2)));
    String quantization = normalize(descriptor.quantization());
    String quantizationFamily = quantization.split("-")[0];
    add(components, new Component(quantizationFamily, 4));
    if (!quantization.equals(quantizationFamily)) {
      add(components, new Component(quantization, 5));
    }
    add(components, new Component(descriptor.format().toLowerCase(Locale.ROOT), 6));

    List<Candidate> candidates = new ArrayList<>();
    candidates.add(new Candidate(base, List.of()));
    combinations(base, components, 0, new ArrayList<>(), candidates);
    candidates.add(new Candidate(descriptor.alias(), List.of(new Component("catalog-id", 9))));
    return new Identity(descriptor, List.copyOf(candidates));
  }

  private static void combinations(
      String base,
      List<Component> components,
      int start,
      List<Component> selected,
      List<Candidate> candidates) {
    for (int index = start; index < components.size(); index++) {
      selected.add(components.get(index));
      String value =
          base
              + "-"
              + selected.stream().map(Component::value).reduce((a, b) -> a + "-" + b).orElse("");
      candidates.add(new Candidate(value, List.copyOf(selected)));
      if (selected.size() < 4) {
        combinations(base, components, index + 1, selected, candidates);
      }
      selected.removeLast();
    }
  }

  private static void add(List<Component> components, Component component) {
    if (!component.value().isBlank()
        && components.stream().noneMatch(existing -> existing.value().equals(component.value()))) {
      components.add(component);
    }
  }

  private static boolean isQuantizationToken(String token) {
    return token.matches("(?:q|cq|iq|fp|bf)[0-9]+") || Set.of("mixed", "none").contains(token);
  }

  private static String normalize(String value) {
    return value
        .strip()
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9.]+", "-")
        .replaceAll("^-+|-+$", "");
  }

  private record Identity(ModelJarDescriptor descriptor, List<Candidate> candidates) {}

  private record Component(String value, int priority) {}

  private record Candidate(String value, List<Component> components) {
    private static final Comparator<Candidate> ORDER =
        Comparator.comparingInt(Candidate::worstPriority)
            .thenComparingInt(candidate -> candidate.components().size())
            .thenComparingInt(Candidate::totalPriority)
            .thenComparingInt(candidate -> candidate.value().length())
            .thenComparing(Candidate::value);

    private int worstPriority() {
      return components.stream().mapToInt(Component::priority).max().orElse(-1);
    }

    private int totalPriority() {
      return components.stream().mapToInt(Component::priority).sum();
    }
  }
}
