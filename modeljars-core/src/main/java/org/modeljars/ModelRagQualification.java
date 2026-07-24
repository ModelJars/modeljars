package org.modeljars;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Production RAG evidence bound to one immutable model artifact and execution backend. */
public record ModelRagQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String artifactSha256,
    long artifactSizeBytes,
    String reportPath,
    URI reportUri,
    String reportSha256,
    String performanceTier,
    String verdict,
    boolean qualified,
    int attempts,
    double p95RetrievalMillis,
    double p95TtftMillis,
    double p95TpotMillis,
    double p95EndToEndMillis,
    double p50PrefillTokensPerSecond,
    double p50DecodeTokensPerSecond,
    long peakRssBytes,
    double correctAnswerRate,
    double rawCorrectAnswerRate,
    double abstentionAccuracy,
    double modelAnswerRate,
    double extractiveFallbackRate,
    ModelQualificationEnvironment environment) {

  public ModelRagQualification {
    modelId = requireIdentifier(modelId);
    model = requireText(model, "model");
    backend = requireText(backend, "backend").toLowerCase(Locale.ROOT);
    backendVersion = requireText(backendVersion, "backendVersion");
    artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
    if (artifactSizeBytes < 1) {
      throw new IllegalArgumentException("artifactSizeBytes must be positive");
    }
    reportPath = requireRelativePath(reportPath);
    reportUri = requireHttps(reportUri);
    reportSha256 = requireSha256(reportSha256, "reportSha256");
    performanceTier = requireText(performanceTier, "performanceTier").toUpperCase(Locale.ROOT);
    verdict = requireText(verdict, "verdict").toUpperCase(Locale.ROOT);
    if (attempts < 1) {
      throw new IllegalArgumentException("attempts must be positive");
    }
    p95RetrievalMillis = requireMetric(p95RetrievalMillis, "p95RetrievalMillis");
    p95TtftMillis = requireMetric(p95TtftMillis, "p95TtftMillis");
    p95TpotMillis = requireMetric(p95TpotMillis, "p95TpotMillis");
    p95EndToEndMillis = requireMetric(p95EndToEndMillis, "p95EndToEndMillis");
    p50PrefillTokensPerSecond =
        requireMetric(p50PrefillTokensPerSecond, "p50PrefillTokensPerSecond");
    p50DecodeTokensPerSecond =
        requireMetric(p50DecodeTokensPerSecond, "p50DecodeTokensPerSecond");
    if (peakRssBytes < 1) {
      throw new IllegalArgumentException("peakRssBytes must be positive");
    }
    correctAnswerRate = requireRate(correctAnswerRate, "correctAnswerRate");
    rawCorrectAnswerRate = requireRate(rawCorrectAnswerRate, "rawCorrectAnswerRate");
    abstentionAccuracy = requireRate(abstentionAccuracy, "abstentionAccuracy");
    modelAnswerRate = requireRate(modelAnswerRate, "modelAnswerRate");
    extractiveFallbackRate = requireRate(extractiveFallbackRate, "extractiveFallbackRate");
    environment = Objects.requireNonNull(environment, "environment");
  }

  /** Classifies how the artifact met the production quality policy. */
  public RagUseCaseTier useCaseTier() {
    if (!qualified) {
      return RagUseCaseTier.UNQUALIFIED;
    }
    if (rawCorrectAnswerRate >= 0.9 && modelAnswerRate >= 0.9) {
      return RagUseCaseTier.GENERATIVE_RAG;
    }
    return RagUseCaseTier.GUARDED_RAG;
  }

  /** Returns true only when the exact artifact passed the production policy. */
  public boolean productionUsable() {
    return qualified;
  }

  /** Tests the descriptor alias, artifact digest, and advertised backend support. */
  public boolean matches(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return modelId.equals(descriptor.alias())
        && descriptor.sha256().filter(artifactSha256::equals).isPresent()
        && descriptor.supportsBackend(backend);
  }

  private static String requireIdentifier(String value) {
    String identifier = requireText(value, "modelId");
    if (!identifier.matches("[a-z0-9_]+")) {
      throw new IllegalArgumentException("modelId must be a lowercase catalog identifier");
    }
    return identifier;
  }

  private static String requireRelativePath(String value) {
    String text = requireText(value, "reportPath").replace('\\', '/');
    Path path = Path.of(text).normalize();
    if (path.isAbsolute() || path.startsWith("..") || !path.toString().replace('\\', '/').equals(text)) {
      throw new IllegalArgumentException("reportPath must be a normalized relative path");
    }
    return text;
  }

  private static URI requireHttps(URI value) {
    URI uri = Objects.requireNonNull(value, "reportUri");
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("reportUri must use HTTPS");
    }
    return uri;
  }

  private static String requireSha256(String value, String name) {
    String sha = requireText(value, name).toLowerCase(Locale.ROOT);
    if (!sha.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
    }
    return sha;
  }

  private static double requireMetric(double value, String name) {
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException(name + " must be finite and non-negative");
    }
    return value;
  }

  private static double requireRate(double value, String name) {
    if (!Double.isFinite(value) || value < 0 || value > 1) {
      throw new IllegalArgumentException(name + " must be between zero and one");
    }
    return value;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
