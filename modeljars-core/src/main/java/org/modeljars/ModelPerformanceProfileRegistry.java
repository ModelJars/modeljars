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
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Loads versioned, dependency-free performance profiles from ModelJars marker resources. */
public final class ModelPerformanceProfileRegistry {
  public static final String RESOURCE = "META-INF/modeljars/performance-v1.properties";
  public static final int SCHEMA_VERSION = 1;

  private static final String SCHEMA_PROPERTY = "modeljars.performance.schemaVersion";
  private static final String PROFILE_PREFIX = "profile.";

  private final List<ModelPerformanceProfile> profiles;

  private ModelPerformanceProfileRegistry(List<ModelPerformanceProfile> profiles) {
    this.profiles =
        profiles.stream().sorted(Comparator.comparing(ModelPerformanceProfile::id)).toList();
  }

  /** Returns every profile in stable profile-ID order. */
  public List<ModelPerformanceProfile> profiles() {
    return profiles;
  }

  /** Returns profiles bound to the descriptor's exact coordinate and model SHA. */
  public List<ModelPerformanceProfile> profilesFor(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return profiles.stream()
        .filter(profile -> profile.modelAlias().equals(descriptor.alias()))
        .filter(profile -> profile.markerCoordinate().equals(descriptor.markerCoordinate()))
        .filter(profile -> descriptor.sha256().filter(profile.artifactSha256()::equals).isPresent())
        .toList();
  }

  /** Returns profiles whose artifact, backend, and complete runtime selector match. */
  public List<ModelPerformanceProfile> matching(
      ModelJarDescriptor descriptor, String backend, Map<String, String> runtime) {
    return profiles.stream()
        .filter(profile -> profile.matches(descriptor, backend, runtime))
        .toList();
  }

  public static ModelPerformanceProfileRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars performance profiles: " + path, e);
    }
    return fromProperties(properties);
  }

  public static ModelPerformanceProfileRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

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
        new PerformanceEvidence(
            required(properties, evidencePrefix + "benchmarkId"),
            parseInstant(
                evidencePrefix + "measuredAt",
                required(properties, evidencePrefix + "measuredAt")),
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
