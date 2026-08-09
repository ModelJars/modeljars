/*
 * Copyright 2026 Integrallis Software, LLC
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
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Loads embedding equivalence evidence from versioned ModelJars resources.
 *
 * <p>The counterpart to {@link ModelRagQualificationRegistry}. A marker for an embedding artifact
 * carries this instead of RAG evidence, because the two policies answer different questions.
 */
public final class ModelEmbeddingQualificationRegistry {

  /** Classpath location of embedding qualification metadata. */
  public static final String RESOURCE = "META-INF/modeljars/embedding-qualifications-v1.properties";

  /** Supported embedding qualification schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.embeddingQualifications.";
  private static final String ENTRY_PREFIX = "embeddingQualification.";

  private final String generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final List<Entry> entries;

  /**
   * Evidence that one exact artifact is reproduced by a backend.
   *
   * @param modelId catalog alias of the qualified model
   * @param model display name and variant
   * @param backend normalized execution backend identifier
   * @param artifactSha256 SHA-256 digest of the qualified artifact
   * @param qualified whether the artifact met the equivalence policy
   * @param probes number of probe texts compared against the oracle
   * @param embeddingDimension width of the vectors the model produces
   * @param pooling how per-position states were reduced to one vector
   * @param normalized whether vectors were scaled to unit length
   * @param oracleBackend reference implementation compared against
   * @param oracleVersion exact pinned build of that implementation
   * @param minimumOracleCosine lowest cosine agreement across the probe set
   */
  public record Entry(
      String modelId,
      String model,
      String backend,
      String artifactSha256,
      boolean qualified,
      int probes,
      int embeddingDimension,
      String pooling,
      boolean normalized,
      String oracleBackend,
      String oracleVersion,
      double minimumOracleCosine) {}

  private ModelEmbeddingQualificationRegistry(
      String generatedAt, String policyVersion, String modelsRevision, List<Entry> entries) {
    this.generatedAt = generatedAt;
    this.policyVersion = policyVersion;
    this.modelsRevision = modelsRevision;
    this.entries = List.copyOf(entries);
  }

