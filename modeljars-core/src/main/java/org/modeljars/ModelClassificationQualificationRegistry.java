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
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Loads exact-snapshot schema-classification evidence from versioned resources. */
public final class ModelClassificationQualificationRegistry {
  /** Classpath location of versioned classification evidence. */
  public static final String RESOURCE =
      "META-INF/modeljars/classification-qualifications-v1.properties";

  private final List<ModelClassificationQualification> entries;

  ModelClassificationQualificationRegistry(List<ModelClassificationQualification> entries) {
    this.entries = List.copyOf(entries);
  }

  /**
   * Loads visible classification evidence using the context class loader.
   *
   * @return registry of consistent, validated evidence
   */
  public static ModelClassificationQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads every classification evidence resource; conflicting identities fail closed.
   *
   * @param loader resource class loader, or null to use this class's loader
   * @return registry of consistent, validated evidence
   */
  public static ModelClassificationQualificationRegistry fromClasspath(ClassLoader loader) {
    ClassLoader resolved =
        loader == null ? ModelClassificationQualificationRegistry.class.getClassLoader() : loader;
    Map<String, ModelClassificationQualification> merged = new LinkedHashMap<>();
    try {
      var resources = resolved.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          for (var entry : parse(stream).entries()) {
            var prior = merged.putIfAbsent(entry.modelId(), entry);
            if (prior != null && !prior.equals(entry))
              throw new ModelJarException(
                  "Conflicting classification evidence: " + entry.modelId());
          }
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, failure);
    }
    return new ModelClassificationQualificationRegistry(
        merged.values().stream()
            .sorted(Comparator.comparing(ModelClassificationQualification::modelId))
            .toList());
  }

  /**
   * Parses evidence and validates the registered gates before exposing an entry.
   *
   * @param stream evidence properties, owned by the caller
   * @return validated evidence registry
   * @throws IOException if the stream cannot be read
   */
  public static ModelClassificationQualificationRegistry parse(InputStream stream)
      throws IOException {
    Properties p = new Properties();
    p.load(Objects.requireNonNull(stream, "stream"));
    if (!"1".equals(p.getProperty("modeljars.classification.schemaVersion")))
      throw new ModelJarException("Classification evidence requires schemaVersion 1");
    List<ModelClassificationQualification> entries = new ArrayList<>();
    int count = count(p, "modeljars.classification.count", 10000);
    for (int i = 0; i < count; i++) {
      String prefix = "classification." + i + ".";
      List<ModelArtifactFile> files = new ArrayList<>();
      for (int j = 0, n = count(p, prefix + "files.count", 1000); j < n; j++) {
        String f = prefix + "files." + j + ".";
        files.add(
            new ModelArtifactFile(
                text(p, f + "path"),
                text(p, f + "role"),
                URI.create(text(p, f + "downloadUri")),
                text(p, f + "sha256"),
                Long.parseLong(text(p, f + "sizeBytes"))));
      }
      Map<String, Double> quality = new LinkedHashMap<>();
      for (String metric : ModelClassificationQualification.qualityFloors().keySet())
        quality.put(metric, number(p, prefix + "quality." + metric));
      List<ModelClassificationQualification.PerformanceBucket> performance = new ArrayList<>();
      for (int j = 0, n = count(p, prefix + "performance.count", 10000); j < n; j++) {
        String f = prefix + "performance." + j + ".";
        performance.add(
            new ModelClassificationQualification.PerformanceBucket(
                count(p, f + "pair", 1000),
                text(p, f + "shape"),
                count(p, f + "samples", 1000000),
                number(p, f + "throughputRatio"),
                number(p, f + "p95LatencyRatio")));
      }
      entries.add(
          new ModelClassificationQualification(
              text(p, prefix + "modelId"),
              text(p, prefix + "backend"),
              text(p, prefix + "backendVersion"),
              URI.create(text(p, prefix + "report")),
              text(p, prefix + "reportSha256"),
              files,
              quality,
              number(p, prefix + "maximumOracleQualityLoss"),
              number(p, prefix + "maximumBaseQualityRegression"),
              count(p, prefix + "qualityCases", 10000000),
              count(p, prefix + "inferenceErrors", 10000000),
              bool(p, prefix + "numericalParityPassed"),
              performance,
              bool(p, prefix + "releasedRuntimeVerified"),
              bool(p, prefix + "qualified")));
    }
    if (entries.stream().map(ModelClassificationQualification::modelId).distinct().count()
        != entries.size()) throw new ModelJarException("Duplicate classification model identity");
    return new ModelClassificationQualificationRegistry(entries);
  }

  /**
   * Returns the immutable evidence entries.
   *
   * @return recorded classification qualifications
   */
  public List<ModelClassificationQualification> entries() {
    return entries;
  }

  /**
   * Matches every runtime file, including tokenizer and configuration.
   *
   * @param descriptor complete model descriptor
   * @return qualified evidence for this exact snapshot, when available
   */
  public Optional<ModelClassificationQualification> qualificationFor(
      ModelJarDescriptor descriptor) {
    return entries.stream()
        .filter(ModelClassificationQualification::qualified)
        .filter(entry -> entry.matches(descriptor))
        .findFirst();
  }

  private static String text(Properties p, String key) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank())
      throw new ModelJarException("Missing classification evidence: " + key);
    return value;
  }

  private static double number(Properties p, String key) {
    return Double.parseDouble(text(p, key));
  }

  private static int count(Properties p, String key, int maximum) {
    int value = Integer.parseInt(text(p, key));
    if (value < 0 || value > maximum)
      throw new ModelJarException("Invalid classification evidence count: " + key);
    return value;
  }

  private static boolean bool(Properties p, String key) {
    String value = text(p, key);
    if (!value.equals("true") && !value.equals("false"))
      throw new ModelJarException("Invalid classification evidence boolean: " + key);
    return Boolean.parseBoolean(value);
  }
}
