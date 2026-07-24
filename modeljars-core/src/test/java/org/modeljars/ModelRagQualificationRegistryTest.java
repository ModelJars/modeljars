package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRagQualificationRegistryTest {

  private static final String ARTIFACT_SHA =
      "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4";
  private static final String REPORT_SHA =
      "e1758e92303d9fca08c8668f4d376d47bcb09dc70b467c8432bb263f8f77d31b";
  private static final String MODELS_REVISION = "3b8ef7392a24b92faf9ec8d30cc3984182ecba78";

  @Test
  void loadsAuditableQualificationAndMatchesOnlyTheCertifiedArtifact() {
    ModelRagQualificationRegistry registry =
        ModelRagQualificationRegistry.fromProperties(properties(1.0, 1.0, true));

    assertEquals(Instant.parse("2026-07-24T06:00:00Z"), registry.generatedAt());
    assertEquals(
        "trusted-citation-lexical-entailment-extractive-fallback-v2",
        registry.policyVersion());
    assertEquals(MODELS_REVISION, registry.modelsRevision());
    assertEquals(25, registry.targetQualifiedModels());
    assertEquals(1, registry.qualifiedModels());
    assertEquals(0, registry.rejectedModels());

    ModelRagQualification qualification = registry.qualifications().getFirst();
    assertEquals("qwen3_0_6b_q4_0", qualification.modelId());
    assertEquals("llama.cpp", qualification.backend());
    assertEquals(27, qualification.attempts());
    assertEquals(101.569, qualification.p50DecodeTokensPerSecond());
    assertEquals(
        URI.create(
            "https://github.com/integrallis/models/blob/"
                + MODELS_REVISION
                + "/benchmark-results/certified-20260724/rag/launch-campaign-v2/qwen.json"),
        qualification.reportUri());
    assertEquals(RagUseCaseTier.GENERATIVE_RAG, qualification.useCaseTier());
    assertTrue(qualification.productionUsable());
    assertEquals(List.of(qualification), registry.qualified());
    assertEquals(List.of(qualification), registry.qualificationsFor(descriptor(ARTIFACT_SHA)));
    assertTrue(registry.qualificationsFor(descriptor("0".repeat(64))).isEmpty());
  }

  @Test
  void distinguishesGuardedRagAndRejectedModels() {
    ModelRagQualification guarded =
        ModelRagQualificationRegistry.fromProperties(properties(0.67, 0.56, true))
            .qualifications()
            .getFirst();
    assertEquals(RagUseCaseTier.GUARDED_RAG, guarded.useCaseTier());
    assertTrue(guarded.productionUsable());

    Properties rejectedProperties = properties(0.67, 0.56, false);
    rejectedProperties.setProperty("modeljars.qualifications.qualifiedModels", "0");
    rejectedProperties.setProperty("modeljars.qualifications.rejectedModels", "1");
    ModelRagQualificationRegistry rejectedRegistry =
        ModelRagQualificationRegistry.fromProperties(rejectedProperties);
    ModelRagQualification rejected = rejectedRegistry.qualifications().getFirst();
    assertEquals(RagUseCaseTier.UNQUALIFIED, rejected.useCaseTier());
    assertFalse(rejected.productionUsable());
    assertTrue(rejectedRegistry.qualified().isEmpty());
  }

  @Test
  void loadsEveryVersionedClasspathResource(@TempDir Path root) throws Exception {
    Path resource = root.resolve(ModelRagQualificationRegistry.RESOURCE);
    Files.createDirectories(resource.getParent());
    try (var output = Files.newOutputStream(resource)) {
      properties(1.0, 1.0, true).store(output, null);
    }

    try (var loader = new java.net.URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, null)) {
      ModelRagQualificationRegistry registry =
          ModelRagQualificationRegistry.fromClasspath(loader);
      assertEquals(1, registry.qualifications().size());
      assertEquals(ARTIFACT_SHA, registry.qualifications().getFirst().artifactSha256());
    }
  }

  @Test
  void rejectsInvalidSchemaCountsAndEvidence() {
    Properties wrongSchema = properties(1.0, 1.0, true);
    wrongSchema.setProperty("modeljars.qualifications.schemaVersion", "2");
    assertThrows(
        ModelJarException.class,
        () -> ModelRagQualificationRegistry.fromProperties(wrongSchema));

    Properties wrongCount = properties(1.0, 1.0, true);
    wrongCount.setProperty("modeljars.qualifications.qualifiedModels", "0");
    assertThrows(
        ModelJarException.class,
        () -> ModelRagQualificationRegistry.fromProperties(wrongCount));

    Properties invalidRate = properties(1.1, 1.0, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelRagQualificationRegistry.fromProperties(invalidRate));

    Properties insecureReport = properties(1.0, 1.0, true);
    insecureReport.setProperty(
        "qualification.qwen3_0_6b_q4_0.reportUri", "http://example.test/report.json");
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelRagQualificationRegistry.fromProperties(insecureReport));
  }

  private static Properties properties(
      double rawCorrectAnswerRate, double modelAnswerRate, boolean qualified) {
    String prefix = "qualification.qwen3_0_6b_q4_0.";
    Properties properties = new Properties();
    properties.setProperty("modeljars.qualifications.schemaVersion", "1");
    properties.setProperty("modeljars.qualifications.generatedAt", "2026-07-24T06:00:00Z");
    properties.setProperty(
        "modeljars.qualifications.policyVersion",
        "trusted-citation-lexical-entailment-extractive-fallback-v2");
    properties.setProperty("modeljars.qualifications.modelsRevision", MODELS_REVISION);
    properties.setProperty("modeljars.qualifications.targetQualifiedModels", "25");
    properties.setProperty("modeljars.qualifications.qualifiedModels", qualified ? "1" : "0");
    properties.setProperty("modeljars.qualifications.rejectedModels", qualified ? "0" : "1");
    properties.setProperty(prefix + "model", "Qwen3 0.6B Q4_0");
    properties.setProperty(prefix + "backend", "llama.cpp");
    properties.setProperty(prefix + "backendVersion", "b10012-c71854292");
    properties.setProperty(prefix + "artifactSha256", ARTIFACT_SHA);
    properties.setProperty(prefix + "artifactSizeBytes", "429496729");
    properties.setProperty(
        prefix + "reportPath",
        "benchmark-results/certified-20260724/rag/launch-campaign-v2/qwen.json");
    properties.setProperty(
        prefix + "reportUri",
        "https://github.com/integrallis/models/blob/"
            + MODELS_REVISION
            + "/benchmark-results/certified-20260724/rag/launch-campaign-v2/qwen.json");
    properties.setProperty(prefix + "reportSha256", REPORT_SHA);
    properties.setProperty(prefix + "performanceTier", "PRODUCTION_READY");
    properties.setProperty(prefix + "verdict", qualified ? "QUALIFIED" : "FAILED_QUALITY");
    properties.setProperty(prefix + "qualified", Boolean.toString(qualified));
    properties.setProperty(prefix + "attempts", "27");
    properties.setProperty(prefix + "p95RetrievalMillis", "4.0");
    properties.setProperty(prefix + "p95TtftMillis", "364.905");
    properties.setProperty(prefix + "p95TpotMillis", "13.338");
    properties.setProperty(prefix + "p95EndToEndMillis", "860.1");
    properties.setProperty(prefix + "p50PrefillTokensPerSecond", "458.32");
    properties.setProperty(prefix + "p50DecodeTokensPerSecond", "101.569");
    properties.setProperty(prefix + "peakRssBytes", "1275252736");
    properties.setProperty(prefix + "correctAnswerRate", qualified ? "1.0" : "0.8");
    properties.setProperty(prefix + "rawCorrectAnswerRate", Double.toString(rawCorrectAnswerRate));
    properties.setProperty(prefix + "abstentionAccuracy", "1.0");
    properties.setProperty(prefix + "modelAnswerRate", Double.toString(modelAnswerRate));
    properties.setProperty(
        prefix + "extractiveFallbackRate", Double.toString(1.0 - modelAnswerRate));
    properties.setProperty(prefix + "environment.hostname", "qualification-host");
    properties.setProperty(prefix + "environment.osName", "Linux");
    properties.setProperty(prefix + "environment.osVersion", "6.8");
    properties.setProperty(prefix + "environment.architecture", "amd64");
    properties.setProperty(prefix + "environment.cpuModel", "AMD EPYC Milan");
    properties.setProperty(prefix + "environment.availableProcessors", "8");
    properties.setProperty(prefix + "environment.totalMemoryBytes", "32857444352");
    properties.setProperty(prefix + "environment.maxHeapBytes", "8589934592");
    properties.setProperty(prefix + "environment.javaVersion", "25.0.3");
    properties.setProperty(prefix + "environment.javaVendor", "Eclipse Adoptium");
    properties.setProperty(prefix + "environment.vmName", "OpenJDK 64-Bit Server VM");
    return properties;
  }

  private static ModelJarDescriptor descriptor(String sha) {
    return new ModelJarDescriptor(
        "qwen3_0_6b_q4_0",
        "hf://ggml-org/Qwen3-0.6B-GGUF",
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1"),
        ModelVersion.parse("3.0.0"),
        "q4_0",
        "gguf",
        "qwen3",
        "Q4_0",
        Optional.of(Path.of("model.gguf")),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF")),
        Optional.empty(),
        Optional.of("a".repeat(40)),
        Optional.of(sha),
        Optional.of(429496729L),
        Optional.of("Apache-2.0"),
        Set.of("chat"),
        Set.of(),
        Map.of("llama.cpp", true),
        Optional.of("Qwen3 0.6B Q4_0"),
        Optional.empty(),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }
}
