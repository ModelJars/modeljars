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

/** Loads reranker qualification evidence from versioned ModelJars resources. */
public final class ModelRerankingQualificationRegistry {
  /** Classpath location of reranking qualification metadata. */
  public static final String RESOURCE = "META-INF/modeljars/reranking-qualifications-v1.properties";

  /** Supported reranking qualification schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.rerankingQualifications.";
  private static final String ENTRY_PREFIX = "rerankingQualification.";

  /** Runtime fields needed to select exact qualified reranker bytes. */
  public record Entry(
      String modelId,
      String model,
      String backend,
      String backendVersion,
      String workload,
      String artifactSha256,
      long artifactSizeBytes,
      String report,
      String reportSha256,
      boolean qualified,
      int pairs,
      double maximumOnnxLogitDelta,
      double maximumSameArtifactOracleLogitDelta,
      boolean topKOrderExact,
      double medianColdLoadMillis,
      double maximumPairP95Millis,
      double maximumBatchP95Millis,
      double medianBatchDocumentsPerSecond) {
    /** Rejects self-contradictory qualified marker evidence at load time. */
    public Entry {
      if (modelId == null || modelId.isBlank() || model == null || model.isBlank()) {
        throw new IllegalArgumentException("reranking model identity must not be blank");
      }
      if (backend == null
          || backend.isBlank()
          || backendVersion == null
          || backendVersion.isBlank()
          || workload == null
          || workload.isBlank()) {
        throw new IllegalArgumentException(
            "reranking backend, backendVersion, and workload must not be blank");
      }
      artifactSha256 = artifactSha256.toLowerCase(Locale.ROOT);
      if (!artifactSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("reranking artifactSha256 must be a SHA-256 digest");
      }
      if (artifactSizeBytes < 1) {
        throw new IllegalArgumentException("reranking artifactSizeBytes must be positive");
      }
      if (report == null || report.isBlank()) {
        throw new IllegalArgumentException("reranking report must not be blank");
      }
      reportSha256 = reportSha256.toLowerCase(Locale.ROOT);
      if (!reportSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("reranking reportSha256 must be a SHA-256 digest");
      }
      if (pairs < 1) {
        throw new IllegalArgumentException("reranking pairs must be positive");
      }
      if (!validMetric(maximumOnnxLogitDelta)
          || !validMetric(maximumSameArtifactOracleLogitDelta)
          || !validMetric(medianColdLoadMillis)
          || !validMetric(maximumPairP95Millis)
          || !validMetric(maximumBatchP95Millis)
          || !validMetric(medianBatchDocumentsPerSecond)) {
        throw new IllegalArgumentException("reranking metrics must be finite and non-negative");
      }
      if (qualified
          && (maximumOnnxLogitDelta > ModelRerankingQualification.MAXIMUM_ONNX_LOGIT_DELTA
              || maximumSameArtifactOracleLogitDelta
                  > ModelRerankingQualification.MAXIMUM_SAME_ARTIFACT_ORACLE_LOGIT_DELTA
              || !topKOrderExact
              || medianColdLoadMillis > ModelRerankingQualification.MAXIMUM_COLD_LOAD_MILLIS
              || maximumPairP95Millis > ModelRerankingQualification.MAXIMUM_PAIR_P95_MILLIS
              || maximumBatchP95Millis > ModelRerankingQualification.MAXIMUM_BATCH_P95_MILLIS)) {
        throw new IllegalArgumentException(
            "qualified reranking marker must pass numerical, ordering, and latency gates");
      }
    }

    private static boolean validMetric(double value) {
      return Double.isFinite(value) && value >= 0.0;
    }
  }

  private final String generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final List<Entry> entries;

  private ModelRerankingQualificationRegistry(
      String generatedAt, String policyVersion, String modelsRevision, List<Entry> entries) {
    this.generatedAt = generatedAt;
    this.policyVersion = policyVersion;
    this.modelsRevision = modelsRevision;
    this.entries = List.copyOf(entries);
  }

