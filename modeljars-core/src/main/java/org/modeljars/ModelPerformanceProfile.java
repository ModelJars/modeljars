package org.modeljars;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Model-SHA-bound performance guidance with the evidence and runtime scope that justify it. */
public record ModelPerformanceProfile(
    String id,
    String modelAlias,
    ModelJarCoordinate markerCoordinate,
    String artifactSha256,
    String backend,
    Map<String, String> runtimeSelector,
    Map<String, String> recommendations,
    Optional<JavaLaunchProfile> javaLaunch,
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
    javaLaunch = Objects.requireNonNull(javaLaunch, "javaLaunch");
    if (javaLaunch.isPresent()) {
      validateLaunchSelector(runtimeSelector, javaLaunch.orElseThrow());
    }
    evidence = Objects.requireNonNull(evidence, "evidence");
  }

  /** Backward-compatible constructor for schema-v1 profiles without startup requirements. */
  public ModelPerformanceProfile(
      String id,
      String modelAlias,
      ModelJarCoordinate markerCoordinate,
      String artifactSha256,
      String backend,
      Map<String, String> runtimeSelector,
      Map<String, String> recommendations,
      PerformanceEvidence evidence) {
    this(
        id,
        modelAlias,
        markerCoordinate,
        artifactSha256,
        backend,
        runtimeSelector,
        recommendations,
        Optional.empty(),
        evidence);
  }

  /** Returns true only when the recommendation passed the deterministic-output comparison. */
  public boolean safeForAutomaticSelection() {
    return evidence.outputHashesMatch() && (!recommendations.isEmpty() || javaLaunch.isPresent());
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

  private static void validateLaunchSelector(
      Map<String, String> runtimeSelector, JavaLaunchProfile launch) {
    String javaFeature = runtimeSelector.get("java-feature");
    if (!Integer.toString(launch.javaFeature()).equals(javaFeature)) {
      throw new IllegalArgumentException(
          "javaLaunch.javaFeature must match runtimeSelector java-feature");
    }
    String compiler = runtimeSelector.get("compiler");
    if (compiler == null || !launch.runtime().equalsIgnoreCase(compiler)) {
      throw new IllegalArgumentException("javaLaunch.runtime must match runtimeSelector compiler");
    }
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