  /**
   * Loads and merges every embedding qualification resource on the classpath.
   *
   * @return the merged registry, empty when no resource is present
   */
  public static ModelEmbeddingQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads and merges every embedding qualification resource visible to a class loader.
   *
   * @param loader class loader to scan
   * @return the merged registry, empty when no resource is present
   */
  public static ModelEmbeddingQualificationRegistry fromClasspath(ClassLoader loader) {
    ClassLoader resolved =
        loader == null ? ModelEmbeddingQualificationRegistry.class.getClassLoader() : loader;
    Map<String, SourcedEntry> merged = new LinkedHashMap<>();
    ModelEmbeddingQualificationRegistry metadata = null;
    try {
      Enumeration<java.net.URL> resources = resolved.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          ModelEmbeddingQualificationRegistry registry = parse(stream);
          Instant generatedAt = timestamp(registry.generatedAt);
          if (metadata == null
              || generatedAt.isAfter(timestamp(metadata.generatedAt))) {
            metadata = registry;
          } else if (generatedAt.equals(timestamp(metadata.generatedAt))) {
            requireCompatibleMetadataAtSameInstant(metadata, registry);
          }
          for (Entry entry : registry.entries) {
            merged.merge(
                entry.modelId(),
                new SourcedEntry(generatedAt, entry),
                ModelEmbeddingQualificationRegistry::newestEntry);
          }
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, failure);
    }
    List<Entry> entries =
        merged.values().stream()
            .map(SourcedEntry::entry)
            .sorted(Comparator.comparing(Entry::modelId))
            .toList();
    if (metadata == null) {
      return new ModelEmbeddingQualificationRegistry("", "", "", entries);
    }
    return new ModelEmbeddingQualificationRegistry(
        metadata.generatedAt, metadata.policyVersion, metadata.modelsRevision, entries);
  }

  private static SourcedEntry newestEntry(SourcedEntry first, SourcedEntry other) {
    if (first.entry().equals(other.entry())) {
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
        "Conflicting embedding qualification model ID at the same generation instant: "
            + first.entry().modelId());
  }

  private static void requireCompatibleMetadataAtSameInstant(
      ModelEmbeddingQualificationRegistry first,
      ModelEmbeddingQualificationRegistry other) {
    if (!first.policyVersion.equals(other.policyVersion)
        || !first.modelsRevision.equals(other.modelsRevision)) {
      throw new ModelJarException(
          "Conflicting embedding qualification metadata at the same generation instant");
    }
  }

  private static Instant timestamp(String value) {
    if (value == null || value.isBlank()) {
      return Instant.MIN;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new ModelJarException("Invalid embedding qualification generatedAt: " + value, malformed);
    }
  }

  private record SourcedEntry(Instant generatedAt, Entry entry) {}

  /**
   * Parses one embedding qualification properties stream.
   *
   * @param stream properties content
   * @return the parsed registry
   * @throws IOException if the stream cannot be read
   */
  public static ModelEmbeddingQualificationRegistry parse(InputStream stream) throws IOException {
    Properties properties = new Properties();
    properties.load(Objects.requireNonNull(stream, "stream"));

    String schemaVersion = properties.getProperty(ROOT_PREFIX + "schemaVersion", "");
    if (!String.valueOf(SCHEMA_VERSION).equals(schemaVersion)) {
      throw new ModelJarException(
          "Embedding qualification metadata must use schemaVersion " + SCHEMA_VERSION);
    }

    TreeSet<String> modelIds = new TreeSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith(ENTRY_PREFIX)) {
        int end = name.indexOf('.', ENTRY_PREFIX.length());
        if (end > ENTRY_PREFIX.length()) {
          modelIds.add(name.substring(ENTRY_PREFIX.length(), end));
        }
      }
    }

    List<Entry> entries = new ArrayList<>(modelIds.size());
    for (String modelId : modelIds) {
      String prefix = ENTRY_PREFIX + modelId + ".";
      entries.add(
          new Entry(
              modelId,
              text(properties, prefix + "model"),
              text(properties, prefix + "backend"),
              text(properties, prefix + "artifactSha256").toLowerCase(Locale.ROOT),
              Boolean.parseBoolean(properties.getProperty(prefix + "qualified", "false")),
              integer(properties, prefix + "probes"),
              integer(properties, prefix + "embeddingDimension"),
              text(properties, prefix + "pooling"),
              Boolean.parseBoolean(properties.getProperty(prefix + "normalized", "false")),
              text(properties, prefix + "oracleBackend"),
              text(properties, prefix + "oracleVersion"),
              decimal(properties, prefix + "minimumOracleCosine")));
    }

    return new ModelEmbeddingQualificationRegistry(
        properties.getProperty(ROOT_PREFIX + "generatedAt", ""),
        properties.getProperty(ROOT_PREFIX + "policyVersion", ""),
        properties.getProperty(ROOT_PREFIX + "modelsRevision", ""),
        entries);
  }

  /**
   * Returns every recorded entry, qualified or not.
   *
   * @return all entries, ordered by model id
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Returns only entries whose artifact met the equivalence policy.
   *
   * @return qualified entries, ordered by model id
   */
  public List<Entry> qualified() {
    return entries.stream().filter(Entry::qualified).toList();
  }

  /**
   * Finds qualified evidence bound to an exact artifact digest.
   *
   * @param artifactSha256 SHA-256 digest of a model artifact
   * @return the qualified entry for those exact bytes, when one exists
   */
  public Optional<Entry> qualificationFor(String artifactSha256) {
    if (artifactSha256 == null || artifactSha256.isBlank()) {
      return Optional.empty();
    }
    String digest = artifactSha256.toLowerCase(Locale.ROOT);
    return entries.stream()
        .filter(entry -> entry.qualified() && entry.artifactSha256().equals(digest))
        .findFirst();
  }

  /**
   * Returns the instant this evidence was generated.
   *
   * @return ISO-8601 instant, or empty when no resource was present
   */
  public String generatedAt() {
    return generatedAt;
  }

  /**
   * Returns the policy this evidence was produced under.
   *
   * @return policy version, or empty when no resource was present
   */
  public String policyVersion() {
    return policyVersion;
  }

  /**
   * Returns the Models commit the evidence reports came from.
   *
   * @return Git revision, or empty when no resource was present
   */
  public String modelsRevision() {
    return modelsRevision;
  }

  private static String text(Properties properties, String name) {
    String value = properties.getProperty(name, "");
    if (value.isBlank()) {
      throw new ModelJarException(name + " must not be blank");
    }
    return value;
  }

  private static int integer(Properties properties, String name) {
    try {
      return Integer.parseInt(text(properties, name));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException(name + " must be an integer", malformed);
    }
  }

  private static double decimal(Properties properties, String name) {
    try {
      return Double.parseDouble(text(properties, name));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException(name + " must be a number", malformed);
    }
  }
}
