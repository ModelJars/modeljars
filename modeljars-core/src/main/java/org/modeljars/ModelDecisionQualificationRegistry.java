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

/**
 * Loads typed-decision evidence from versioned ModelJars resources.
 *
 * <p>The third kind alongside {@link ModelRagQualificationRegistry} and {@link
 * ModelEmbeddingQualificationRegistry}. A marker for a decision artifact carries this instead of
 * either, because the three policies answer different questions and a RAG metric says nothing about
 * a model that emits one chosen option and a probability.
 *
 * <p>The thing a consumer must not be able to lose is the tier. An entry's accuracy transfers as a
 * property of the weights; whether its <em>confidence</em> can be thresholded does not, because
 * calibration is fitted to a distribution. {@link Entry#exportsConfidenceQuality()} is the single
 * question the catalog asks of this registry.
 */
public final class ModelDecisionQualificationRegistry {

  /** Classpath location of decision qualification metadata. */
  public static final String RESOURCE = "META-INF/modeljars/decision-qualifications-v1.properties";

  /** Supported decision qualification schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.decisionQualifications.";
  private static final String ENTRY_PREFIX = "decisionQualification.";

  private final String generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final List<Entry> entries;

  /**
   * Evidence that one exact artifact decides accurately, and how far its confidence travels.
   *
   * @param modelId catalog alias of the qualified model
   * @param model display name and variant
   * @param backend normalized execution backend identifier
   * @param artifactSha256 SHA-256 digest of the qualified artifact
   * @param tier {@code CALIBRATED_DECISION} or {@code RANKED_DECISION}
   * @param cohort stable identifier of the labelled cohort
   * @param cases number of labelled decisions scored
   * @param chanceAccuracy accuracy a uniform random chooser reaches on this cohort
   * @param accuracy fraction of decisions matching the label
   * @param calibrationError expected calibration error, NaN when unmeasurable
   * @param uncalibratedBucketsExercised cohort questions in a bucket the artifact declares
   *     uncalibrated; zero means that promise went untested
   */
  public record Entry(
      String modelId,
      String model,
      String backend,
      String artifactSha256,
      String tier,
      String cohort,
      int cases,
      double chanceAccuracy,
      double accuracy,
      double calibrationError,
      int uncalibratedBucketsExercised) {

    /** Whether a model with this evidence may publish a confidence quality callers can gate on. */
    public boolean exportsConfidenceQuality() {
      return ModelDecisionQualification.TIER_CALIBRATED.equals(tier);
    }

    /**
     * Whether the cohort ever exercised a bucket the artifact declares uncalibrated.
     *
     * <p>False means untested, not upheld.
     */
    public boolean testedUncalibratedReporting() {
      return uncalibratedBucketsExercised > 0;
    }

    /** How far above a uniform random chooser this accuracy sits, as a multiple. */
    public double chanceMultiple() {
      return chanceAccuracy <= 0.0 ? Double.NaN : accuracy / chanceAccuracy;
    }
  }

  private ModelDecisionQualificationRegistry(
      String generatedAt, String policyVersion, String modelsRevision, List<Entry> entries) {
    this.generatedAt = generatedAt;
    this.policyVersion = policyVersion;
    this.modelsRevision = modelsRevision;
    this.entries = List.copyOf(entries);
  }