  /** Loads and merges every reranking qualification resource visible to the context loader. */
  public static ModelRerankingQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /** Loads and merges every reranking qualification resource visible to a class loader. */
  public static ModelRerankingQualificationRegistry fromClasspath(ClassLoader loader) {
    ClassLoader resolved =
        loader == null ? ModelRerankingQualificationRegistry.class.getClassLoader() : loader;
    Map<String, SourcedEntry> merged = new LinkedHashMap<>();
    ModelRerankingQualificationRegistry metadata = null;
    try {
      Enumeration<java.net.URL> resources = resolved.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          ModelRerankingQualificationRegistry registry = parse(stream);
          Instant generatedAt = timestamp(registry.generatedAt);
          if (metadata == null || generatedAt.isAfter(timestamp(metadata.generatedAt))) {
            metadata = registry;
          } else if (generatedAt.equals(timestamp(metadata.generatedAt))
              && (!metadata.policyVersion.equals(registry.policyVersion)
                  || !metadata.modelsRevision.equals(registry.modelsRevision))) {
            throw new ModelJarException(
                "Conflicting reranking qualification metadata at the same generation instant");
          }
          for (Entry entry : registry.entries) {
            merged.merge(
                entry.modelId(),
                new SourcedEntry(generatedAt, entry),
                ModelRerankingQualificationRegistry::newestEntry);
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
    return metadata == null
        ? new ModelRerankingQualificationRegistry("", "", "", entries)
        : new ModelRerankingQualificationRegistry(
            metadata.generatedAt, metadata.policyVersion, metadata.modelsRevision, entries);
  }

  /** Parses one reranking qualification properties stream. */
  public static ModelRerankingQualificationRegistry parse(InputStream stream) throws IOException {
    Properties properties = new Properties();
    properties.load(Objects.requireNonNull(stream, "stream"));
    if (!String.valueOf(SCHEMA_VERSION)
        .equals(properties.getProperty(ROOT_PREFIX + "schemaVersion", ""))) {
      throw new ModelJarException(
          "Reranking qualification metadata must use schemaVersion " + SCHEMA_VERSION);
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
              text(properties, prefix + "backendVersion"),
              text(properties, prefix + "workload"),
              text(properties, prefix + "artifactSha256").toLowerCase(Locale.ROOT),
              longInteger(properties, prefix + "artifactSizeBytes"),
              text(properties, prefix + "report"),
              text(properties, prefix + "reportSha256").toLowerCase(Locale.ROOT),
              Boolean.parseBoolean(properties.getProperty(prefix + "qualified", "false")),
              integer(properties, prefix + "pairs"),
              decimal(properties, prefix + "maximumOnnxLogitDelta"),
              decimal(properties, prefix + "maximumSameArtifactOracleLogitDelta"),
              Boolean.parseBoolean(properties.getProperty(prefix + "topKOrderExact", "false")),
              decimal(properties, prefix + "medianColdLoadMillis"),
              decimal(properties, prefix + "maximumPairP95Millis"),
              decimal(properties, prefix + "maximumBatchP95Millis"),
              decimal(properties, prefix + "medianBatchDocumentsPerSecond")));
    }
    return new ModelRerankingQualificationRegistry(
        properties.getProperty(ROOT_PREFIX + "generatedAt", ""),
        properties.getProperty(ROOT_PREFIX + "policyVersion", ""),
        properties.getProperty(ROOT_PREFIX + "modelsRevision", ""),
        entries);
  }

  /** Returns every recorded entry, ordered by model ID. */
  public List<Entry> entries() {
    return entries;
  }

  /** Returns only evidence admitted to production. */
  public List<Entry> qualified() {
    return entries.stream().filter(Entry::qualified).toList();
  }

  /** Finds qualified evidence bound to an exact artifact digest. */
  public Optional<Entry> qualificationFor(String artifactSha256) {
    if (artifactSha256 == null || artifactSha256.isBlank()) {
      return Optional.empty();
    }
    String digest = artifactSha256.toLowerCase(Locale.ROOT);
    return entries.stream()
        .filter(entry -> entry.qualified() && entry.artifactSha256().equals(digest))
        .findFirst();
  }

  /** Returns the ISO-8601 generation time, or empty when no resource was present. */
  public String generatedAt() {
    return generatedAt;
  }

  /** Returns the qualification policy version. */
  public String policyVersion() {
    return policyVersion;
  }

  /** Returns the Models commit that produced the evidence. */
  public String modelsRevision() {
    return modelsRevision;
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
        "Conflicting reranking qualification model ID at the same generation instant: "
            + first.entry().modelId());
  }

  private static Instant timestamp(String value) {
    if (value == null || value.isBlank()) {
      return Instant.MIN;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new ModelJarException(
          "Invalid reranking qualification generatedAt: " + value, malformed);
    }
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

  private static long longInteger(Properties properties, String name) {
    try {
      return Long.parseLong(text(properties, name));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException(name + " must be a long integer", malformed);
    }
  }

  private static double decimal(Properties properties, String name) {
    try {
      return Double.parseDouble(text(properties, name));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException(name + " must be a number", malformed);
    }
  }

  private record SourcedEntry(Instant generatedAt, Entry entry) {}
}
