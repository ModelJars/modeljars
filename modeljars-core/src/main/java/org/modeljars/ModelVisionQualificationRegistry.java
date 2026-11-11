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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Loads validated, exact-pair LLaVA task qualification resources. */
public final class ModelVisionQualificationRegistry {
  /** Classpath resource containing the versioned vision evidence. */
  public static final String RESOURCE = "META-INF/modeljars/vision-qualifications-v1.properties";

  private final List<ModelVisionQualification> entries;

  ModelVisionQualificationRegistry(List<ModelVisionQualification> entries) {
    this.entries = List.copyOf(entries);
    if (entries.stream().map(ModelVisionQualification::modelId).distinct().count()
        != entries.size()) throw new ModelJarException("Duplicate vision evidence identity");
  }

  /**
   * Loads the vision evidence visible to this application.
   *
   * @return all evidence visible to the current context class loader
   */
  public static ModelVisionQualificationRegistry fromClasspath() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    ClassLoader loader =
        context == null ? ModelVisionQualificationRegistry.class.getClassLoader() : context;
    Map<String, ModelVisionQualification> merged = new LinkedHashMap<>();
    try {
      var resources = loader.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        try (InputStream stream = resources.nextElement().openStream()) {
          for (var entry : parse(stream).entries) {
            var previous = merged.putIfAbsent(entry.modelId(), entry);
            if (previous != null && !previous.equals(entry))
              throw new ModelJarException("Conflicting vision evidence: " + entry.modelId());
          }
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read vision evidence", failure);
    }
    return new ModelVisionQualificationRegistry(List.copyOf(merged.values()));
  }

  /**
   * Parses and validates every gate before exposing any qualification.
   *
   * @param input caller-owned properties stream
   * @return validated registry
   * @throws IOException when reading fails
   */
  public static ModelVisionQualificationRegistry parse(InputStream input) throws IOException {
    Properties properties = new Properties();
    properties.load(input);
    if (!text(properties, "modeljars.vision.schemaVersion").equals("1"))
      throw new ModelJarException("Unsupported vision evidence schema");
    int count = integer(properties, "modeljars.vision.count");
    if (count < 0 || count > 10000) throw new ModelJarException("Invalid vision evidence count");
    List<ModelVisionQualification> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) entries.add(entry(properties, "vision." + i + "."));
    return new ModelVisionQualificationRegistry(entries);
  }

  private static ModelVisionQualification entry(Properties p, String prefix) {
    return new ModelVisionQualification(
        text(p, prefix + "modelId"),
        text(p, prefix + "backendVersion"),
        URI.create(text(p, prefix + "report")),
        text(p, prefix + "reportSha256"),
        text(p, prefix + "corpusSha256"),
        files(p, prefix),
        datasets(p, prefix),
        number(p, prefix + "tokenAgreement"),
        performance(p, prefix),
        bool(p, prefix + "integrationPassed"),
        bool(p, prefix + "qualified"));
  }

  private static List<ModelArtifactFile> files(Properties p, String prefix) {
    List<ModelArtifactFile> files = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String f = prefix + "files." + i + ".";
      files.add(
          new ModelArtifactFile(
              text(p, f + "path"),
              text(p, f + "role"),
              URI.create(text(p, f + "downloadUri")),
              text(p, f + "sha256"),
              Long.parseLong(text(p, f + "sizeBytes"))));
    }
    return files;
  }

  private static Map<String, ModelVisionQualification.Dataset> datasets(
      Properties p, String prefix) {
    Map<String, ModelVisionQualification.Dataset> datasets = new LinkedHashMap<>();
    for (String dataset : List.of("vqav2", "textvqa")) {
      String d = prefix + dataset + ".";
      datasets.put(
          dataset,
          new ModelVisionQualification.Dataset(
              integer(p, d + "cases"),
              number(p, d + "candidateScore"),
              number(p, d + "referenceScore"),
              integer(p, d + "blankCases"),
              number(p, d + "imageContribution")));
    }
    return datasets;
  }

  private static List<ModelVisionQualification.Performance> performance(
      Properties p, String prefix) {
    List<ModelVisionQualification.Performance> performance = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String f = prefix + "performance." + i + ".";
      performance.add(
          new ModelVisionQualification.Performance(
              text(p, f + "dataset"),
              text(p, f + "caseId"),
              integer(p, f + "warmups"),
              integer(p, f + "repetitions"),
              number(p, f + "prefillRatio"),
              number(p, f + "decodeRatio"),
              number(p, f + "totalRatio")));
    }
    return performance;
  }

  /**
   * Selects passing evidence bound to both marker files.
   *
   * @param descriptor complete model marker
   * @return matching qualification, if present
   */
  public Optional<ModelVisionQualification> qualificationFor(ModelJarDescriptor descriptor) {
    return entries.stream()
        .filter(ModelVisionQualification::qualified)
        .filter(e -> e.matches(descriptor))
        .findFirst();
  }

  /**
   * Returns all recorded vision evidence, including rejected attempts.
   *
   * @return immutable evidence entries
   */
  public List<ModelVisionQualification> entries() {
    return entries;
  }

  private static String text(Properties p, String key) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank())
      throw new ModelJarException("Missing vision evidence: " + key);
    return value;
  }

  private static int integer(Properties p, String key) {
    return Integer.parseInt(text(p, key));
  }

  private static double number(Properties p, String key) {
    return Double.parseDouble(text(p, key));
  }

  private static boolean bool(Properties p, String key) {
    String value = text(p, key);
    if (!value.equals("true") && !value.equals("false"))
      throw new ModelJarException("Invalid vision evidence boolean: " + key);
    return Boolean.parseBoolean(value);
  }
}
