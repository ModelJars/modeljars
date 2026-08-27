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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Loads versioned, dependency-free performance profiles from ModelJars marker resources. */
public final class ModelPerformanceProfileRegistry {
  /** Classpath location of performance-profile metadata. */
  public static final String RESOURCE = "META-INF/modeljars/performance-v1.properties";

  /** Supported performance-profile schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String SCHEMA_PROPERTY = "modeljars.performance.schemaVersion";
  private static final String PROFILE_PREFIX = "profile.";

  private final List<ModelPerformanceProfile> profiles;

  private ModelPerformanceProfileRegistry(List<ModelPerformanceProfile> profiles) {
    this.profiles =
        profiles.stream().sorted(Comparator.comparing(ModelPerformanceProfile::id)).toList();
    rejectConflictingOverlaps(this.profiles);
  }

  /**
   * Returns every profile in stable profile-ID order.
   *
   * @return immutable performance profiles
   */
  public List<ModelPerformanceProfile> profiles() {
    return profiles;
  }

  /**
   * Returns profiles bound to the descriptor's exact coordinate and model SHA.
   *
   * @param descriptor model descriptor to match
   * @return profiles measured against the exact model artifact
   */
  public List<ModelPerformanceProfile> profilesFor(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return profiles.stream()
        .filter(profile -> profile.modelAlias().equals(descriptor.alias()))
        .filter(profile -> profile.markerCoordinate().equals(descriptor.markerCoordinate()))
        .filter(profile -> descriptor.sha256().filter(profile.artifactSha256()::equals).isPresent())
        .toList();
  }

  /**
   * Returns profiles whose artifact, backend, and complete runtime selector match.
   *
   * @param descriptor model descriptor to match
   * @param backend selected inference backend
   * @param runtime normalized properties of the active runtime
   * @return applicable performance profiles
   */
  public List<ModelPerformanceProfile> matching(
      ModelJarDescriptor descriptor, String backend, Map<String, String> runtime) {
    return profiles.stream()
        .filter(profile -> profile.matches(descriptor, backend, runtime))
        .toList();
  }

