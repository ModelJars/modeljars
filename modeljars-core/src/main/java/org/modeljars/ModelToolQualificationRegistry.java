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

/** Loads tool-calling qualifications from versioned ModelJars resources. */
public final class ModelToolQualificationRegistry {
  public static final String RESOURCE = "META-INF/modeljars/tool-qualifications-v1.properties";
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.toolQualifications.";
  private static final String QUALIFICATION_PREFIX = "toolQualification.";

  private final Instant generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final List<ModelToolQualification> qualifications;

  private ModelToolQualificationRegistry(
      Instant generatedAt,
      String policyVersion,
      String modelsRevision,
      List<ModelToolQualification> qualifications) {
    this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    this.policyVersion = requireText(policyVersion, "policyVersion");
    this.modelsRevision = requireRevision(modelsRevision);
    this.qualifications =
        qualifications.stream()
            .sorted(Comparator.comparing(ModelToolQualification::modelId))
            .toList();
    if (this.qualifications.stream().map(ModelToolQualification::modelId).distinct().count()
        != this.qualifications.size()) {
      throw new ModelJarException("Tool qualification model IDs must be unique");
    }
  }

  public Instant generatedAt() {
    return generatedAt;
  }

  public String policyVersion() {
    return policyVersion;
  }

  public String modelsRevision() {
    return modelsRevision;
  }

  public int qualifiedModels() {
    return Math.toIntExact(
        qualifications.stream().filter(ModelToolQualification::qualified).count());
  }

  public int rejectedModels() {
    return qualifications.size() - qualifiedModels();
  }

  public List<ModelToolQualification> qualifications() {
    return qualifications;
  }

  public List<ModelToolQualification> qualified() {
    return qualifications.stream().filter(ModelToolQualification::productionUsable).toList();
  }

  public List<ModelToolQualification> qualificationsFor(ModelJarDescriptor descriptor) {
    return qualifications.stream()
        .filter(qualification -> qualification.matches(descriptor))
        .toList();
  }

