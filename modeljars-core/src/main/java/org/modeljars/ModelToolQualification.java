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

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Tool-calling conformance evidence bound to one immutable model artifact and backend. */
public record ModelToolQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String workload,
    String promptTemplate,
    String artifactSha256,
    long artifactSizeBytes,
    String reportPath,
    URI reportUri,
    String reportSha256,
    String verdict,
    boolean qualified,
    int attempts,
    int passed,
    double structuredOutputRate,
    double toolSelectionExactRate,
    double schemaValidityRate,
    double declaredArgumentsOnlyRate,
    double expectedArgumentAccuracy,
    double refusalAccuracy,
    double p95EndToEndMillis,
    String suiteSha256,
    URI sourceRepository,
    String sourceRevision,
    String sourcePath,
    ModelQualificationEnvironment environment)
    implements ModelExecutionQualification {

  /** Minimum expected-argument accuracy for qualified tool-calling evidence. */
  public static final double MINIMUM_EXPECTED_ARGUMENT_ACCURACY = 0.90;

  /** Validates immutable provenance, conformance metrics, and the qualification policy floor. */
  public ModelToolQualification {
    modelId = requireIdentifier(modelId, "modelId");
    model = requireText(model, "model");
    backend = requireText(backend, "backend").toLowerCase(Locale.ROOT);
    backendVersion = requireText(backendVersion, "backendVersion");
    workload = requireSlug(workload, "workload");
    promptTemplate = requireIdentifier(promptTemplate, "promptTemplate");
    artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
    if (artifactSizeBytes < 1) {
      throw new IllegalArgumentException("artifactSizeBytes must be positive");
    }
    reportPath = requireRelativePath(reportPath, "reportPath");
    reportUri = requireHttps(reportUri, "reportUri");
    reportSha256 = requireSha256(reportSha256, "reportSha256");
    verdict = requireText(verdict, "verdict").toUpperCase(Locale.ROOT);
    if (attempts < 1) {
      throw new IllegalArgumentException("attempts must be positive");
    }
    if (passed < 0 || passed > attempts) {
      throw new IllegalArgumentException("passed must be between zero and attempts");
    }
    structuredOutputRate = requireRate(structuredOutputRate, "structuredOutputRate");
    toolSelectionExactRate = requireRate(toolSelectionExactRate, "toolSelectionExactRate");
    schemaValidityRate = requireRate(schemaValidityRate, "schemaValidityRate");
    declaredArgumentsOnlyRate = requireRate(declaredArgumentsOnlyRate, "declaredArgumentsOnlyRate");
    expectedArgumentAccuracy = requireRate(expectedArgumentAccuracy, "expectedArgumentAccuracy");
    refusalAccuracy = requireRate(refusalAccuracy, "refusalAccuracy");
    if (!Double.isFinite(p95EndToEndMillis) || p95EndToEndMillis < 0) {
      throw new IllegalArgumentException("p95EndToEndMillis must be finite and non-negative");
    }
    suiteSha256 = requireSha256(suiteSha256, "suiteSha256");
    sourceRepository = requireHttps(sourceRepository, "sourceRepository");
    sourceRevision = requireRevision(sourceRevision, "sourceRevision");
    sourcePath = requireRelativePath(sourcePath, "sourcePath");
    environment = Objects.requireNonNull(environment, "environment");
    if (qualified
        && (structuredOutputRate < 1.0
            || toolSelectionExactRate < 1.0
            || schemaValidityRate < 1.0
            || declaredArgumentsOnlyRate < 1.0
            || expectedArgumentAccuracy < MINIMUM_EXPECTED_ARGUMENT_ACCURACY
            || refusalAccuracy < 1.0)) {
      throw new IllegalArgumentException(
          "qualified tool evidence must meet every conformance policy floor");
    }
  }

  @Override
  public boolean productionUsable() {
    return qualified;
  }

  @Override
  public boolean matches(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return modelId.equals(descriptor.alias())
        && descriptor.sha256().filter(artifactSha256::equals).isPresent()
        && descriptor.supportsBackend(backend);
  }

  private static String requireIdentifier(String value, String name) {
    String identifier = requireText(value, name);
    if (!identifier.matches("[a-z0-9_]+")) {
      throw new IllegalArgumentException(name + " must be a lowercase identifier");
    }
    return identifier;
  }

  private static String requireSlug(String value, String name) {
    String slug = requireText(value, name);
    if (!slug.matches("[a-z0-9][a-z0-9._-]*")) {
      throw new IllegalArgumentException(name + " must be a lowercase slug");
    }
    return slug;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String requireSha256(String value, String name) {
    String sha = requireText(value, name).toLowerCase(Locale.ROOT);
    if (!sha.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
    }
    return sha;
  }

  private static String requireRevision(String value, String name) {
    String revision = requireText(value, name).toLowerCase(Locale.ROOT);
    if (!revision.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException(name + " must contain 40 hexadecimal characters");
    }
    return revision;
  }

  private static String requireRelativePath(String value, String name) {
    String text = requireText(value, name).replace('\\', '/');
    Path path = Path.of(text).normalize();
    if (path.isAbsolute()
        || path.startsWith("..")
        || !path.toString().replace('\\', '/').equals(text)) {
      throw new IllegalArgumentException(name + " must be a normalized relative path");
    }
    return text;
  }

  private static URI requireHttps(URI value, String name) {
    URI uri = Objects.requireNonNull(value, name);
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException(name + " must use HTTPS");
    }
    return uri;
  }

  private static double requireRate(double value, String name) {
    if (!Double.isFinite(value) || value < 0 || value > 1) {
      throw new IllegalArgumentException(name + " must be between zero and one");
    }
    return value;
  }
}
