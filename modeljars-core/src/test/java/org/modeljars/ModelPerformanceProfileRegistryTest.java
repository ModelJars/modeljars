package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    Map<String, String> runtime =
        Map.of(
            "os", "Linux",
            "architecture", "amd64",
            "processors", "8",
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

    assertEquals(3, registry.profiles().size());
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("qwen3_0_6b_q4_0_epyc_milan_jdk25")
                        && profile.recommendations().get("compiler").equals("hotspot-c2")));
    assertTrue(
        registry.profiles().stream()
            .anyMatch(
                profile ->
                    profile.id().equals("smollm2_360m_q8_0_epyc_milan_jdk25")
                        && profile.recommendations().get("compiler").equals("graal-jvmci")));
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
    properties.setProperty(prefix + "selector.active-vector-bits", "256");
    properties.setProperty(prefix + "recommendation.compiler", "graal-jvmci");
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
        Optional.of(URI.create("https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF")),
        Optional.empty(),
        Optional.empty(),
        Optional.of(sha),
        Optional.of(386_404_992L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation"),
        Set.of(),
        Map.of("pure-java", true));
  }
}
