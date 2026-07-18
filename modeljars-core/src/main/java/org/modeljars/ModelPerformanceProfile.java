package org.modeljars;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Model-SHA-bound performance guidance with the evidence and runtime scope that justify it. */
public record ModelPerformanceProfile(
    String id,
    String modelAlias,
    ModelJarCoordinate markerCoordinate,
    String artifactSha256,
    String backend,
    Map<String, String> runtimeSelector,
    Map<String, String> recommendations,
    PerformanceEvidence evidence) {

  public ModelPerformanceProfile {
    if (id == null || !id.matches("[a-z0-9][a-z0-9_-]*")) {
      throw new IllegalArgumentException("id must be a lowercase profile identifier: " + id);
    }
    modelAlias = requireText(modelAlias, "modelAlias");
    markerCoordinate = Objects.requireNonNull(markerCoordinate, "markerCoordinate");
    artifactSha256 = requireSha256(artifactSha256);
    backend = requireText(backend, "backend").toLowerCase(Locale.ROOT);
    runtimeSelector = normalizedMap(runtimeSelector, "runtimeSelector");
    if (runtimeSelector.isEmpty()) {
      throw new IllegalArgumentException("runtimeSelector must not be empty");
    }
    recommendations = normalizedMap(recommendations, "recommendations");
    evidence = Objects.requireNonNull(evidence, "evidence");
  }

  /** Returns true only when the recommendation passed the deterministic-output comparison. */
  public boolean safeForAutomaticSelection() {
    return evidence.outputHashesMatch() && !recommendations.isEmpty();
  }

  /** Tests the exact artifact identity, backend, and every runtime selector property. */
  public boolean matches(
      ModelJarDescriptor descriptor, String requestedBackend, Map<String, String> runtime) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(runtime, "runtime");
    String normalizedBackend = requireText(requestedBackend, "requestedBackend").toLowerCase(Locale.ROOT);
    if (!modelAlias.equals(descriptor.alias())
        || !markerCoordinate.equals(descriptor.markerCoordinate())
        || !descriptor.sha256().filter(artifactSha256::equals).isPresent()
        || !backend.equals(normalizedBackend)
        || !descriptor.supportsBackend(normalizedBackend)) {
      return false;
    }
    return runtimeSelector.entrySet().stream()
        .allMatch(
            selector -> {
              String actual = runtime.get(selector.getKey());
              return actual != null && selector.getValue().equalsIgnoreCase(actual.trim());
            });
  }

  private static Map<String, String> normalizedMap(Map<String, String> values, String name) {
    Objects.requireNonNull(values, name);
    return values.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                entry -> requireText(entry.getKey(), name + " key"),
                entry -> requireText(entry.getValue(), name + " value")));
  }

  private static String requireSha256(String value) {
    String sha = requireText(value, "artifactSha256").toLowerCase(Locale.ROOT);
    if (!sha.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "artifactSha256 must contain exactly 64 hexadecimal characters");
    }
    return sha;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
