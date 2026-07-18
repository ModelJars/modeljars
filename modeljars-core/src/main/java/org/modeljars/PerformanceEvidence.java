package org.modeljars;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Reproducible before/after benchmark evidence attached to a model performance profile. */
public record PerformanceEvidence(
    String benchmarkId,
    Instant measuredAt,
    String baseline,
    String candidate,
    int warmups,
    int trials,
    int generatedTokens,
    boolean outputHashesMatch,
    Map<String, Double> baselineMetrics,
    Map<String, Double> candidateMetrics,
    Map<String, String> controls) {

  public PerformanceEvidence {
    benchmarkId = requireText(benchmarkId, "benchmarkId");
    measuredAt = Objects.requireNonNull(measuredAt, "measuredAt");
    baseline = requireText(baseline, "baseline");
    candidate = requireText(candidate, "candidate");
    if (warmups < 0 || trials <= 0 || generatedTokens <= 0) {
      throw new IllegalArgumentException(
          "warmups must be >= 0 and trials/generatedTokens must be > 0");
    }
    baselineMetrics = validatedMetrics(baselineMetrics, "baselineMetrics");
    candidateMetrics = validatedMetrics(candidateMetrics, "candidateMetrics");
    controls = Map.copyOf(Objects.requireNonNull(controls, "controls"));
  }

  private static Map<String, Double> validatedMetrics(
      Map<String, Double> values, String name) {
    Map<String, Double> metrics = Map.copyOf(Objects.requireNonNull(values, name));
    if (metrics.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    metrics.forEach(
        (key, value) -> {
          requireText(key, name + " key");
          if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " values must be finite and >= 0");
          }
        });
    return metrics;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
