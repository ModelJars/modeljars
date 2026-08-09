package org.modeljars;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

/** Loads production RAG qualifications from versioned ModelJars resources. */
public final class ModelRagQualificationRegistry {
  /** Classpath location of RAG qualification metadata. */
  public static final String RESOURCE = "META-INF/modeljars/qualifications-v1.properties";

  /** Supported RAG qualification schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.qualifications.";
  private static final String QUALIFICATION_PREFIX = "qualification.";

  private final Instant generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final int targetQualifiedModels;
  private final List<ModelRagQualification> qualifications;

  private ModelRagQualificationRegistry(
      Instant generatedAt,
      String policyVersion,
      String modelsRevision,
      int targetQualifiedModels,
      List<ModelRagQualification> qualifications) {
    this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    this.policyVersion = requireText(policyVersion, "policyVersion");
    this.modelsRevision = requireRevision(modelsRevision);
    if (targetQualifiedModels < 1) {
      throw new IllegalArgumentException("targetQualifiedModels must be positive");
    }
    this.targetQualifiedModels = targetQualifiedModels;
    this.qualifications =
        qualifications.stream()
            .sorted(Comparator.comparing(ModelRagQualification::modelId))
            .toList();
    if (this.qualifications.stream().map(ModelRagQualification::modelId).distinct().count()
        != this.qualifications.size()) {
      throw new ModelJarException("Qualification model IDs must be unique");
    }
  }

  /**
   * Returns when this qualification catalog was generated.
   *
   * @return catalog generation timestamp
   */
  public Instant generatedAt() {
    return generatedAt;
  }

  /**
   * Returns the production policy version applied by the qualification run.
   *
   * @return qualification policy version
   */
  public String policyVersion() {
    return policyVersion;
  }

  /**
   * Returns the Models source revision used by the qualification run.
   *
   * @return 40-character Models Git commit
   */
  public String modelsRevision() {
    return modelsRevision;
  }

  /**
   * Returns the catalog's declared qualification target.
   *
   * @return target qualified-model count
   */
  public int targetQualifiedModels() {
    return targetQualifiedModels;
  }

  /**
   * Returns the number of artifacts that passed the policy.
   *
   * @return qualified artifact count
   */
  public int qualifiedModels() {
    return Math.toIntExact(qualifications.stream().filter(ModelRagQualification::qualified).count());
  }

  /**
   * Returns the number of evaluated artifacts that failed the policy.
   *
   * @return rejected artifact count
   */
  public int rejectedModels() {
    return qualifications.size() - qualifiedModels();
  }

  /**
   * Returns all evaluated artifacts in stable model-ID order.
   *
   * @return immutable qualification entries
   */
  public List<ModelRagQualification> qualifications() {
    return qualifications;
  }

  /**
   * Returns only artifacts that passed the production qualification policy.
   *
   * @return production-usable qualification entries
   */
  public List<ModelRagQualification> qualified() {
    return qualifications.stream().filter(ModelRagQualification::productionUsable).toList();
  }

  /**
   * Returns qualifications bound to the descriptor's exact alias, digest, and backend.
   *
   * @param descriptor model descriptor to match
   * @return qualifications for the exact model artifact
   */
  public List<ModelRagQualification> qualificationsFor(ModelJarDescriptor descriptor) {
    return qualifications.stream()
        .filter(qualification -> qualification.matches(descriptor))
        .toList();
  }

