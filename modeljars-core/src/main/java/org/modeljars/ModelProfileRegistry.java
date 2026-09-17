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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Loads model generation profiles and computed memory fit from the aggregate ModelJars registry.
 *
 * <p>The profiles are carried as {@code modelProfile.<id>.*} properties in the aggregate {@code
 * META-INF/modeljars/registry.properties}. Marker JARs never carry them, so a marker's identity is
 * unaffected by profile changes.
 */
public final class ModelProfileRegistry {
  /** Property naming the profile schema version. */
  public static final String SCHEMA_PROPERTY = "modeljars.modelProfiles.schemaVersion";

  /** Supported profile schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String PREFIX = "modelProfile.";

  private final List<ModelProfile> profiles;

  private ModelProfileRegistry(List<ModelProfile> profiles) {
    this.profiles =
        profiles.stream().sorted(Comparator.comparing(ModelProfile::modelAlias)).toList();
  }

  /**
   * Returns a registry with no profiles.
   *
   * @return empty registry
   */
  public static ModelProfileRegistry empty() {
    return new ModelProfileRegistry(List.of());
  }

  /**
   * Returns every loaded profile.
   *
   * @return profiles sorted by model ID
   */
  public List<ModelProfile> profiles() {
    return profiles;
  }

  /**
   * Returns the profile for the exact artifact a descriptor pins.
   *
   * @param descriptor catalog descriptor
   * @return the profile when its model ID and artifact SHA-256 both match
   */
  public Optional<ModelProfile> profileFor(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return profiles.stream()
        .filter(profile -> profile.modelAlias().equals(descriptor.alias()))
        .filter(profile -> descriptor.sha256().filter(profile.artifactSha256()::equals).isPresent())
        .findFirst();
  }