  /**
   * Loads and merges every decision qualification resource on the classpath.
   *
   * @return the merged registry, empty when no resource is present
   */
  public static ModelDecisionQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads and merges every decision qualification resource visible to a class loader.
   *
   * @param loader class loader to scan
   * @return the merged registry, empty when no resource is present
   */
  public static ModelDecisionQualificationRegistry fromClasspath(ClassLoader loader) {
    ClassLoader resolved =
        loader == null ? ModelDecisionQualificationRegistry.class.getClassLoader() : loader;
    Map<String, SourcedEntry> merged = new LinkedHashMap<>();
    ModelDecisionQualificationRegistry metadata = null;
    try {
      Enumeration<java.net.URL> resources = resolved.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          ModelDecisionQualificationRegistry registry = parse(stream);
          Instant generatedAt = timestamp(registry.generatedAt);
          if (metadata == null || generatedAt.isAfter(timestamp(metadata.generatedAt))) {
            metadata = registry;
          } else if (generatedAt.equals(timestamp(metadata.generatedAt))) {
            requireCompatibleMetadataAtSameInstant(metadata, registry);
          }
          for (Entry entry : registry.entries) {
            merged.merge(
                entry.modelId(),
                new SourcedEntry(generatedAt, entry),
                ModelDecisionQualificationRegistry::newestEntry);
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
      return new ModelDecisionQualificationRegistry("", "", "", entries);
    }
    return new ModelDecisionQualificationRegistry(
        metadata.generatedAt, metadata.policyVersion, metadata.modelsRevision, entries);
  }

  /**
   * Resolves which of two records for one model id wins.
   *
   * <p>Newest generation, and a hard failure on a genuine conflict at the same instant. Two
   * different verdicts for one model with no way to order them is ambiguity the catalog must not
   * paper over by choosing arbitrarily.
   */
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
        "Conflicting decision qualification model ID at the same generation instant: "
            + first.entry().modelId());
  }

  private static void requireCompatibleMetadataAtSameInstant(
      ModelDecisionQualificationRegistry first, ModelDecisionQualificationRegistry other) {
    if (!first.policyVersion.equals(other.policyVersion)
        || !first.modelsRevision.equals(other.modelsRevision)) {
      throw new ModelJarException(
          "Conflicting decision qualification metadata at the same generation instant");
    }
  }

  private static Instant timestamp(String value) {
    if (value == null || value.isBlank()) {
      return Instant.MIN;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new ModelJarException("Invalid decision qualification generatedAt: " + value, malformed);
    }
  }

  private record SourcedEntry(Instant generatedAt, Entry entry) {}

  /**
   * Parses one decision qualification properties stream.
   *
   * @param stream properties content
   * @return the parsed registry
   * @throws IOException if the stream cannot be read
   */
  public static ModelDecisionQualificationRegistry parse(InputStream stream) throws IOException {
    Properties properties = new Properties();
    properties.load(Objects.requireNonNull(stream, "stream"));

    String schemaVersion = properties.getProperty(ROOT_PREFIX + "schemaVersion", "");
    if (!String.valueOf(SCHEMA_VERSION).equals(schemaVersion)) {
      throw new ModelJarException(
          "Decision qualification metadata must use schemaVersion " + SCHEMA_VERSION);
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
      String tier = text(properties, prefix + "tier");
      if (!ModelDecisionQualification.TIER_CALIBRATED.equals(tier)
          && !ModelDecisionQualification.TIER_RANKED.equals(tier)) {
        // Refused at load, not at use. A tier this code cannot interpret would otherwise reach a
        // consumer as though its meaning were known.
        throw new ModelJarException("Unknown decision tier for " + modelId + ": " + tier);
      }
      entries.add(
          new Entry(
              modelId,
              text(properties, prefix + "model"),
              text(properties, prefix + "backend"),
              text(properties, prefix + "artifactSha256").toLowerCase(Locale.ROOT),
              tier,
              text(properties, prefix + "cohort"),
              integer(properties, prefix + "cases"),
              decimal(properties, prefix + "chanceAccuracy"),
              decimal(properties, prefix + "accuracy"),
              optionalDecimal(properties, prefix + "calibrationError"),
              integer(properties, prefix + "uncalibratedBucketsExercised")));
    }

    return new ModelDecisionQualificationRegistry(
        properties.getProperty(ROOT_PREFIX + "generatedAt", ""),
        properties.getProperty(ROOT_PREFIX + "policyVersion", ""),
        properties.getProperty(ROOT_PREFIX + "modelsRevision", ""),
        entries);
  }

  /**
   * Returns every recorded entry.
   *
   * @return all entries, ordered by model id
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Finds evidence bound to an exact artifact digest.
   *
   * <p>Matched on the digest and not only the id, so a record cannot follow a re-quantized or
   * re-converted artifact that nobody scored.
   *
   * @param descriptor the model to look up
   * @return the entry when one matches this exact artifact
   */
  public Optional<Entry> qualificationFor(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Optional<String> digest = descriptor.sha256().map(value -> value.toLowerCase(Locale.ROOT));
    if (digest.isEmpty()) {
      return Optional.empty();
    }
    return entries.stream()
        .filter(entry -> entry.modelId().equals(descriptor.alias()))
        .filter(entry -> entry.artifactSha256().equals(digest.orElseThrow()))
        .findFirst();
  }

  /** When this metadata was generated, or empty when unrecorded. */
  public String generatedAt() {
    return generatedAt;
  }

  /** The policy version these verdicts were produced under. */
  public String policyVersion() {
    return policyVersion;
  }

  /** The models revision these verdicts were measured against. */
  public String modelsRevision() {
    return modelsRevision;
  }

  private static String text(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new ModelJarException("Missing decision qualification property: " + key);
    }
    return value.strip();
  }

  private static int integer(Properties properties, String key) {
    try {
      return Integer.parseInt(text(properties, key));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid integer for " + key, malformed);
    }
  }

  private static double decimal(Properties properties, String key) {
    try {
      return Double.parseDouble(text(properties, key));
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid decimal for " + key, malformed);
    }
  }

  /**
   * A decimal that may legitimately be absent or {@code NaN}.
   *
   * <p>Only {@code calibrationError} uses this: a cohort where every answer declared itself
   * uncalibrated has no calibration to report, and 0.0 there would read as perfect calibration.
   */
  private static double optionalDecimal(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank() || "NaN".equalsIgnoreCase(value.strip())) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(value.strip());
    } catch (NumberFormatException malformed) {
      throw new ModelJarException("Invalid decimal for " + key, malformed);
    }
  }
}
