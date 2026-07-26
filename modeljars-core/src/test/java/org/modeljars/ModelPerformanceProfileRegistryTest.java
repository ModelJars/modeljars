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

class ModelPerformanceProfileRegistryTest {

  private static final String SHA =
      "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201";
  private static final ModelJarCoordinate COORDINATE =
      ModelJarCoordinate.parse(
          "org.modeljars.huggingface:huggingfacetb.smollm2-360m-instruct-gguf.q8_0:2.0.0-q8_0.1");

  @Test
  void loadsTypedEvidenceAndMatchesOnlyTheExactArtifactAndRuntime() {
    ModelPerformanceProfileRegistry registry =
        ModelPerformanceProfileRegistry.fromProperties(profileProperties(true));

    ModelPerformanceProfile profile = registry.profiles().getFirst();
    assertEquals("smollm2_360m_q8_0_epyc_milan_jdk25", profile.id());
    assertEquals("graal-jvmci", profile.recommendations().get("compiler"));
    assertEquals(21.226575, profile.evidence().baselineMetrics().get("decodeTokensPerSecond"));
    assertEquals(44.703344, profile.evidence().candidateMetrics().get("decodeTokensPerSecond"));
    assertEquals(Instant.parse("2026-07-18T18:52:34.731627458Z"), profile.evidence().measuredAt());
    assertTrue(profile.safeForAutomaticSelection());

    JavaLaunchProfile launch = profile.javaLaunch().orElseThrow();
    assertEquals("graal-jvmci", launch.runtime());
    assertEquals(25, launch.javaFeature());
    assertEquals(
        List.of(
            "-Djdk.graal.MaximumInliningSize=10000",
            "-XX:+UseSerialGC"),
        launch.jvmArguments());
    assertEquals(
        List.of(
            "java",
            "-Djdk.graal.MaximumInliningSize=10000",
            "-XX:+UseSerialGC",
            "-cp",
            "app.jar",
            "example.Main"),
        launch.command("java", List.of("-cp", "app.jar", "example.Main")));
    assertEquals(
        List.of("-XX:+UseSerialGC"),
        launch.missingArguments(List.of("-Djdk.graal.MaximumInliningSize=10000")));

    Map<String, String> runtime =
        Map.of(
            "os", "Linux",
            "architecture", "amd64",
            "processors", "8",
            "java-feature", "25",
            "compiler", "graal-jvmci",
            "active-vector-bits", "256");
    assertEquals(1, registry.matching(descriptor(SHA), "pure-java", runtime).size());
    assertTrue(
        registry
            .matching(descriptor(SHA), "pure-java", Map.of("os", "macOS"))
            .isEmpty());
    assertTrue(registry.matching(descriptor("0".repeat(64)), "pure-java", runtime).isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> profile.recommendations().put("compiler", "hotspot-c2"));
  }

  @Test
  void correctnessMismatchRemainsVisibleButCannotDriveAutomaticSelection() {
    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromProperties(profileProperties(false))
            .profiles()
            .getFirst();

    assertFalse(profile.safeForAutomaticSelection());
    assertFalse(profile.evidence().outputHashesMatch());
  }

  @Test
  void launchProfileCanBeTheOnlyAutomaticRecommendation() {
    Properties properties = profileProperties(true);
    properties.remove("profile.smollm2_360m_q8_0_epyc_milan_jdk25.recommendation.compiler");

    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromProperties(properties).profiles().getFirst();

    assertTrue(profile.recommendations().isEmpty());
    assertTrue(profile.javaLaunch().isPresent());
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void recommendationOnlyProfilesDoNotRequireLaunchMetadata() {
    Properties properties = profileProperties(true);
    properties.stringPropertyNames().stream()
        .filter(name -> name.contains(".launch."))
        .toList()
        .forEach(properties::remove);

    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromProperties(properties).profiles().getFirst();

    assertTrue(profile.javaLaunch().isEmpty());
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void rejectsIncompleteOrNonContiguousLaunchMetadata() {
    Properties missingFeature = profileProperties(true);
    missingFeature.remove("profile.smollm2_360m_q8_0_epyc_milan_jdk25.launch.javaFeature");
    assertThrows(
        ModelJarException.class,
        () -> ModelPerformanceProfileRegistry.fromProperties(missingFeature));

    Properties gappedArguments = profileProperties(true);
    gappedArguments.remove(
        "profile.smollm2_360m_q8_0_epyc_milan_jdk25.launch.jvmArgument.000");
    assertThrows(
        ModelJarException.class,
        () -> ModelPerformanceProfileRegistry.fromProperties(gappedArguments));
  }

  @Test
  void rejectsDuplicateJvmArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JavaLaunchProfile(
                "graal-jvmci",
                25,
                List.of(
                    "-Djdk.graal.MaximumInliningSize=10000",
                    "-Djdk.graal.MaximumInliningSize=10000")));
  }