  /**
   * Loads RAG qualifications from a properties file.
   *
   * @param path qualification properties file
   * @return parsed qualification registry
   */
  public static ModelRagQualificationRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars RAG qualifications: " + path, e);
    }
    return fromProperties(properties);
  }

  /**
   * Loads qualifications visible to the current thread context class loader.
   *
   * @return combined classpath qualification registry
   */
  public static ModelRagQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads qualifications visible to a class loader.
   *
   * @param classLoader class loader to inspect, or {@code null} to use this class's loader
   * @return combined classpath qualification registry
   */
  public static ModelRagQualificationRegistry fromClasspath(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ModelRagQualificationRegistry.class.getClassLoader() : classLoader;
    Map<String, SourcedQualification> qualifications = new LinkedHashMap<>();
    ModelRagQualificationRegistry metadata = null;
    try {
      Enumeration<URL> resources = loader.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        Properties properties = new Properties();
        try (InputStream input = resource.openStream()) {
          properties.load(input);
        }
        ModelRagQualificationRegistry registry = fromProperties(properties);
        if (metadata == null || registry.generatedAt().isAfter(metadata.generatedAt())) {
          metadata = registry;
        } else if (registry.generatedAt().equals(metadata.generatedAt())) {
          requireCompatibleMetadataAtSameInstant(metadata, registry);
        }
        for (ModelRagQualification qualification : registry.qualifications()) {
          qualifications.merge(
              qualification.modelId(),
              new SourcedQualification(registry.generatedAt(), qualification),
              ModelRagQualificationRegistry::newestQualification);
        }
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars RAG qualification resources", e);
    }
    if (metadata == null) {
      throw new ModelJarException("No ModelJars RAG qualification resources found");
    }
    return new ModelRagQualificationRegistry(
        metadata.generatedAt(),
        metadata.policyVersion(),
        metadata.modelsRevision(),
        metadata.targetQualifiedModels(),
        qualifications.values().stream().map(SourcedQualification::qualification).toList());
  }

  /**
   * Parses RAG qualifications from their versioned properties representation.
   *
   * @param properties qualification properties
   * @return parsed qualification registry
   */
  public static ModelRagQualificationRegistry fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    int schemaVersion = integer(properties, ROOT_PREFIX + "schemaVersion");
    if (schemaVersion != SCHEMA_VERSION) {
      throw new ModelJarException(
          "Unsupported ModelJars RAG qualification schema version: " + schemaVersion);
    }
    List<ModelRagQualification> qualifications =
        qualificationIds(properties).stream()
            .map(id -> qualification(id, properties))
            .toList();
    ModelRagQualificationRegistry registry =
        new ModelRagQualificationRegistry(
            instant(properties, ROOT_PREFIX + "generatedAt"),
            required(properties, ROOT_PREFIX + "policyVersion"),
            required(properties, ROOT_PREFIX + "modelsRevision"),
            integer(properties, ROOT_PREFIX + "targetQualifiedModels"),
            qualifications);
    int declaredQualified = integer(properties, ROOT_PREFIX + "qualifiedModels");
    int declaredRejected = integer(properties, ROOT_PREFIX + "rejectedModels");
    if (declaredQualified != registry.qualifiedModels()
        || declaredRejected != registry.rejectedModels()) {
      throw new ModelJarException("RAG qualification counts do not match resource entries");
    }
    return registry;
  }

  private static Set<String> qualificationIds(Properties properties) {
    Set<String> ids = new TreeSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith(QUALIFICATION_PREFIX)) {
        continue;
      }
      String remaining = name.substring(QUALIFICATION_PREFIX.length());
      int separator = remaining.indexOf('.');
      if (separator > 0) {
        ids.add(remaining.substring(0, separator));
      }
    }
    return ids;
  }

  private static ModelRagQualification qualification(String id, Properties properties) {
    String prefix = QUALIFICATION_PREFIX + id + ".";
    String environment = prefix + "environment.";
    return new ModelRagQualification(
        id,
        required(properties, prefix + "model"),
        required(properties, prefix + "backend"),
        required(properties, prefix + "backendVersion"),
        required(properties, prefix + "workload"),
        required(properties, prefix + "corpusSha256"),
        required(properties, prefix + "promptTemplate"),
        required(properties, prefix + "groundingPolicy"),
        required(properties, prefix + "artifactSha256"),
        longValue(properties, prefix + "artifactSizeBytes"),
        required(properties, prefix + "reportPath"),
        URI.create(required(properties, prefix + "reportUri")),
        required(properties, prefix + "reportSha256"),
        required(properties, prefix + "performanceTier"),
        required(properties, prefix + "verdict"),
        bool(properties, prefix + "qualified"),
        integer(properties, prefix + "attempts"),
        decimal(properties, prefix + "p95RetrievalMillis"),
        decimal(properties, prefix + "p95TtftMillis"),
        decimal(properties, prefix + "p95TpotMillis"),
        decimal(properties, prefix + "p95EndToEndMillis"),
        decimal(properties, prefix + "p50PrefillTokensPerSecond"),
        decimal(properties, prefix + "p50DecodeTokensPerSecond"),
        longValue(properties, prefix + "peakRssBytes"),
        decimal(properties, prefix + "correctAnswerRate"),
        decimal(properties, prefix + "rawCorrectAnswerRate"),
        decimal(properties, prefix + "abstentionAccuracy"),
        decimal(properties, prefix + "modelAnswerRate"),
        decimal(properties, prefix + "modelAnswerCorrectRate"),
        decimal(properties, prefix + "extractiveFallbackRate"),
        new ModelQualificationEnvironment(
            required(properties, environment + "hostname"),
            required(properties, environment + "osName"),
            required(properties, environment + "osVersion"),
            required(properties, environment + "architecture"),
            required(properties, environment + "cpuModel"),
            integer(properties, environment + "availableProcessors"),
            longValue(properties, environment + "totalMemoryBytes"),
            longValue(properties, environment + "maxHeapBytes"),
            required(properties, environment + "javaVersion"),
            required(properties, environment + "javaVendor"),
            required(properties, environment + "vmName")));
  }

  private static SourcedQualification newestQualification(
      SourcedQualification first, SourcedQualification other) {
    if (first.qualification().equals(other.qualification())) {
      return first.generatedAt().isBefore(other.generatedAt()) ? other : first;
    }
    int recency = first.generatedAt().compareTo(other.generatedAt());
    if (recency < 0) {
      return other;
    }
    if (recency > 0) {
      return first;
    }
    throw new ModelJarException(
        "Conflicting RAG qualification model ID at the same generation instant: "
            + first.qualification().modelId());
  }

  private static void requireCompatibleMetadataAtSameInstant(
      ModelRagQualificationRegistry first, ModelRagQualificationRegistry other) {
    if (!first.policyVersion().equals(other.policyVersion())
        || !first.modelsRevision().equals(other.modelsRevision())
        || first.targetQualifiedModels() != other.targetQualifiedModels()) {
      throw new ModelJarException(
          "Conflicting RAG qualification catalog metadata at the same generation instant");
    }
  }

  private record SourcedQualification(
      Instant generatedAt, ModelRagQualification qualification) {}

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new ModelJarException("Missing required property: " + name);
    }
    return value.trim();
  }

  private static int integer(Properties properties, String name) {
    try {
      return Integer.parseInt(required(properties, name));
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid integer property: " + name, e);
    }
  }

  private static long longValue(Properties properties, String name) {
    try {
      return Long.parseLong(required(properties, name));
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid long property: " + name, e);
    }
  }

  private static double decimal(Properties properties, String name) {
    try {
      return Double.parseDouble(required(properties, name));
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid decimal property: " + name, e);
    }
  }

  private static boolean bool(Properties properties, String name) {
    String value = required(properties, name);
    if (value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    throw new ModelJarException("Invalid boolean property: " + name);
  }

  private static Instant instant(Properties properties, String name) {
    try {
      return Instant.parse(required(properties, name));
    } catch (DateTimeParseException e) {
      throw new ModelJarException("Invalid instant property: " + name, e);
    }
  }

  private static String requireRevision(String value) {
    String revision = requireText(value, "modelsRevision").toLowerCase(java.util.Locale.ROOT);
    if (!revision.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("modelsRevision must be a 40-character Git commit");
    }
    return revision;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
