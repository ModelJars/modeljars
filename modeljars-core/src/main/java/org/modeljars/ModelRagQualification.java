package org.modeljars;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Production RAG evidence bound to one immutable model artifact and execution backend.
 *
 * @param modelId catalog alias of the qualified model
 * @param model display name and variant of the qualified model
 * @param backend normalized execution backend identifier
 * @param backendVersion exact backend version or build
 * @param workload stable qualification-workload identifier
 * @param corpusSha256 SHA-256 digest of the retrieval corpus
 * @param promptTemplate prompt template used by the qualification
 * @param groundingPolicy policy used to accept, abstain, or extract an answer
 * @param artifactSha256 SHA-256 digest of the qualified model artifact
 * @param artifactSizeBytes qualified model artifact size in bytes
 * @param reportPath repository-relative qualification report path
 * @param reportUri canonical HTTPS location of the qualification report
 * @param reportSha256 SHA-256 digest of the qualification report
 * @param performanceTier measured performance classification
 * @param verdict qualification verdict
 * @param qualified whether the artifact passed the production policy
 * @param attempts measured workload attempts
 * @param p95RetrievalMillis p95 retrieval latency in milliseconds
 * @param p95TtftMillis p95 time to first token in milliseconds
 * @param p95TpotMillis p95 time per output token in milliseconds
 * @param p95EndToEndMillis p95 end-to-end latency in milliseconds
 * @param p50PrefillTokensPerSecond p50 prompt-prefill throughput
 * @param p50DecodeTokensPerSecond p50 decode throughput
 * @param peakRssBytes peak resident-set size in bytes
 * @param correctAnswerRate accepted-answer correctness rate
 * @param rawCorrectAnswerRate model-only correctness rate before grounding-policy fallback
 * @param abstentionAccuracy accuracy of unsupported-question abstentions
 * @param modelAnswerRate fraction of attempts answered directly by the model
 * @param modelAnswerCorrectRate correctness rate among direct model answers
 * @param extractiveFallbackRate fraction of attempts answered by extractive fallback
 * @param environment controlled host and JVM identity
 */
public record ModelRagQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String workload,
    String corpusSha256,
    String promptTemplate,
    String groundingPolicy,
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
    double modelAnswerCorrectRate,
    double extractiveFallbackRate,
    ModelQualificationEnvironment environment) {

  public static final double MINIMUM_MODEL_ANSWER_RATE = 1.0 / 3.0;
  public static final double MINIMUM_MODEL_ANSWER_CORRECT_RATE = 0.90;

  /** Validates immutable evidence, metrics, rates, and the production policy floor. */
  public ModelRagQualification {
    modelId = requireIdentifier(modelId);
    model = requireText(model, "model");
    backend = requireText(backend, "backend").toLowerCase(Locale.ROOT);
    backendVersion = requireText(backendVersion, "backendVersion");
    workload = requireIdentifier(workload, "workload");
    corpusSha256 = requireSha256(corpusSha256, "corpusSha256");
    promptTemplate = requireText(promptTemplate, "promptTemplate");
    groundingPolicy = requireText(groundingPolicy, "groundingPolicy");
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
    modelAnswerCorrectRate = requireRate(modelAnswerCorrectRate, "modelAnswerCorrectRate");
    extractiveFallbackRate = requireRate(extractiveFallbackRate, "extractiveFallbackRate");
    environment = Objects.requireNonNull(environment, "environment");
    if (qualified
        && (modelAnswerRate < MINIMUM_MODEL_ANSWER_RATE
            || modelAnswerCorrectRate < MINIMUM_MODEL_ANSWER_CORRECT_RATE)) {
      throw new IllegalArgumentException(
          "qualified evidence must meet model-answer contribution and correctness floors");
    }
  }

  /**
   * Classifies how the artifact met the production quality policy.
   *
   * @return the highest supported RAG use-case tier
   */
  public RagUseCaseTier useCaseTier() {
    if (!qualified) {
      return RagUseCaseTier.UNQUALIFIED;
    }
    if (rawCorrectAnswerRate >= 0.9 && modelAnswerRate >= 0.9) {
      return RagUseCaseTier.GENERATIVE_RAG;
    }
    return RagUseCaseTier.GUARDED_RAG;
  }

  /**
   * Returns true only when the exact artifact passed the production policy.
   *
   * @return whether the exact model artifact is production usable
   */
  public boolean productionUsable() {
    return qualified;
  }

  /**
   * Tests the descriptor alias, artifact digest, and advertised backend support.
   *
   * @param descriptor candidate model descriptor
   * @return whether this qualification applies to the exact descriptor
   */
  public boolean matches(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return modelId.equals(descriptor.alias())
        && descriptor.sha256().filter(artifactSha256::equals).isPresent()
        && descriptor.supportsBackend(backend);
  }

  private static String requireIdentifier(String value) {
    return requireIdentifier(value, "modelId");
  }

  private static String requireIdentifier(String value, String name) {
    String identifier = requireText(value, name);
    if (!identifier.matches("[a-z0-9_]+")) {
      throw new IllegalArgumentException(name + " must be a lowercase identifier");
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