  @Test
  void rejectsLaunchMetadataThatConflictsWithItsRuntimeSelector() {
    Properties wrongFeature = profileProperties(true);
    wrongFeature.setProperty(
        "profile.smollm2_360m_q8_0_epyc_milan_jdk25.selector.java-feature", "26");
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelPerformanceProfileRegistry.fromProperties(wrongFeature));

    Properties wrongRuntime = profileProperties(true);
    wrongRuntime.setProperty(
        "profile.smollm2_360m_q8_0_epyc_milan_jdk25.selector.compiler", "hotspot-c2");
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelPerformanceProfileRegistry.fromProperties(wrongRuntime));
  }

  @Test
  void loadsEveryVersionedClasspathResource(@TempDir Path root) throws Exception {
    Path resource = root.resolve(ModelPerformanceProfileRegistry.RESOURCE);
    Files.createDirectories(resource.getParent());
    try (var output = Files.newOutputStream(resource)) {
      profileProperties(true).store(output, null);
    }

    try (var loader = new java.net.URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, null)) {
      ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath(loader);
      assertEquals(1, registry.profiles().size());
      assertEquals(COORDINATE, registry.profiles().getFirst().markerCoordinate());
    }
  }

  @Test
  void rejectsUnknownSchemaVersions() {
    Properties properties = profileProperties(true);
    properties.setProperty("modeljars.performance.schemaVersion", "2");

    assertThrows(
        ModelJarException.class,
        () -> ModelPerformanceProfileRegistry.fromProperties(properties));
  }

  @Test
  void aggregateCatalogPublishesControlledCompilerComparisons() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();

    assertEquals(33, registry.profiles().size());
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("qwen3_0_6b_q4_0_epyc_milan_jdk25")
                        && profile.javaLaunch().isPresent()
                        && profile.javaLaunch().orElseThrow().runtime().equals("graal-jvmci")
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(List.of("-Djdk.graal.MaximumInliningSize=10000"))
                        && "unsigned-pairwise"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.q4Kernel"))
                        && "true"
                            .equals(
                                profile
                                    .runtimeSelector()
                                    .get("q4-unsigned-pairwise-supported"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("graal-q4-unsigned-pairwise-20260721")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("7e6c1e3991f96d7a15e0228d0d0e8edf2730f3fe")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsMergeCommit")
                            .equals("e9b98c9cd6eb90ff7f8a80091498e705923c60e0")
                        && profile
                            .evidence()
                            .controls()
                            .get("batchedAttentionValues")
                            .equals("true")
                        && profile
                            .evidence()
                            .controls()
                            .get("batchedAttentionScores")
                            .equals("true")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals(
                            "qwen3_0_6b_q4_0_epyc_milan_jdk25_batched_values")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.batchedAttentionValues"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen-batched-attention-values-20260721")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals(
                            "qwen3_0_6b_q4_0_epyc_milan_jdk25_batched_scores")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.batchedAttentionScores"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen-batched-attention-scores-20260721")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("qwen3_0_6b_q4_0_epyc_milan_jdk25_staged_ffn")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.stagedQuantizedFfn"))
                        && "true"
                            .equals(
                                profile
                                    .runtimeSelector()
                                    .get("q4-unsigned-pairwise-supported"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen-staged-q4-ffn-20260722")
                        && profile.evidence().warmups() == 10
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsMergeCommit")
                            .equals("6891bbf72e771c8061068ae499cef7e97926e822")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("49376ac9e2ec5b05581900596c61204dde8d0de8")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("qwen3_0_6b_q4_0_epyc_milan_jdk25_batch_24")
                        && "24"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.prefillBatchSize"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen-prefill-batch-24-20260722")
                        && profile.evidence().warmups() == 10
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("64caf14fba758593a1769cfb0587da8cc20d73a2")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("qwen3_0_6b_q4_0_epyc_milan_jdk25_staged_layer")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.stagedQuantizedLayer"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen-staged-q4-layer-20260722")
                        && profile.evidence().warmups() == 10
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsCandidateCommit")
                            .equals("b03bef831894b819a81aa4912f3102b953c78331")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("64caf14fba758593a1769cfb0587da8cc20d73a2")
                        && profile
                            .evidence()
                            .controls()
                            .get("prefillProcessPairWins")
                            .equals("5-of-6")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("smollm2_360m_q8_0_epyc_milan_jdk25")
                        && profile.recommendations().get("compiler").equals("graal-jvmci")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("smollm2_360m_q8_0_epyc_milan_jdk25_rust_ffm")
                        && profile.backend().equals("rust-ffm")
                        && profile.recommendations().isEmpty()
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-Djdk.graal.MaximumInliningSize=10000"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-q8-rust-ffm-rag-20260724")
                        && profile.evidence().trials() == 27
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "879f95209b4a25a3047affe492b679e56addf7bf361baaa28643879d525178b3")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("qwen3_1_7b_q8_0_epyc_milan_jdk25_rust_ffm")
                        && profile.backend().equals("rust-ffm")
                        && profile.recommendations().isEmpty()
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-Djdk.graal.MaximumInliningSize=10000"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen3-1.7b-q8-rust-ffm-rag-20260724")
                        && profile.evidence().trials() == 27
                        && !profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("semanticQualityParity")
                            .equals("27-of-27")
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "bdf55206cbd49e81e9ce1681f526a406ead2b6e0412811fbe51c3e56f95ac323")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals(
                                "qwen2_5_coder_0_5b_q8_0_epyc_milan_jdk25_rust_ffm_coding")
                        && profile.backend().equals("rust-ffm")
                        && profile.recommendations().isEmpty()
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(List.of("--enable-native-access=ALL-UNNAMED"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen2.5-coder-0.5b-q8-rust-ffm-coding-20260724")
                        && profile.evidence().trials() == 27
                        && !profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("workload")
                            .equals("coding")
                        && profile
                            .evidence()
                            .controls()
                            .get("finalGroundedAnswerParity")
                            .equals("27-of-27")
                        && profile
                            .evidence()
                            .controls()
                            .get("baselineReportSha256")
                            .equals(
                                "7b4a7865b1a545067fab9eb266448312afc5d45ba65abfafeda3542f43ab87f2")
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "c674fa510c1866b7425b953dcc4f15edc38a02e60f4e0e3aee50a2e46be24277")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals(
                                "qwen2_5_coder_0_5b_q4_0_epyc_milan_jdk25_rust_ffm_coding")
                        && profile.backend().equals("rust-ffm")
                        && profile
                            .recommendations()
                            .get("models.purejava.q4Kernel")
                            .equals("unsigned-pairwise")
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-Djdk.graal.MaximumInliningSize=10000",
                                    "-Dvectors.gguf.threads=4"))
                        && profile.safeForAutomaticSelection()
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen2.5-coder-0.5b-q4-hybrid-coding-20260724")
                        && profile.evidence().trials() == 27
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("workload")
                            .equals("coding")
                        && profile
                            .evidence()
                            .controls()
                            .get("baselineReportSha256")
                            .equals(
                                "31c1c1670db11337c14b5f52f8345df0cd61823ec0789e59aff5f856f082c4ab")
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "f09336788d9dfbead64dacbbfa68d756c34f5acb6cc4122f741bc0bcbb737f72")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals(
                                "qwen2_5_coder_1_5b_q4_0_epyc_milan_jdk25_rust_ffm_coding")
                        && profile.backend().equals("rust-ffm")
                        && profile
                            .recommendations()
                            .get("models.purejava.q4Kernel")
                            .equals("unsigned-pairwise")
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-Djdk.graal.MaximumInliningSize=10000",
                                    "-Dvectors.gguf.threads=4"))
                        && profile.safeForAutomaticSelection()
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen2.5-coder-1.5b-q4-hybrid-coding-20260724")
                        && profile.evidence().trials() == 27
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("pairedRawOutputSha256")
                            .equals(
                                "6371b16b864fbe846dd7f2b03e0439f89ff357733e0d9443f9a45f9d63d47fdc")
                        && profile
                            .evidence()
                            .controls()
                            .get("baselineReportSha256")
                            .equals(
                                "a2d6e6d5bcd91886595424b28487378dd7e75aa0e8f00f6ce9abbb7930979e45")
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "9e98947ae5747ef7d433b88fc4b174863667f4a7b4b6b82ed4eeb448adf18e31")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals(
                                "qwen2_5_coder_1_5b_q8_0_epyc_milan_jdk25_rust_ffm_coding")
                        && profile.backend().equals("rust-ffm")
                        && profile.recommendations().isEmpty()
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(List.of("--enable-native-access=ALL-UNNAMED"))
                        && !profile.safeForAutomaticSelection()
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("qwen2.5-coder-1.5b-q8-rust-ffm-coding-20260724")
                        && profile.evidence().trials() == 27
                        && !profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("pairedRawOutputs")
                            .equals("different-semantic-parity-verified")
                        && profile
                            .evidence()
                            .controls()
                            .get("baselineReportSha256")
                            .equals(
                                "87034690196fdc3ecf3d506de039df9eed2e9e138528488d9f0dc16c67ff3f54")
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "8aae6eb24601b031acaaf33998f39e5c753eab106a27ec99242c2db2bd13db66")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("sqlcoder_7b_2_q5_k_m_epyc_milan_jdk25_rust_ffm")
                        && profile.backend().equals("rust-ffm")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.native.quantizedDecode"))
                        && "4"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.native.kernels.threads"))
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-XX:ActiveProcessorCount=4"))
                        && profile.safeForAutomaticSelection()
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("sqlcoder-q5-k-native-decode-20260725")
                        && profile.evidence().trials() == 10
                        && profile.evidence().generatedTokens() == 4
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("candidateReportSha256")
                            .equals(
                                "a20e25c0afa2371b83eed5cc1b65e2c85857c31dbdbf2e88e2a6f761280939c0")
                        && profile
                            .evidence()
                            .controls()
                            .get("comparisonReportSha256")
                            .equals(
                                "24705f2fd6107a6a624a62730c1553a22589de30024b1437f28663a2c79db1dd")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("smollm2_360m_q8_0_epyc_milan_jdk25_staged_layer")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.stagedQuantizedFfn"))
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.stagedQuantizedLayer"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-staged-q8-layer-20260723")
                        && profile.evidence().warmups() == 5
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsCandidateCommit")
                            .equals("fa24b91ac6fa14cf5a46f47fc5d945b9b4494c35")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("smollm2_360m_q8_0_epyc_milan_jdk25_block_major")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.blockMajorQ8Activations"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-q8-block-major-20260723")
                        && profile.evidence().warmups() == 5
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsCandidateCommit")
                            .equals("d295f32c0ce827a54f89fd2fa0088384926653cc")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsCandidateCommit")
                            .equals("29b51694f2c524602c3f8c868f324dde4674547d")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("64a72b90b62644fc2eb02bdf9b965fc2e5c71337")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsMergeCommit")
                            .equals("d07216ea1261fae968b66fd96580a5b30815148e")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("smollm2_360m_q8_0_epyc_milan_jdk25_parallel_ffn")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.parallelQ8FfnPreparation"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-q8-parallel-ffn-preparation-20260723")
                        && profile.evidence().warmups() == 5
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsCandidateCommit")
                            .equals("4dcf9352b9330d6c48846e6889755d78282a8ea8")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsCandidateCommit")
                            .equals("4887cdef57d6feedb085b709391c0f5e54755b62")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("523f3aa95f503a35babc02b76e5537df5f06891c")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsMergeCommit")
                            .equals("423621b2aac66c2561d50208ec6a0274053b1590")));
    assertFalse(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                        .id()
                        .equals("smollm2_360m_q8_0_epyc_milan_jdk25_row_accumulator")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("smollm2_360m_q8_0_epyc_milan_jdk25_float_lane")
                        && "float-lane-accumulated"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.q8BlockMajorKernel"))
                        && profile
                            .javaLaunch()
                            .orElseThrow()
                            .jvmArguments()
                            .equals(
                                List.of(
                                    "-Djdk.graal.MaximumInliningSize=10000",
                                    "-XX:CompileCommand=option,com.integrallis.vectors.core.PanamaVectorUtilSupport::ggufQ8_0Q8_0BlockMajorFloatLaneRow,double,CompileThresholdScaling,0.001",
                                    "-XX:CompileCommand=option,com.integrallis.vectors.core.PanamaVectorUtilSupport::ggufQ8_0Q8_0BlockMajorFloatLaneRow,bool,BackgroundCompilation,false"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-q8-float-lane-20260723")
                        && profile.evidence().warmups() == 5
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsCandidateCommit")
                            .equals("1f00848da50a4a11a2ef1792527590e2cc319218")
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsMergeCommit")
                            .equals("fde9858901624d1661a1cf51195d2c59737bcf87")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsCandidateCommit")
                            .equals("2aef4dd07ff33c9841d86deb8d46a74e4a5f0828")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsMergeCommit")
                            .equals("45afa8742f2e901b3884fe6993b5dc58887368ef")
                        && profile
                            .evidence()
                            .controls()
                            .get("internallyDeterministic")
                            .equals("true")
                        && profile
                            .evidence()
                            .controls()
                            .get("crossKernelSequenceAgreement")
                            .equals("not-required-non-associative")
                        && profile
                            .evidence()
                            .controls()
                            .get("sqlFloatLaneLlamaCommonPrefix")
                            .equals("64-of-64")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("minicpm5_1b_q4_k_m_epyc_milan_jdk25_mixed_k")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.mixedKProjections"))));
  }

  @Test
  void aggregateCatalogPublishesQualifiedEuroLlmRustFfmProfile() {
    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromClasspath().profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("eurollm_1_7b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "1cade17f491ea46a686dbee51fbd52442e0f001f102380c3b9d66b4a77f84093",
        profile.artifactSha256());
    assertEquals("64", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25GeneralRustFfmProfile() {
    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromClasspath().profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("qwen2_5_0_5b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        profile.artifactSha256());
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25OnePointFiveBillionRustFfmProfile() {
    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromClasspath().profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("qwen2_5_1_5b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        profile.artifactSha256());
    assertEquals("64", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedUmarTransitRustFfmProfile() {
    ModelPerformanceProfile profile =
        ModelPerformanceProfileRegistry.fromClasspath().profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("umartransit_1b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "db1a4489626110145274f508b3fa30439516a47b4e721fe02d67df4679db5b9a",
        profile.artifactSha256());
    assertEquals("64", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedMiniCpm5RustFfmProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("minicpm5_1b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals(33, registry.profiles().size());
    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa",
        profile.artifactSha256());
    assertEquals("64", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedLlama32OneBillionRustFfmProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("llama_3_2_1b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals(33, registry.profiles().size());
    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
        profile.artifactSha256());
    assertEquals("64", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("true", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedGemma3OneBillionRustFfmProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("gemma_3_1b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d",
        profile.artifactSha256());
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertFalse(profile.recommendations().containsKey("models.purejava.prefillBatchSize"));
    assertFalse(profile.recommendations().containsKey("models.purejava.batchedAttentionScores"));
    assertFalse(profile.recommendations().containsKey("models.purejava.batchedAttentionValues"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedIndianLegalQwenThreeBillionRustFfmProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals(
                            "indian_legal_qwen2_5_3b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "20e09a60606859d9a5401f4d261d02c1a1c57b75ee322a10b034cdbf2506fcb5",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "893d074044ab1955ee3a4b91b67750440f12dbe683c0ddf3fd7503f04daed6e8",
        profile.evidence().controls().get("candidateReportSha256"));
    assertEquals("27-identical", profile.evidence().controls().get("pairedOutputHashes"));
    assertEquals(
        "0.8425535918769786", profile.evidence().controls().get("decodeRatioToOllama"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedDeepSeekR1DistillQwenOnePointFiveBillionProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals(
                            "deepseek_r1_distill_qwen_1_5b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "1741e5b2d062b07acf048bf0d2c514dadf2a48f94e2b4aa0cfe069af3838ee2f",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "5b1ba2c93ec7ea793fc0e74ed796d06729bf56689d0dc65a293264b5bdc07624",
        profile.evidence().controls().get("candidateReportSha256"));
    assertEquals("27-identical", profile.evidence().controls().get("pairedOutputHashes"));
    assertEquals(
        "0.9894992519851347", profile.evidence().controls().get("decodeRatioToOllama"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedQwen25MathOnePointFiveBillionProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("qwen2_5_math_1_5b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "9614a50f03c897028920ca0dc4365da570bf587f9ee7768261216fe370b37e8e",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "ac0e13ea84dc179ecbbb16e345191f992de43c8729f13b66521656a7289d9e61",
        profile.evidence().controls().get("candidateReportSha256"));
    assertEquals(". ", profile.evidence().controls().get("stopSequences"));
    assertEquals(
        "dd75d510ceebf71606e69aa80d6d7624173755e9",
        profile.evidence().controls().get("modelJarsCommitAtMeasurement"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedLlama32ThreeBillionRustFfmProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("llama_3_2_3b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "86d77fe27372764fe0d60c88bf1be7a0ef4b767f8cb03c891bc6e3d470c4a42c",
        profile.evidence().controls().get("candidateReportSha256"));
    assertEquals(
        "e1d568fbf36bf87b8e1e2ee6d245e6374be7cd8865c0c11e1d2464c966a296c6",
        profile.evidence().controls().get("rejectedAttentionReportSha256"));
    assertEquals(
        "ce2cafecd145ae37fbd879fcc4c8cf7b721b51c3ad90ab56fc35b6ff0d705b93",
        profile.evidence().controls().get("directReportSha256"));
    assertEquals(
        "ad3d2d4d68403fe8fea544877d421097e0fc65f7",
        profile.evidence().controls().get("modelJarsCommitAtMeasurement"));
    assertEquals(
        "60e441df4a3e3d0f6798851c6861f85f8c885933",
        profile.evidence().controls().get("modelsEvidenceCommit"));
    assertEquals("27-identical", profile.evidence().controls().get("pairedOutputHashes"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedDeepSeekCoderOnePointThreeBillionProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals("deepseek_coder_1_3b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "04cebb6fafa40ae628cf6bfeb76032ec792852f54020c559ad0a56b9f2839118",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "2755a19a7b1f0c6538c3e835bef104b6de8a0c843ccae266a79567c2eb280d83",
        profile.evidence().controls().get("baselineReportSha256"));
    assertEquals(
        "6d4f78972e2f4ec3f9646a312ec05837fbb1d948a6f6e26ae122b33005595cc1",
        profile.evidence().controls().get("candidateReportSha256"));
    assertEquals(
        "0b50df8", profile.evidence().controls().get("modelJarsCommitAtMeasurement"));
    assertEquals(
        "6cea1f5d58861d898f1f4fab6f0b97bef3fd33ba",
        profile.evidence().controls().get("modelsEvidenceCommit"));
    assertEquals("deepseek", profile.evidence().controls().get("promptTemplate"));
    assertEquals(
        "trusted-title-provenance-statement-anchors-safe-discourse-explicit-abstention-v14",
        profile.evidence().controls().get("groundingPolicy"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  @Test
  void aggregateCatalogPublishesQualifiedSmolLmThreeBillionProfile() {
    ModelPerformanceProfileRegistry registry = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        registry.profiles().stream()
            .filter(
                candidate ->
                    candidate.id().equals("smollm3_3b_q4_k_m_epyc_milan_jdk25_rust_ffm"))
            .findFirst()
            .orElseThrow();

    assertEquals("rust-ffm", profile.backend());
    assertEquals(
        "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e",
        profile.artifactSha256());
    assertEquals("32", profile.recommendations().get("models.purejava.prefillBatchSize"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionScores"));
    assertEquals("false", profile.recommendations().get("models.purejava.batchedAttentionValues"));
    assertEquals("true", profile.recommendations().get("models.native.quantizedDecode"));
    assertEquals("8", profile.recommendations().get("models.native.kernels.threads"));
    assertEquals(
        "d4083f981c07f71ac89855568d7405a7658fbeaf6fbc912e05ea8ab347599f4f",
        profile.evidence().controls().get("baselineReportSha256"));
    assertEquals("chatml-no-think", profile.evidence().controls().get("promptTemplate"));
    assertEquals(
        "trusted-title-provenance-statement-anchors-safe-discourse-explicit-abstention-v14",
        profile.evidence().controls().get("groundingPolicy"));
    assertTrue(profile.safeForAutomaticSelection());
  }

  private static Properties profileProperties(boolean outputHashesMatch) {
    String prefix = "profile.smollm2_360m_q8_0_epyc_milan_jdk25.";
    Properties properties = new Properties();
    properties.setProperty("modeljars.performance.schemaVersion", "1");
    properties.setProperty(prefix + "modelAlias", "smollm2_360m_instruct_q8_0");
    properties.setProperty(prefix + "markerCoordinate", COORDINATE.toString());
    properties.setProperty(prefix + "artifactSha256", SHA);
    properties.setProperty(prefix + "backend", "pure-java");
    properties.setProperty(prefix + "selector.os", "Linux");
    properties.setProperty(prefix + "selector.architecture", "amd64");
    properties.setProperty(prefix + "selector.processors", "8");
    properties.setProperty(prefix + "selector.java-feature", "25");
    properties.setProperty(prefix + "selector.compiler", "graal-jvmci");
    properties.setProperty(prefix + "selector.active-vector-bits", "256");
    properties.setProperty(prefix + "recommendation.compiler", "graal-jvmci");
    properties.setProperty(prefix + "launch.runtime", "graal-jvmci");
    properties.setProperty(prefix + "launch.javaFeature", "25");
    properties.setProperty(
        prefix + "launch.jvmArgument.000", "-Djdk.graal.MaximumInliningSize=10000");
    properties.setProperty(
        prefix + "launch.jvmArgument.001", "-XX:+UseSerialGC");
    properties.setProperty(prefix + "evidence.benchmarkId", "inference-matrix-20260718");
    properties.setProperty(
        prefix + "evidence.measuredAt", "2026-07-18T18:52:34.731627458Z");
    properties.setProperty(prefix + "evidence.baseline", "hotspot-c2");
    properties.setProperty(prefix + "evidence.candidate", "graal-jvmci");
    properties.setProperty(prefix + "evidence.warmups", "2");
    properties.setProperty(prefix + "evidence.trials", "10");
    properties.setProperty(prefix + "evidence.generatedTokens", "64");
    properties.setProperty(
        prefix + "evidence.outputHashesMatch", Boolean.toString(outputHashesMatch));
    properties.setProperty(
        prefix + "evidence.baseline.metric.decodeTokensPerSecond", "21.226575");
    properties.setProperty(
        prefix + "evidence.baseline.metric.p95TtftMillis", "2643.733682");
    properties.setProperty(
        prefix + "evidence.candidate.metric.decodeTokensPerSecond", "44.703344");
    properties.setProperty(
        prefix + "evidence.candidate.metric.p95TtftMillis", "1299.085687");
    properties.setProperty(prefix + "evidence.control.seed", "42");
    properties.setProperty(prefix + "evidence.control.promptSha256", "2db2d875");
    return properties;
  }

  private static ModelJarDescriptor descriptor(String sha) {
    return new ModelJarDescriptor(
        "smollm2_360m_instruct_q8_0",
        "hf://HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
        COORDINATE,
        ModelVersion.parse("2.0.0"),
        "q8_0",
        "gguf",
        "llama",
        "Q8_0",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF")),
        Optional.empty(),
        Optional.empty(),
        Optional.of(sha),
        Optional.of(386_404_992L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation"),
        Set.of(),
        Map.of("pure-java", true),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        ModelDimensions.unknown());
  }
}