  public static ModelToolQualificationRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException failure) {
      throw new ModelJarException("Unable to load ModelJars tool qualifications: " + path, failure);
    }
    return fromProperties(properties);
  }

  public static ModelToolQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  public static ModelToolQualificationRegistry fromClasspath(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ModelToolQualificationRegistry.class.getClassLoader() : classLoader;
    Map<String, SourcedQualification> merged = new LinkedHashMap<>();
    ModelToolQualificationRegistry metadata = null;
    try {
      Enumeration<URL> resources = loader.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        Properties properties = new Properties();
        try (InputStream input = resource.openStream()) {
          properties.load(input);
        }
        ModelToolQualificationRegistry registry = fromProperties(properties);
        if (metadata == null || registry.generatedAt().isAfter(metadata.generatedAt())) {
          metadata = registry;
        } else if (registry.generatedAt().equals(metadata.generatedAt())) {
          requireCompatibleMetadataAtSameInstant(metadata, registry);
        }
        for (ModelToolQualification qualification : registry.qualifications()) {
          merged.merge(
              qualification.modelId(),
              new SourcedQualification(registry.generatedAt(), qualification),
              ModelToolQualificationRegistry::newestQualification);
        }
      }
    } catch (IOException failure) {
      throw new ModelJarException("Unable to load ModelJars tool qualification resources", failure);
    }
    if (metadata == null) {
      throw new ModelJarException("No ModelJars tool qualification resources found");
    }
    return new ModelToolQualificationRegistry(
        metadata.generatedAt(),
        metadata.policyVersion(),
        metadata.modelsRevision(),
        merged.values().stream().map(SourcedQualification::qualification).toList());
  }

  public static ModelToolQualificationRegistry fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    int schemaVersion = integer(properties, ROOT_PREFIX + "schemaVersion");
    if (schemaVersion != SCHEMA_VERSION) {
      throw new ModelJarException(
          "Unsupported ModelJars tool qualification schema version: " + schemaVersion);
    }
    ModelToolQualificationRegistry registry =
        new ModelToolQualificationRegistry(
            instant(properties, ROOT_PREFIX + "generatedAt"),
            required(properties, ROOT_PREFIX + "policyVersion"),
            required(properties, ROOT_PREFIX + "modelsRevision"),
            qualificationIds(properties).stream()
                .map(id -> qualification(id, properties))
                .toList());
    if (integer(properties, ROOT_PREFIX + "qualifiedModels") != registry.qualifiedModels()
        || integer(properties, ROOT_PREFIX + "rejectedModels") != registry.rejectedModels()) {
      throw new ModelJarException("Tool qualification counts do not match resource entries");
    }
    return registry;
  }

  private static Set<String> qualificationIds(Properties properties) {
    Set<String> ids = new TreeSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith(QUALIFICATION_PREFIX)) {
        String remaining = name.substring(QUALIFICATION_PREFIX.length());
        int separator = remaining.indexOf('.');
        if (separator > 0) {
          ids.add(remaining.substring(0, separator));
        }
      }
    }
    return ids;
  }

  private static ModelToolQualification qualification(String id, Properties properties) {
    String prefix = QUALIFICATION_PREFIX + id + ".";
    String environment = prefix + "environment.";
    return new ModelToolQualification(
        id,
        required(properties, prefix + "model"),
        required(properties, prefix + "backend"),
        required(properties, prefix + "backendVersion"),
        required(properties, prefix + "workload"),
        required(properties, prefix + "promptTemplate"),
        required(properties, prefix + "artifactSha256"),
        longValue(properties, prefix + "artifactSizeBytes"),
        required(properties, prefix + "reportPath"),
        URI.create(required(properties, prefix + "reportUri")),
        required(properties, prefix + "reportSha256"),
        required(properties, prefix + "verdict"),
        bool(properties, prefix + "qualified"),
        integer(properties, prefix + "attempts"),
        integer(properties, prefix + "passed"),
        decimal(properties, prefix + "structuredOutputRate"),
        decimal(properties, prefix + "toolSelectionExactRate"),
        decimal(properties, prefix + "schemaValidityRate"),
        decimal(properties, prefix + "declaredArgumentsOnlyRate"),
        decimal(properties, prefix + "expectedArgumentAccuracy"),
        decimal(properties, prefix + "refusalAccuracy"),
        decimal(properties, prefix + "p95EndToEndMillis"),
        required(properties, prefix + "suiteSha256"),
        URI.create(required(properties, prefix + "sourceRepository")),
        required(properties, prefix + "sourceRevision"),
        required(properties, prefix + "sourcePath"),
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
        "Conflicting tool qualification model ID at the same generation instant: "
            + first.qualification().modelId());
  }

  private static void requireCompatibleMetadataAtSameInstant(
      ModelToolQualificationRegistry first, ModelToolQualificationRegistry other) {
    if (!first.policyVersion().equals(other.policyVersion())
        || !first.modelsRevision().equals(other.modelsRevision())) {
      throw new ModelJarException(
          "Conflicting tool qualification catalog metadata at the same generation instant");
    }
  }

  private record SourcedQualification(Instant generatedAt, ModelToolQualification qualification) {}

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new ModelJarException("Missing required property: " + name);
    }
    return value.trim();
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String requireRevision(String value) {
    String revision = requireText(value, "modelsRevision");
    if (!revision.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("modelsRevision must contain 40 hexadecimal characters");
    }
    return revision;
  }

  private static int integer(Properties properties, String name) {
    try {
      return Integer.parseInt(required(properties, name));
    } catch (NumberFormatException failure) {
      throw new ModelJarException("Invalid integer property: " + name, failure);
    }
  }

  private static long longValue(Properties properties, String name) {
    try {
      return Long.parseLong(required(properties, name));
    } catch (NumberFormatException failure) {
      throw new ModelJarException("Invalid long property: " + name, failure);
    }
  }

  private static double decimal(Properties properties, String name) {
    try {
      return Double.parseDouble(required(properties, name));
    } catch (NumberFormatException failure) {
      throw new ModelJarException("Invalid decimal property: " + name, failure);
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
    } catch (DateTimeParseException failure) {
      throw new ModelJarException("Invalid instant property: " + name, failure);
    }
  }
}