  /**
   * Loads performance profiles from a properties file.
   *
   * @param path performance-profile properties file
   * @return parsed profile registry
   */
  public static ModelPerformanceProfileRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars performance profiles: " + path, e);
    }
    return fromProperties(properties);
  }

  /**
   * Loads profiles visible to the current thread context class loader.
   *
   * @return combined classpath profile registry
   */
  public static ModelPerformanceProfileRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads profiles visible to a class loader.
   *
   * @param classLoader class loader to inspect, or {@code null} to use this class's loader
   * @return combined classpath profile registry
   */
  public static ModelPerformanceProfileRegistry fromClasspath(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ModelPerformanceProfileRegistry.class.getClassLoader() : classLoader;
    Map<String, ModelPerformanceProfile> profiles = new LinkedHashMap<>();
    try {
      Enumeration<URL> resources = loader.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        Properties properties = new Properties();
        try (InputStream input = resource.openStream()) {
          properties.load(input);
        }
        for (ModelPerformanceProfile profile : fromProperties(properties).profiles()) {
          ModelPerformanceProfile previous = profiles.putIfAbsent(profile.id(), profile);
          if (previous != null && !previous.equals(profile)) {
            throw new ModelJarException("Conflicting performance profile ID: " + profile.id());
          }
        }
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars performance profile resources", e);
    }
    return new ModelPerformanceProfileRegistry(List.copyOf(profiles.values()));
  }

  /**
   * Parses performance profiles from their versioned properties representation.
   *
   * @param properties profile properties
   * @return parsed profile registry
   */
  public static ModelPerformanceProfileRegistry fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    int schemaVersion = parseInt(SCHEMA_PROPERTY, required(properties, SCHEMA_PROPERTY));
    if (schemaVersion != SCHEMA_VERSION) {
      throw new ModelJarException(
          "Unsupported ModelJars performance schema version: " + schemaVersion);
    }
    List<ModelPerformanceProfile> profiles =
        profileIds(properties).stream().map(id -> profile(id, properties)).toList();
    return new ModelPerformanceProfileRegistry(profiles);
  }

  private static void rejectConflictingOverlaps(List<ModelPerformanceProfile> profiles) {
    for (int firstIndex = 0; firstIndex < profiles.size(); firstIndex++) {
      ModelPerformanceProfile first = profiles.get(firstIndex);
      for (int secondIndex = firstIndex + 1; secondIndex < profiles.size(); secondIndex++) {
        ModelPerformanceProfile second = profiles.get(secondIndex);
        if (!sameExecution(first, second) || !selectorsOverlap(first, second)) {
          continue;
        }
        first
            .recommendations()
            .forEach(
                (name, value) -> {
                  String other = second.recommendations().get(name);
                  if (other != null && !value.equals(other)) {
                    throw new ModelJarException(
                        "Conflicting performance profile recommendation."
                            + name
                            + " for overlapping profiles "
                            + first.id()
                            + " and "
                            + second.id());
                  }
                });
      }
    }
  }

  private static boolean sameExecution(
      ModelPerformanceProfile first, ModelPerformanceProfile second) {
    return first.modelAlias().equals(second.modelAlias())
        && first.markerCoordinate().equals(second.markerCoordinate())
        && first.artifactSha256().equals(second.artifactSha256())
        && first.backend().equals(second.backend());
  }

  private static boolean selectorsOverlap(
      ModelPerformanceProfile first, ModelPerformanceProfile second) {
    return first.runtimeSelector().entrySet().stream()
        .allMatch(
            selector -> {
              String other = second.runtimeSelector().get(selector.getKey());
              return other == null || selector.getValue().equalsIgnoreCase(other);
            });
  }

  private static Set<String> profileIds(Properties properties) {
    Set<String> ids = new TreeSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith(PROFILE_PREFIX)) {
        continue;
      }
      String remaining = name.substring(PROFILE_PREFIX.length());
      int separator = remaining.indexOf('.');
      if (separator > 0) {
        ids.add(remaining.substring(0, separator));
      }
    }
    return ids;
  }

  private static ModelPerformanceProfile profile(String id, Properties properties) {
    String prefix = PROFILE_PREFIX + id + ".";
    String evidencePrefix = prefix + "evidence.";
    return new ModelPerformanceProfile(
        id,
        required(properties, prefix + "modelAlias"),
        ModelJarCoordinate.parse(required(properties, prefix + "markerCoordinate")),
        required(properties, prefix + "artifactSha256"),
        required(properties, prefix + "backend"),
        descendants(properties, prefix + "selector."),
        descendants(properties, prefix + "recommendation."),
        javaLaunch(properties, prefix),
        new PerformanceEvidence(
            required(properties, evidencePrefix + "benchmarkId"),
            parseInstant(
                evidencePrefix + "measuredAt", required(properties, evidencePrefix + "measuredAt")),
            required(properties, evidencePrefix + "baseline"),
            required(properties, evidencePrefix + "candidate"),
            parseInt(evidencePrefix + "warmups", required(properties, evidencePrefix + "warmups")),
            parseInt(evidencePrefix + "trials", required(properties, evidencePrefix + "trials")),
            parseInt(
                evidencePrefix + "generatedTokens",
                required(properties, evidencePrefix + "generatedTokens")),
            parseBoolean(
                evidencePrefix + "outputHashesMatch",
                required(properties, evidencePrefix + "outputHashesMatch")),
            metrics(properties, evidencePrefix + "baseline.metric."),
            metrics(properties, evidencePrefix + "candidate.metric."),
            descendants(properties, evidencePrefix + "control.")));
  }

  private static Optional<JavaLaunchProfile> javaLaunch(Properties properties, String prefix) {
    String launchPrefix = prefix + "launch.";
    String runtime = properties.getProperty(launchPrefix + "runtime");
    String javaFeature = properties.getProperty(launchPrefix + "javaFeature");
    List<String> arguments = indexedValues(properties, launchPrefix + "jvmArgument.");
    if (runtime == null && javaFeature == null && arguments.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new JavaLaunchProfile(
            required(properties, launchPrefix + "runtime"),
            parseInt(
                launchPrefix + "javaFeature", required(properties, launchPrefix + "javaFeature")),
            arguments));
  }

  private static List<String> indexedValues(Properties properties, String prefix) {
    List<String> names =
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith(prefix))
            .sorted()
            .toList();
    for (int index = 0; index < names.size(); index++) {
      String expected = prefix + String.format("%03d", index);
      if (!names.get(index).equals(expected)) {
        throw new ModelJarException("Expected indexed property " + expected);
      }
    }
    return names.stream().map(properties::getProperty).toList();
  }

  private static Map<String, String> descendants(Properties properties, String prefix) {
    Map<String, String> values = new LinkedHashMap<>();
    properties.stringPropertyNames().stream()
        .filter(name -> name.startsWith(prefix))
        .sorted()
        .forEach(name -> values.put(name.substring(prefix.length()), properties.getProperty(name)));
    return Map.copyOf(values);
  }

  private static Map<String, Double> metrics(Properties properties, String prefix) {
    Map<String, Double> values = new LinkedHashMap<>();
    descendants(properties, prefix)
        .forEach((name, value) -> values.put(name, parseDouble(prefix + name, value)));
    return Map.copyOf(values);
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new ModelJarException("Missing required property: " + name);
    }
    return value.trim();
  }

  private static int parseInt(String name, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid integer property " + name + ": " + value, e);
    }
  }

  private static double parseDouble(String name, String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid decimal property " + name + ": " + value, e);
    }
  }

  private static boolean parseBoolean(String name, String value) {
    if (value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    throw new ModelJarException("Invalid boolean property " + name + ": " + value);
  }

  private static Instant parseInstant(String name, String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new ModelJarException("Invalid instant property " + name + ": " + value, e);
    }
  }
}
