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

/** Loads artifact-bound speech correctness, streaming, and latency evidence. */
public final class ModelSpeechQualificationRegistry {
  /** Classpath location of the generated speech qualification registry. */
  public static final String RESOURCE = "META-INF/modeljars/speech-qualifications-v1.properties";

  /** Current speech qualification registry schema. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.speechQualifications.";
  private static final String ENTRY_PREFIX = "speechQualification.";

  /**
   * Evidence required to expose one exact text-to-speech artifact through ModelJars.
   *
   * @param modelId catalog identifier
   * @param model human-readable model name
   * @param backend qualified Models backend
   * @param backendVersion Models version used for qualification
   * @param workload qualification workload identifier
   * @param artifactSha256 exact model artifact digest
   * @param artifactSizeBytes exact model artifact size
   * @param report immutable evidence path in the Models repository
   * @param reportSha256 exact evidence report digest
   * @param qualified whether all admission gates passed
   * @param oracleBackend independent reference implementation
   * @param oracleVersion immutable oracle revision
   * @param probes number of oracle comparison probes
   * @param minimumPcmCosine lowest measured PCM cosine similarity
   * @param minimumSignalToDifferenceDb lowest measured signal-to-difference ratio
   * @param sampleRate generated PCM sample rate
   * @param channels generated PCM channel count
   * @param streaming whether incremental synthesis was exercised
   * @param firstAudioBeforeCompletion whether streaming emitted audio before synthesis completed
   * @param trials number of controlled performance trials
   * @param p95RealTimeFactor p95 synthesis time divided by generated-audio duration
   * @param p95TimeToFirstAudioMillis p95 delay before the first audio chunk
   * @param peakRssBytes highest observed resident-set size
   */
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
      String oracleBackend,
      String oracleVersion,
      int probes,
      double minimumPcmCosine,
      double minimumSignalToDifferenceDb,
      int sampleRate,
      int channels,
      boolean streaming,
      boolean firstAudioBeforeCompletion,
      int trials,
      double p95RealTimeFactor,
      double p95TimeToFirstAudioMillis,
      long peakRssBytes) {
    /** Validates and normalizes one immutable speech qualification entry. */
    public Entry {
      modelId = requireText(modelId, "modelId");
      model = requireText(model, "model");
      backend = requireText(backend, "backend");
      backendVersion = requireText(backendVersion, "backendVersion");
      workload = requireText(workload, "workload");
      artifactSha256 = requireDigest(artifactSha256, "artifactSha256");
      report = requireText(report, "report");
      reportSha256 = requireDigest(reportSha256, "reportSha256");
      oracleBackend = requireText(oracleBackend, "oracleBackend");
      oracleVersion = requireText(oracleVersion, "oracleVersion");
      if (artifactSizeBytes < 1 || peakRssBytes < 1) {
        throw new IllegalArgumentException("speech artifact and peak RSS bytes must be positive");
      }
      if (probes < 1 || trials < 3 || sampleRate < 8_000 || channels < 1) {
        throw new IllegalArgumentException(
            "speech evidence requires probes, at least three trials, and valid PCM dimensions");
      }
      if (!finiteNonNegative(minimumPcmCosine)
          || minimumPcmCosine > 1.0
          || !Double.isFinite(minimumSignalToDifferenceDb)
          || !finiteNonNegative(p95RealTimeFactor)
          || !finiteNonNegative(p95TimeToFirstAudioMillis)) {
        throw new IllegalArgumentException("speech metrics must be finite and in range");
      }
      if (qualified
          && (minimumPcmCosine < ModelSpeechQualification.MINIMUM_PCM_COSINE
              || minimumSignalToDifferenceDb
                  < ModelSpeechQualification.MINIMUM_SIGNAL_TO_DIFFERENCE_DB
              || p95RealTimeFactor > ModelSpeechQualification.MAXIMUM_P95_REAL_TIME_FACTOR
              || p95TimeToFirstAudioMillis
                  > ModelSpeechQualification.MAXIMUM_P95_TIME_TO_FIRST_AUDIO_MILLIS
              || !streaming
              || !firstAudioBeforeCompletion)) {
        throw new IllegalArgumentException(
            "qualified speech marker must pass correctness, streaming, and latency gates");
      }
    }

    private static boolean finiteNonNegative(double value) {
      return Double.isFinite(value) && value >= 0.0;
    }
  }

  private final String generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final List<Entry> entries;

  private ModelSpeechQualificationRegistry(
      String generatedAt, String policyVersion, String modelsRevision, List<Entry> entries) {
    this.generatedAt = generatedAt;
    this.policyVersion = policyVersion;
    this.modelsRevision = modelsRevision;
    this.entries = List.copyOf(entries);
  }

  /**
   * Loads and merges all speech qualification resources visible to the context loader.
   *
   * @return merged speech qualification registry
   */
  public static ModelSpeechQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads and merges all speech qualification resources visible to a class loader.
   *
   * @param loader class loader to inspect, or {@code null} to use this class's loader
   * @return merged speech qualification registry
   */
  public static ModelSpeechQualificationRegistry fromClasspath(ClassLoader loader) {
    ClassLoader resolved =
        loader == null ? ModelSpeechQualificationRegistry.class.getClassLoader() : loader;
    Map<String, SourcedEntry> merged = new LinkedHashMap<>();
    ModelSpeechQualificationRegistry metadata = null;
    try {
      Enumeration<java.net.URL> resources = resolved.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          ModelSpeechQualificationRegistry registry = parse(stream);
          Instant generatedAt = timestamp(registry.generatedAt);
          if (metadata == null || generatedAt.isAfter(timestamp(metadata.generatedAt))) {
            metadata = registry;
          } else if (generatedAt.equals(timestamp(metadata.generatedAt))
              && (!metadata.policyVersion.equals(registry.policyVersion)
                  || !metadata.modelsRevision.equals(registry.modelsRevision))) {
            throw new ModelJarException(
                "Conflicting speech qualification metadata at the same generation instant");
          }
          for (Entry entry : registry.entries) {
            merged.merge(
                entry.modelId(),
                new SourcedEntry(generatedAt, entry),
                ModelSpeechQualificationRegistry::newestEntry);
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
        ? new ModelSpeechQualificationRegistry("", "", "", entries)
        : new ModelSpeechQualificationRegistry(
            metadata.generatedAt, metadata.policyVersion, metadata.modelsRevision, entries);
  }

  /**
   * Parses one speech qualification registry.
   *
   * @param stream registry properties stream
   * @return validated registry
   * @throws IOException when the properties cannot be read
   */
  public static ModelSpeechQualificationRegistry parse(InputStream stream) throws IOException {
    Properties properties = new Properties();
    properties.load(Objects.requireNonNull(stream, "stream"));
    if (!String.valueOf(SCHEMA_VERSION)
        .equals(properties.getProperty(ROOT_PREFIX + "schemaVersion", ""))) {
      throw new ModelJarException(
          "Speech qualification metadata must use schemaVersion " + SCHEMA_VERSION);
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
              text(properties, prefix + "artifactSha256"),
              integer64(properties, prefix + "artifactSizeBytes"),
              text(properties, prefix + "report"),
              text(properties, prefix + "reportSha256"),
              bool(properties, prefix + "qualified"),
              text(properties, prefix + "oracleBackend"),
              text(properties, prefix + "oracleVersion"),
              integer(properties, prefix + "probes"),
              decimal(properties, prefix + "minimumPcmCosine"),
              decimal(properties, prefix + "minimumSignalToDifferenceDb"),
              integer(properties, prefix + "sampleRate"),
              integer(properties, prefix + "channels"),
              bool(properties, prefix + "streaming"),
              bool(properties, prefix + "firstAudioBeforeCompletion"),
              integer(properties, prefix + "trials"),
              decimal(properties, prefix + "p95RealTimeFactor"),
              decimal(properties, prefix + "p95TimeToFirstAudioMillis"),
              integer64(properties, prefix + "peakRssBytes")));
    }
    return new ModelSpeechQualificationRegistry(
        properties.getProperty(ROOT_PREFIX + "generatedAt", ""),
        properties.getProperty(ROOT_PREFIX + "policyVersion", ""),
        properties.getProperty(ROOT_PREFIX + "modelsRevision", ""),
        entries);
  }

  /**
   * Returns every recorded speech qualification.
   *
   * @return all qualification entries, including rejections
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Returns the speech artifacts admitted to the public catalog.
   *
   * @return entries that passed all speech admission gates
   */
  public List<Entry> qualified() {
    return entries.stream().filter(Entry::qualified).toList();
  }

  /**
   * Finds qualified evidence for exact artifact bytes.
   *
   * @param artifactSha256 artifact digest
   * @return matching qualified evidence, if present
   */
  public Optional<Entry> qualificationFor(String artifactSha256) {
    String digest = requireDigest(artifactSha256, "artifactSha256");
    return qualified().stream().filter(entry -> entry.artifactSha256().equals(digest)).findFirst();
  }

  /**
   * Returns when this evidence set was generated.
   *
   * @return evidence generation timestamp
   */
  public String generatedAt() {
    return generatedAt;
  }

  /**
   * Returns the policy used to admit the entries.
   *
   * @return qualification policy identifier
   */
  public String policyVersion() {
    return policyVersion;
  }

  /**
   * Returns the Models revision used for qualification.
   *
   * @return immutable Models revision that produced the evidence
   */
  public String modelsRevision() {
    return modelsRevision;
  }

  private static SourcedEntry newestEntry(SourcedEntry first, SourcedEntry other) {
    if (first.entry().equals(other.entry())) {
      return first.generatedAt().isBefore(other.generatedAt()) ? other : first;
    }
    int recency = first.generatedAt().compareTo(other.generatedAt());
    if (recency < 0) return other;
    if (recency > 0) return first;
    throw new ModelJarException(
        "Conflicting speech qualification model ID at the same generation instant: "
            + first.entry().modelId());
  }

  private static Instant timestamp(String value) {
    if (value == null || value.isBlank()) return Instant.MIN;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new ModelJarException("Invalid speech qualification generatedAt: " + value, malformed);
    }
  }

  private static String text(Properties properties, String key) {
    return requireText(properties.getProperty(key), key);
  }

  private static int integer(Properties properties, String key) {
    try {
      return Integer.parseInt(text(properties, key));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid integer for " + key, malformed);
    }
  }

  private static long integer64(Properties properties, String key) {
    try {
      return Long.parseLong(text(properties, key));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid long for " + key, malformed);
    }
  }

  private static double decimal(Properties properties, String key) {
    try {
      return Double.parseDouble(text(properties, key));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid decimal for " + key, malformed);
    }
  }

  private static boolean bool(Properties properties, String key) {
    String value = text(properties, key);
    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
      throw new ModelJarException("Invalid boolean for " + key + ": " + value);
    }
    return Boolean.parseBoolean(value);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }

  private static String requireDigest(String value, String name) {
    String digest = requireText(value, name).toLowerCase(Locale.ROOT);
    if (!digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a SHA-256 digest");
    }
    return digest;
  }

  private record SourcedEntry(Instant generatedAt, Entry entry) {}
}
