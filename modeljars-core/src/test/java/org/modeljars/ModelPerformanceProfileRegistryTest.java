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

    assertEquals(13, registry.profiles().size());
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
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile
                            .id()
                            .equals("smollm2_360m_q8_0_epyc_milan_jdk25_row_accumulator")
                        && "true"
                            .equals(
                                profile
                                    .recommendations()
                                    .get("models.purejava.q8BlockMajorRowAccumulator"))
                        && profile
                            .evidence()
                            .benchmarkId()
                            .equals("smollm2-q8-row-accumulator-20260723")
                        && profile.evidence().warmups() == 5
                        && profile.evidence().trials() == 30
                        && profile.evidence().generatedTokens() == 1
                        && profile.evidence().outputHashesMatch()
                        && profile
                            .evidence()
                            .controls()
                            .get("vectorsCandidateCommit")
                            .equals("4e9eaca7b0ed928e183f3892a210460e3fcbe288")
                        && profile
                            .evidence()
                            .controls()
                            .get("modelsCandidateCommit")
                            .equals("4d339450b0a82d051b9257498271119396d878f7")));
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
