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
  private static final int AGGREGATE_QUALIFIED_MODELS = 27;
  private static final String AGGREGATE_MODELS_REVISION =
      "bc9ac1d08d49c6e70a9396af9b086942db1fe419";

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
        "production-rag-model-contribution-v4",
        registry.policyVersion());
    assertEquals(MODELS_REVISION, registry.modelsRevision());
    assertEquals(25, registry.targetQualifiedModels());
    assertEquals(1, registry.qualifiedModels());
    assertEquals(0, registry.rejectedModels());

    ModelRagQualification qualification = registry.qualifications().getFirst();
    assertEquals("qwen3_0_6b_q4_0", qualification.modelId());
    assertEquals("llama.cpp", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("d".repeat(64), qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals(
        "trusted-provenance-clause-anchors-extractive-fallback-v4",
        qualification.groundingPolicy());
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
  void aggregateCatalogPublishesQualifiedSmolLm2RustFfmEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("smollm2_360m_instruct_q8_0"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-2729c3a", qualification.backendVersion());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(18.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(1.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(43.91685822892476, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen3RustFfmEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen3_1_7b_q8_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-2729c3a", qualification.backendVersion());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(21.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(17.99028698691015, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25CoderRustFfmEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen2_5_coder_0_5b_instruct_q8_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-689f0d5", qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals(
        "trusted-provenance-clause-anchors-extractive-fallback-v4",
        qualification.groundingPolicy());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(15.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(53.01198879379568, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen3SixHundredMillionPureJavaEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen3_0_6b_q4_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("pure-java", qualification.backend());
    assertEquals("models-purejava-3e759cc-q4-unsigned", qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("chatml-no-think", qualification.promptTemplate());
    assertEquals(
        "trusted-provenance-clause-anchors-extractive-fallback-v4",
        qualification.groundingPolicy());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(3.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(51.019240300967795, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25CoderQ4HybridEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen2_5_coder_0_5b_instruct_q4_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-3e759cc-q4-unsigned-t4", qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals(
        "trusted-provenance-clause-anchors-extractive-fallback-v4",
        qualification.groundingPolicy());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(39.501345700476605, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25Coder15BQ4HybridEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen2_5_coder_1_5b_instruct_q4_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-3e759cc-q4-unsigned-t4", qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals(
        "trusted-provenance-clause-anchors-extractive-fallback-v4",
        qualification.groundingPolicy());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(3.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(15.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(23.71799920784686, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25Coder15BQ8RustEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("qwen2_5_coder_1_5b_instruct_q8_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("models-native-3e759cc-q8", qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(15.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(19.69807730818983, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedEuroLlmMultilingualEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("eurollm_1_7b_instruct_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(
        "models@180fae9+modeljars@06da6f6+vectors@c298a0b", qualification.backendVersion());
    assertEquals("multilingual", qualification.workload());
    assertEquals(
        "d1d0889113c2d2f3e6c6fe3e551bdeecfb2d0324454d75d7ac55462619c88178",
        qualification.corpusSha256());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(21.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(31.85981477595764, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25GeneralEvidence() {
    ModelRagQualification qualification =
        ModelRagQualificationRegistry.fromClasspath().qualifications().stream()
            .filter(
                entry -> entry.modelId().equals("qwen_qwen2_5_0_5b_instruct_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("PRODUCTION_READY", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(67.53433542693713, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25OnePointFiveBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry -> entry.modelId().equals("qwen_qwen2_5_1_5b_instruct_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(3.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(9.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(31.2528077247551, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedUmarTransitEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry -> entry.modelId().equals("umarfarookm_umartransit_1b_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("transportation", qualification.workload());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(15.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(30.933941694666537, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedMiniCpm5Evidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("minicpm5_1b_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("coding", qualification.workload());
    assertEquals("minicpm5-no-think", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(6.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(49.00532460192145, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedLlama32OneBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry.modelId().equals("bartowski_llama_3_2_1b_instruct_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("llama3", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(3.0 / 27.0, qualification.rawCorrectAnswerRate());
    assertEquals(15.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(38.728329822527606, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedGemma3OneBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry.modelId().equals("bartowski_google_gemma_3_1b_it_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("gemma", qualification.promptTemplate());
    assertEquals("PRODUCTION_READY", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(9.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(41.057387554747024, qualification.p50DecodeTokensPerSecond());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedIndianLegalQwenThreeBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry.modelId().equals("gsms_b_indian_legal_qwen2_5_3b_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", qualification.backend());
    assertEquals("legal", qualification.workload());
    assertEquals("chatml", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(12.0 / 27.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(15.576879388209814, qualification.p50DecodeTokensPerSecond());
    assertEquals(3_487.0603180000003, qualification.p95EndToEndMillis());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedDeepSeekR1DistillQwenOnePointFiveBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry
                        .modelId()
                        .equals("bartowski_deepseek_r1_distill_qwen_1_5b_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", qualification.backend());
    assertEquals("general", qualification.workload());
    assertEquals("chatml-no-think", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(1.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(29.038017564355236, qualification.p50DecodeTokensPerSecond());
    assertEquals(2_820.2629916, qualification.p95EndToEndMillis());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25MathOnePointFiveBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry.modelId().equals("qwen2_5_math_1_5b_instruct_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", qualification.backend());
    assertEquals("math", qualification.workload());
    assertEquals("chatml-direct", qualification.promptTemplate());
    assertEquals("PRODUCTION_READY", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(1.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(26.987122854620765, qualification.p50DecodeTokensPerSecond());
    assertEquals(2_079.1374404, qualification.p95EndToEndMillis());
    assertEquals(
        "ac0e13ea84dc179ecbbb16e345191f992de43c8729f13b66521656a7289d9e61",
        qualification.reportSha256());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedLlama32ThreeBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry.modelId().equals("bartowski_llama_3_2_3b_instruct_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(
        "models@49b848f3e594d07322cbe2ea8418e9312609dd33 "
            + "modeljars@ad3d2d4d68403fe8fea544877d421097e0fc65f7 "
            + "vectors@c298a0b73970468794c1ba403022e2adc517e57e",
        qualification.backendVersion());
    assertEquals("general", qualification.workload());
    assertEquals(
        "4b27eba8f166c84ef19c53de825445a6d0097f9bd8efa20b2d7013f34621f83c",
        qualification.corpusSha256());
    assertEquals("llama3", qualification.promptTemplate());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(1.0, qualification.rawCorrectAnswerRate());
    assertEquals(2.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(15.697249150115727, qualification.p50DecodeTokensPerSecond());
    assertEquals(3_789.2577145, qualification.p95EndToEndMillis());
    assertEquals(
        "86d77fe27372764fe0d60c88bf1be7a0ef4b767f8cb03c891bc6e3d470c4a42c",
        qualification.reportSha256());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedDeepSeekCoderOnePointThreeBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(
                entry -> entry.modelId().equals("deepseek_coder_1_3b_instruct_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals(AGGREGATE_MODELS_REVISION, registry.modelsRevision());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(
        "models@9c08ebd866d2c35b5e4695992f6ff62e4b0bf8d8 "
            + "modeljars@0b50df8 "
            + "vectors@c298a0b73970468794c1ba403022e2adc517e57e",
        qualification.backendVersion());
    assertEquals("coding", qualification.workload());
    assertEquals(
        "6841c286837b4c45c06fe8d103b2e044b61a1bfe75a61b64fa04c7ca31b20e45",
        qualification.corpusSha256());
    assertEquals("deepseek", qualification.promptTemplate());
    assertEquals(
        "trusted-title-provenance-statement-anchors-safe-discourse-explicit-abstention-v14",
        qualification.groundingPolicy());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(1.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(15.0 / 27.0, qualification.extractiveFallbackRate());
    assertEquals(29.94997844450135, qualification.p50DecodeTokensPerSecond());
    assertEquals(2_972.0400623, qualification.p95EndToEndMillis());
    assertEquals(
        "6d4f78972e2f4ec3f9646a312ec05837fbb1d948a6f6e26ae122b33005595cc1",
        qualification.reportSha256());
    assertEquals(
        URI.create(
            "https://github.com/integrallis/models/blob/"
                + registry.modelsRevision()
                + "/benchmark-results/certified-20260726/rag/"
                + "deepseek-coder-1.3b-q4_k_m/models-rust-ffm-marker.json"),
        qualification.reportUri());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedSmolLmThreeBillionEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("smollm3_3b_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals(AGGREGATE_MODELS_REVISION, registry.modelsRevision());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(
        "models@20733a0cd7cf9207e5641d7f749458a3021fc245 "
            + "modeljars@372defe "
            + "vectors@c298a0b73970468794c1ba403022e2adc517e57e",
        qualification.backendVersion());
    assertEquals("general", qualification.workload());
    assertEquals(
        "4b27eba8f166c84ef19c53de825445a6d0097f9bd8efa20b2d7013f34621f83c",
        qualification.corpusSha256());
    assertEquals("chatml-no-think", qualification.promptTemplate());
    assertEquals(
        "trusted-title-provenance-statement-anchors-safe-discourse-explicit-abstention-v14",
        qualification.groundingPolicy());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(2.0 / 3.0, qualification.rawCorrectAnswerRate());
    assertEquals(4.0 / 9.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(4.0 / 9.0, qualification.extractiveFallbackRate());
    assertEquals(15.925965398250405, qualification.p50DecodeTokensPerSecond());
    assertEquals(5_318.8749972, qualification.p95EndToEndMillis());
    assertEquals(
        "ef419f16217b83d76db30acd312292b63b74f90ce1f81173601d31a0c1cedb36",
        qualification.reportSha256());
    assertEquals(
        URI.create(
            "https://github.com/integrallis/models/blob/"
                + registry.modelsRevision()
                + "/benchmark-results/certified-20260726/rag/"
                + "smollm3-3b-q4_k_m/models-rust-ffm-marker.json"),
        qualification.reportUri());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedTinyLlamaEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();
    ModelRagQualification qualification =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("tinyllama_1_1b_chat_v1_0_q4_0"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals(AGGREGATE_MODELS_REVISION, registry.modelsRevision());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(
        "models@5c59bdf30ce29b039dfcce461b490f95b929976a "
            + "modeljars@a2a9120 "
            + "vectors@c298a0b73970468794c1ba403022e2adc517e57e",
        qualification.backendVersion());
    assertEquals("general", qualification.workload());
    assertEquals(
        "4b27eba8f166c84ef19c53de825445a6d0097f9bd8efa20b2d7013f34621f83c",
        qualification.corpusSha256());
    assertEquals("zephyr", qualification.promptTemplate());
    assertEquals(
        "trusted-title-provenance-statement-anchors-safe-discourse-explicit-abstention-v15",
        qualification.groundingPolicy());
    assertEquals("USABLE", qualification.performanceTier());
    assertEquals(RagUseCaseTier.GUARDED_RAG, qualification.useCaseTier());
    assertEquals(27, qualification.attempts());
    assertEquals(1.0, qualification.correctAnswerRate());
    assertEquals(0.0, qualification.rawCorrectAnswerRate());
    assertEquals(1.0 / 3.0, qualification.modelAnswerRate());
    assertEquals(1.0, qualification.modelAnswerCorrectRate());
    assertEquals(5.0 / 9.0, qualification.extractiveFallbackRate());
    assertEquals(34.25645729502951, qualification.p50DecodeTokensPerSecond());
    assertEquals(3_188.5273641999997, qualification.p95EndToEndMillis());
    assertEquals(
        "5e4f6a778fbaa509be93d81747c1406abd282dc4521f8f442baf39d0d6ab465c",
        qualification.reportSha256());
    assertEquals(
        URI.create(
            "https://github.com/integrallis/models/blob/"
                + registry.modelsRevision()
                + "/benchmark-results/certified-20260726/rag/"
                + "tinyllama-1.1b-q4_0/models-rust-ffm-marker.json"),
        qualification.reportUri());
    assertTrue(qualification.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesQualifiedNexusVerticalEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification finance =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("king3djbl_nexus_finance_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();
    ModelRagQualification legal =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("king3djbl_nexus_legal_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();
    ModelRagQualification medical =
        registry.qualifications().stream()
            .filter(entry -> entry.modelId().equals("king3djbl_nexus_medical_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals(AGGREGATE_MODELS_REVISION, registry.modelsRevision());
    assertEquals("finance", finance.workload());
    assertEquals(5.0 / 9.0, finance.modelAnswerRate());
    assertEquals(30.16945787255821, finance.p50DecodeTokensPerSecond());
    assertEquals(
        "42035af3e519215887dfb2633588c8ea04a1e5dc29ff55456517b0464dc1e9be",
        finance.reportSha256());
    assertEquals("legal", legal.workload());
    assertEquals(4.0 / 9.0, legal.modelAnswerRate());
    assertEquals(27.75538941546097, legal.p50DecodeTokensPerSecond());
    assertEquals(
        "68a57876f44b951fa5f6a67f8554ab76a46279f16f17d6dca3d4eca3439a1ad7",
        legal.reportSha256());
    assertEquals("healthcare", medical.workload());
    assertEquals(4.0 / 9.0, medical.modelAnswerRate());
    assertEquals(29.37754596980454, medical.p50DecodeTokensPerSecond());
    assertEquals(
        "468f7ede0fdc8487201515b639df8bbeeb73fbdae1af70a42a4517681065140b",
        medical.reportSha256());
    assertTrue(finance.productionUsable());
    assertTrue(legal.productionUsable());
    assertTrue(medical.productionUsable());
  }

  @Test
  void aggregateCatalogPublishesFinalLaunchEvidence() {
    ModelRagQualificationRegistry registry = ModelRagQualificationRegistry.fromClasspath();

    ModelRagQualification smolLm =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry
                        .modelId()
                        .equals("huggingfacetb_smollm2_1_7b_instruct_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();
    ModelRagQualification danube =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry
                        .modelId()
                        .equals("h2oai_h2o_danube2_1_8b_chat_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();
    ModelRagQualification yiCoder =
        registry.qualifications().stream()
            .filter(
                entry ->
                    entry
                        .modelId()
                        .equals("bartowski_yi_coder_1_5b_chat_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();

    assertEquals(AGGREGATE_QUALIFIED_MODELS, registry.qualifiedModels());
    assertEquals(AGGREGATE_MODELS_REVISION, registry.modelsRevision());
    assertEquals("chatml", smolLm.promptTemplate());
    assertEquals(15.0 / 27.0, smolLm.modelAnswerRate());
    assertEquals(22.83409323014655, smolLm.p50DecodeTokensPerSecond());
    assertEquals(
        "128dce8b4c8b5eae1e1c1be7cca8c155b144506e33c37b1cb0479dd928af0968",
        smolLm.reportSha256());
    assertEquals("h2o-direct", danube.promptTemplate());
    assertEquals(1.0 / 3.0, danube.modelAnswerRate());
    assertEquals(26.092233851604476, danube.p50DecodeTokensPerSecond());
    assertEquals(
        "3fca8449921c16205d24d60cff87b9162ed933fb3871748b1dcada56ff77686e",
        danube.reportSha256());
    assertEquals("chatml-answer", yiCoder.promptTemplate());
    assertEquals(15.0 / 27.0, yiCoder.modelAnswerRate());
    assertEquals(29.737367126827113, yiCoder.p50DecodeTokensPerSecond());
    assertEquals(
        "7213773355906a05cf1e9c42991246c78265cb775cac2bc87e05e209e787f612",
        yiCoder.reportSha256());
    assertEquals(
        URI.create(
            "https://github.com/integrallis/models/blob/"
                + AGGREGATE_MODELS_REVISION
                + "/benchmark-results/certified-20260726/rag/"
                + "yi-coder-1.5b-q4_k_m/models-rust-ffm.json"),
        yiCoder.reportUri());
    assertTrue(smolLm.productionUsable());
    assertTrue(danube.productionUsable());
    assertTrue(yiCoder.productionUsable());
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

    Properties insufficientContribution = properties(1.0, 0.32, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelRagQualificationRegistry.fromProperties(insufficientContribution));

    Properties incorrectModelAnswers = properties(1.0, 1.0, true);
    incorrectModelAnswers.setProperty(
        "qualification.qwen3_0_6b_q4_0.modelAnswerCorrectRate", "0.89");
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelRagQualificationRegistry.fromProperties(incorrectModelAnswers));
  }

  private static Properties properties(
      double rawCorrectAnswerRate, double modelAnswerRate, boolean qualified) {
    String prefix = "qualification.qwen3_0_6b_q4_0.";
    Properties properties = new Properties();
    properties.setProperty("modeljars.qualifications.schemaVersion", "1");
    properties.setProperty("modeljars.qualifications.generatedAt", "2026-07-24T06:00:00Z");
    properties.setProperty(
        "modeljars.qualifications.policyVersion",
        "production-rag-model-contribution-v4");
    properties.setProperty("modeljars.qualifications.modelsRevision", MODELS_REVISION);
    properties.setProperty("modeljars.qualifications.targetQualifiedModels", "25");
    properties.setProperty("modeljars.qualifications.qualifiedModels", qualified ? "1" : "0");
    properties.setProperty("modeljars.qualifications.rejectedModels", qualified ? "0" : "1");
    properties.setProperty(prefix + "model", "Qwen3 0.6B Q4_0");
    properties.setProperty(prefix + "backend", "llama.cpp");
    properties.setProperty(prefix + "backendVersion", "b10012-c71854292");
    properties.setProperty(prefix + "workload", "general");
    properties.setProperty(prefix + "corpusSha256", "d".repeat(64));
    properties.setProperty(prefix + "promptTemplate", "chatml");
    properties.setProperty(
        prefix + "groundingPolicy",
        "trusted-provenance-clause-anchors-extractive-fallback-v4");
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
    properties.setProperty(prefix + "modelAnswerCorrectRate", "1.0");
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