  /**
   * Loads profiles from every registry resource on the context class path.
   *
   * @return merged registry
   */
  public static ModelProfileRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads profiles from every registry resource visible to a class loader.
   *
   * @param classLoader class loader to search, or {@code null} for this class's loader
   * @return merged registry
   */
  public static ModelProfileRegistry fromClasspath(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ModelProfileRegistry.class.getClassLoader() : classLoader;
    Map<String, ModelProfile> merged = new LinkedHashMap<>();
    try {
      Enumeration<URL> resources = loader.getResources(ClasspathModelJarRegistry.REGISTRY_RESOURCE);
      while (resources.hasMoreElements()) {
        Properties properties = new Properties();
        try (InputStream input = resources.nextElement().openStream()) {
          properties.load(input);
        }
        for (ModelProfile profile : fromProperties(properties).profiles()) {
          ModelProfile previous = merged.putIfAbsent(profile.modelAlias(), profile);
          if (previous != null && !previous.equals(profile)) {
            throw new ModelJarException("Conflicting model profile: " + profile.modelAlias());
          }
        }
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars model profiles", e);
    }
    return new ModelProfileRegistry(List.copyOf(merged.values()));
  }

  /**
   * Parses profiles from registry properties. Properties without profile keys yield an empty
   * registry.
   *
   * @param properties registry properties
   * @return parsed registry
   */
  public static ModelProfileRegistry fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    TreeSet<String> aliases = new TreeSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith(PREFIX)) {
        int end = name.indexOf('.', PREFIX.length());
        if (end > PREFIX.length()) {
          aliases.add(name.substring(PREFIX.length(), end));
        }
      }
    }
    String schema = properties.getProperty(SCHEMA_PROPERTY);
    if (schema == null) {
      if (!aliases.isEmpty()) {
        throw new ModelJarException("Model profiles require " + SCHEMA_PROPERTY);
      }
      return empty();
    }
    if (parseInt(SCHEMA_PROPERTY, schema.trim()) != SCHEMA_VERSION) {
      throw new ModelJarException("Unsupported ModelJars model profile schema version: " + schema);
    }
    return new ModelProfileRegistry(
        aliases.stream().map(alias -> profile(alias, properties)).toList());
  }

  private static ModelProfile profile(String alias, Properties properties) {
    Reader reader = new Reader(properties, PREFIX + alias + ".");
    return new ModelProfile(
        alias,
        reader.required("artifactSha256"),
        reader.has("generation.") ? Optional.of(generation(reader)) : Optional.empty(),
        reader.has("memory.") ? Optional.of(memoryFit(reader)) : Optional.empty(),
        repetitionLoop(reader));
  }

  private static List<ModelRepetitionLoopMeasurement> repetitionLoop(Reader reader) {
    int count = reader.optionalInt("repetitionLoop.count").orElse(0);
    return IntStream.range(0, count)
        .mapToObj(
            index -> {
              String entry = "repetitionLoop.%03d.".formatted(index);
              try {
                return new ModelRepetitionLoopMeasurement(
                    reader.required(entry + "backend"),
                    reader.required(entry + "workload"),
                    reader.required(entry + "modelsVersion"),
                    reader.required(entry + "modelsRevision"),
                    reader.requiredInt(entry + "detector.maxSpan"),
                    reader.requiredInt(entry + "detector.minRepeats"),
                    reader.requiredInt(entry + "detector.minLoopTokens"),
                    reader.requiredInt(entry + "generations"),
                    reader.requiredInt(entry + "stops"),
                    reader.required(entry + "report"),
                    reader.required(entry + "reportSha256"));
              } catch (IllegalArgumentException e) {
                throw new ModelJarException(
                    "Invalid repetition-loop measurement " + reader.prefix() + entry, e);
              }
            })
        .toList();
  }

  private static ModelGenerationProfile generation(Reader reader) {
    int sourceCount = reader.optionalInt("generation.source.count").orElse(0);
    List<ModelProfileSource> sources =
        IntStream.range(0, sourceCount)
            .mapToObj(
                index -> {
                  String source = "generation.source.%03d.".formatted(index);
                  return new ModelProfileSource(
                      reader.required(source + "id"),
                      reader.required(source + "kind"),
                      reader.required(source + "file"),
                      URI.create(reader.required(source + "uri")),
                      reader.required(source + "revision"),
                      reader.required(source + "sha256"));
                })
            .toList();
    Map<String, Double> sampling = new LinkedHashMap<>();
    Map<String, List<String>> provenance = new LinkedHashMap<>();
    Map<String, List<String>> conflicts = new LinkedHashMap<>();
    for (String name : List.of("temperature", "topP", "topK", "minP", "repetitionPenalty")) {
      reader
          .optional("generation.sampling." + name)
          .ifPresent(value -> sampling.put(name, parseDouble(name, value)));
    }
    Optional<Boolean> doSample =
        reader.optional("generation.sampling.doSample").map(Boolean::parseBoolean);
    for (String name :
        List.of("doSample", "temperature", "topP", "topK", "minP", "repetitionPenalty")) {
      reader
          .optional("generation.sampling." + name + ".provenance")
          .ifPresent(value -> provenance.put("sampling." + name, csv(value)));
      reader
          .optional("generation.sampling." + name + ".conflicts")
          .ifPresent(value -> conflicts.put("sampling." + name, csv(value)));
    }
    List<Integer> eosTokenIds =
        reader
            .optional("generation.eosTokenIds")
            .map(ModelProfileRegistry::csv)
            .orElse(List.of())
            .stream()
            .map(value -> parseInt("eosTokenIds", value))
            .toList();
    for (int id : eosTokenIds) {
      reader
          .optional("generation.eosTokenId." + id + ".provenance")
          .ifPresent(value -> provenance.put("eosTokenId." + id, csv(value)));
    }
    Optional<ModelGenerationProfile.ReasoningToken> open = reasoningToken(reader, "openToken");
    Optional<ModelGenerationProfile.ReasoningToken> close = reasoningToken(reader, "closeToken");
    reader
        .optional("generation.reasoning.markers.provenance")
        .ifPresent(value -> provenance.put("reasoning.markers", csv(value)));
    Optional<Boolean> thinkingDefault =
        reader.optional("generation.reasoning.thinkingDefault").map(Boolean::parseBoolean);
    reader
        .optional("generation.reasoning.thinkingDefault.provenance")
        .ifPresent(value -> provenance.put("reasoning.thinkingDefault", csv(value)));
    return new ModelGenerationProfile(
        sources,
        sampling,
        doSample,
        eosTokenIds,
        open,
        close,
        thinkingDefault,
        provenance,
        conflicts);
  }

  private static Optional<ModelGenerationProfile.ReasoningToken> reasoningToken(
      Reader reader, String name) {
    return reader
        .optional("generation.reasoning." + name)
        .map(
            text ->
                new ModelGenerationProfile.ReasoningToken(
                    text, reader.requiredInt("generation.reasoning." + name + "Id")));
  }

  private static ModelMemoryFit memoryFit(Reader reader) {
    int noteCount = reader.optionalInt("memory.note.count").orElse(0);
    List<String> notes =
        IntStream.range(0, noteCount)
            .mapToObj(index -> reader.required("memory.note.%03d".formatted(index)))
            .toList();
    List<ModelMemoryFit.KvCacheFit> kvCache =
        csv(reader.required("memory.kvTypes")).stream()
            .map(
                type -> {
                  String prefix = "memory.kv." + type + ".";
                  List<ModelMemoryFit.ContextTotal> contexts =
                      csv(reader.required(prefix + "contexts")).stream()
                          .map(
                              context ->
                                  new ModelMemoryFit.ContextTotal(
                                      parseInt("context", context),
                                      reader.requiredLong(
                                          prefix + "context." + context + ".kvBytes"),
                                      reader.requiredLong(
                                          prefix + "context." + context + ".totalBytes")))
                          .toList();
                  List<ModelMemoryFit.BudgetFit> budgets =
                      csv(reader.required(prefix + "budgets")).stream()
                          .map(
                              budget ->
                                  new ModelMemoryFit.BudgetFit(
                                      parseLong("budget", budget),
                                      reader.requiredInt(
                                          prefix + "budget." + budget + ".maxContextTokens"),
                                      reader.required(prefix + "budget." + budget + ".limitedBy")))
                          .toList();
                  return new ModelMemoryFit.KvCacheFit(
                      type,
                      reader.requiredLong(prefix + "bytesPerToken"),
                      reader.requiredLong(prefix + "slidingWindowBytesPerToken"),
                      contexts,
                      budgets);
                })
            .toList();
    return new ModelMemoryFit(
        reader.requiredLong("memory.weightBytes"),
        reader.requiredLong("memory.fixedOverheadBytes"),
        reader.requiredInt("memory.contextLength"),
        reader.optionalInt("memory.slidingWindow"),
        Boolean.parseBoolean(reader.required("memory.upperBound")),
        reader.optional("memory.recurrentStateExcluded").map(Boolean::parseBoolean).orElse(false),
        notes,
        kvCache);
  }

  private static List<String> csv(String value) {
    return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static int parseInt(String name, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid model profile " + name + ": " + value, e);
    }
  }

  private static long parseLong(String name, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid model profile " + name + ": " + value, e);
    }
  }

  private static double parseDouble(String name, String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid model profile " + name + ": " + value, e);
    }
  }

  private record Reader(Properties properties, String prefix) {
    boolean has(String keyPrefix) {
      String full = prefix + keyPrefix;
      return properties.stringPropertyNames().stream().anyMatch(name -> name.startsWith(full));
    }

    Optional<String> optional(String key) {
      return Optional.ofNullable(properties.getProperty(prefix + key))
          .map(String::trim)
          .filter(value -> !value.isEmpty());
    }

    String required(String key) {
      return optional(key)
          .orElseThrow(
              () -> new ModelJarException("Missing model profile property: " + prefix + key));
    }

    int requiredInt(String key) {
      return parseInt(key, required(key));
    }

    long requiredLong(String key) {
      return parseLong(key, required(key));
    }

    OptionalInt optionalInt(String key) {
      return optional(key)
          .map(value -> OptionalInt.of(parseInt(key, value)))
          .orElse(OptionalInt.empty());
    }
  }
}
