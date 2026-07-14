package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathModelJarRegistryTest {
  @TempDir Path tempDir;

  @Test
  void deduplicatesTheSameMarkerFromAggregateAndIndividualJars() throws IOException {
    String marker =
        """
        model.example.sourceId=hf://example/model
        model.example.markerCoordinate=org.modeljars.huggingface:example.model.q4_0:1.0.0-q4_0.1
        model.example.modelVersion=1.0.0
        model.example.variant=q4_0
        model.example.format=gguf
        model.example.architecture=llama
        model.example.quantization=Q4_0
        model.example.capabilities=text-generation
        model.example.backend.llama.cpp=true
        """;
    Path aggregate = writeRegistry("aggregate", marker);
    Path individual = writeRegistry("individual", marker);

    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {aggregate.toUri().toURL(), individual.toUri().toURL()}, null)) {
      assertEquals(1, ClasspathModelJarRegistry.load(loader).descriptors().size());
    }
  }

  @Test
  void loadsRegistryPropertiesFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://ggml-org/Qwen3-0.6B-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("text-generation")
                    .build())
            .orElseThrow();

    assertEquals("qwen3_0_6b_q4_0", descriptor.alias());
    assertTrue(descriptor.localPath().orElseThrow().toString().endsWith("Qwen3-0.6B-Q4_0.gguf"));
  }

  private Path writeRegistry(String directory, String marker) throws IOException {
    Path root = tempDir.resolve(directory);
    Path resource = root.resolve(ClasspathModelJarRegistry.REGISTRY_RESOURCE);
    Files.createDirectories(resource.getParent());
    Files.writeString(resource, marker, StandardCharsets.ISO_8859_1);
    return root;
  }

  @Test
  void loadsQwen3OnePointSevenBillionMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen3-1.7B-GGUF")
                    .variant("q8_0")
                    .backend("pure-java")
                    .capability("text-generation")
                    .build())
            .orElseThrow();

    assertEquals("qwen3_1_7b_q8_0", descriptor.alias());
    assertEquals("qwen3", descriptor.architecture());
    assertEquals("Q8_0", descriptor.quantization());
    assertTrue(descriptor.localPath().orElseThrow().toString().endsWith("Qwen3-1.7B-Q8_0.gguf"));
  }

  @Test
  void loadsQwen3EightBillionMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen3-8B-GGUF")
                    .versionRange("[3.0.0,4.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("chat")
                    .build())
            .orElseThrow();

    assertEquals("qwen3_8b_q4_k_m", descriptor.alias());
    assertEquals("qwen3", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("7c41481f57cb95916b40956ab2f0b139b296d974", descriptor.revision().orElseThrow());
    assertEquals(
        "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
        descriptor.sha256().orElseThrow());
    assertEquals(5_027_783_488L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor.localPath().orElseThrow().toString().endsWith("Qwen3-8B-Q4_K_M.gguf"));
  }

  @Test
  void loadsQwen25CoderPureJavaCandidateMarkersFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor halfBillion =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();
    ModelJarDescriptor onePointFiveBillion =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();
    ModelJarDescriptor threeBillion =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen2.5-Coder-3B-Instruct-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();
    ModelJarDescriptor sevenBillion =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://Qwen/Qwen2.5-Coder-7B-Instruct-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();

    assertEquals("qwen2_5_coder_0_5b_instruct_q4_0", halfBillion.alias());
    assertEquals("qwen2", halfBillion.architecture());
    assertEquals("Q4_0", halfBillion.quantization());
    assertTrue(
        halfBillion
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("qwen2.5-coder-0.5b-instruct-q4_0.gguf"));

    assertEquals("qwen2_5_coder_1_5b_instruct_q4_0", onePointFiveBillion.alias());
    assertEquals("qwen2", onePointFiveBillion.architecture());
    assertEquals("Q4_0", onePointFiveBillion.quantization());
    assertTrue(
        onePointFiveBillion
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("qwen2.5-coder-1.5b-instruct-q4_0.gguf"));

    assertEquals("qwen2_5_coder_3b_instruct_q4_0", threeBillion.alias());
    assertEquals("qwen2", threeBillion.architecture());
    assertEquals("Q4_0", threeBillion.quantization());
    assertTrue(
        threeBillion
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("qwen2.5-coder-3b-instruct-q4_0.gguf"));

    assertEquals("qwen2_5_coder_7b_instruct_q4_0", sevenBillion.alias());
    assertEquals("qwen2", sevenBillion.architecture());
    assertEquals("Q4_0", sevenBillion.quantization());
    assertTrue(
        sevenBillion
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("qwen2.5-coder-7b-instruct-q4_0.gguf"));
  }

  @Test
  void loadsSmolLm2PureJavaCandidateMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://HuggingFaceTB/SmolLM2-360M-Instruct-GGUF")
                    .variant("q8_0")
                    .backend("pure-java")
                    .capability("chat")
                    .build())
            .orElseThrow();

    assertEquals("smollm2_360m_instruct_q8_0", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q8_0", descriptor.quantization());
    assertTrue(
        descriptor.localPath().orElseThrow().toString().endsWith("smollm2-360m-instruct-q8_0.gguf"));
  }

  @Test
  void loadsTinyLlamaSentencePieceCandidateMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_0")
                    .backend("pure-java")
                    .capability("chat")
                    .build())
            .orElseThrow();

    assertEquals("tinyllama_1_1b_chat_v1_0_q4_0", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q4_0", descriptor.quantization());
    assertEquals(
        "da3087fb14aede55fde6eb81a0e55e886810e43509ec82ecdc7aa5d62a03b556",
        descriptor.sha256().orElseThrow());
    assertEquals(637_699_456L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("tinyllama-1.1b-chat-v1.0.Q4_0.gguf"));
  }

  @Test
  void loadsDeepSeekCoderMixedQuantCandidateMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://TheBloke/deepseek-coder-1.3b-instruct-GGUF")
                    .versionRange("[1.3.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();

    assertEquals("deepseek_coder_1_3b_instruct_q4_k_m", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("LicenseRef-DeepSeek", descriptor.license().orElseThrow());
    assertEquals("4595af8c3dff738094bd6c86054dfb5a90d5c41e", descriptor.revision().orElseThrow());
    assertEquals(
        "04cebb6fafa40ae628cf6bfeb76032ec792852f54020c559ad0a56b9f2839118",
        descriptor.sha256().orElseThrow());
    assertEquals(873_582_624L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("deepseek-coder-1.3b-instruct.Q4_K_M.gguf"));
  }

  @Test
  void loadsDeepSeekCoderLargeMixedQuantCandidateMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://TheBloke/deepseek-coder-6.7B-instruct-GGUF")
                    .versionRange("[6.7.0,7.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("code-completion")
                    .build())
            .orElseThrow();

    assertEquals("deepseek_coder_6_7b_instruct_q4_k_m", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("LicenseRef-DeepSeek", descriptor.license().orElseThrow());
    assertEquals("9e221e6b41cb1bf1c5d8f9718e81e3dc781f7557", descriptor.revision().orElseThrow());
    assertEquals(
        "92da6238854f2fa902d8b2ad79d548536af1d3ab06821f323bd5bbcea2013276",
        descriptor.sha256().orElseThrow());
    assertEquals(4_083_015_904L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("deepseek-coder-6.7b-instruct.Q4_K_M.gguf"));
  }

  @Test
  void loadsDeepSeekR1DistillQwenSevenBillionMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("reasoning")
                    .build())
            .orElseThrow();

    assertEquals("deepseek_r1_distill_qwen_7b_q4_k_m", descriptor.alias());
    assertEquals("qwen2", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("MIT", descriptor.license().orElseThrow());
    assertEquals("361004151d4f4f6b446dc5e6d46fbf4422a80d5f", descriptor.revision().orElseThrow());
    assertEquals(
        "731ece8d06dc7eda6f6572997feb9ee1258db0784827e642909d9b565641937b",
        descriptor.sha256().orElseThrow());
    assertEquals(4_683_073_504L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"));
  }

  @Test
  void loadsQwen25MathOnePointFiveBillionMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://bartowski/Qwen2.5-Math-1.5B-Instruct-GGUF")
                    .versionRange("[2.5.0,3.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("math")
                    .build())
            .orElseThrow();

    assertEquals("qwen2_5_math_1_5b_instruct_q4_k_m", descriptor.alias());
    assertEquals("qwen2", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("951ed2aea09c43e331c612e74d83e4a23ca98e3b", descriptor.revision().orElseThrow());
    assertEquals(
        "9614a50f03c897028920ca0dc4365da570bf587f9ee7768261216fe370b37e8e",
        descriptor.sha256().orElseThrow());
    assertEquals(986_048_832L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("Qwen2.5-Math-1.5B-Instruct-Q4_K_M.gguf"));
  }

  @Test
  void loadsSqlCoderSevenBillionV2MarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://defog/sqlcoder-7b-2")
                    .versionRange("[2.0.0,3.0.0)")
                    .variant("q5_k_m")
                    .backend("pure-java")
                    .capability("text-to-sql")
                    .build())
            .orElseThrow();

    assertEquals("sqlcoder_7b_2_q5_k_m", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q5_K_M", descriptor.quantization());
    assertEquals("CC-BY-SA-4.0", descriptor.license().orElseThrow());
    assertEquals("7e5b6f7981c0aa7d143f6bec6fa26625bdfcbe66", descriptor.revision().orElseThrow());
    assertEquals(
        "0068f25d1fc37cb25aa6be85064432eeeb1a0754d97139c0d2eb3529fc8fc32b",
        descriptor.sha256().orElseThrow());
    assertEquals(4_783_256_288L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor.localPath().orElseThrow().toString().endsWith("sqlcoder-7b-q5_k_m.gguf"));
  }

  @Test
  void loadsMiniCpm5OfficialMarkerFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://openbmb/MiniCPM5-1B-GGUF")
                    .versionRange("[5.0.0,6.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("text-generation")
                    .build())
            .orElseThrow();

    assertEquals("minicpm5_1b_q4_k_m", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("87007042419d30c1d8f38ef065424ee33870831e", descriptor.revision().orElseThrow());
    assertEquals(
        "81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa",
        descriptor.sha256().orElseThrow());
    assertEquals(688_065_920L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("MiniCPM5-1B-Q4_K_M.gguf"));
  }

  @Test
  void loadsHuatuoGptO1SevenBillionMarkerWithVerifiedPureJavaClaim() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("hf://bartowski/HuatuoGPT-o1-7B-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("medical-reasoning")
                    .build())
            .orElseThrow();

    assertEquals("huatuogpt_o1_7b_q4_k_m", descriptor.alias());
    assertEquals("qwen2", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("5b481e71fa41e2ccffdd863dc01f27be48075bd1", descriptor.revision().orElseThrow());
    assertEquals(
        "4643521a184cb26df0f7c57da9aead0c632b286a9aff103c9f9dca4dc059abd7",
        descriptor.sha256().orElseThrow());
    assertEquals(4_683_074_720L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor.features().containsAll(Set.of("community-conversion", "medical-use-warning")));
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("HuatuoGPT-o1-7B-Q4_K_M.gguf"));
    assertTrue(descriptor.supportsBackend("pure-java"));
    assertTrue(descriptor.supportsBackend("llama.cpp"));
  }

  @Test
  void loadsFinR1MarkerWithoutPrematurePureJavaClaim() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://bartowski/SUFE-AIFLM-Lab_Fin-R1-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("llama.cpp")
                    .capability("financial-reasoning")
                    .build())
            .orElseThrow();

    assertEquals("fin_r1_7b_q4_k_m", descriptor.alias());
    assertEquals("qwen2", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("61b412864cc5df96e415808920451a9856d7b078", descriptor.revision().orElseThrow());
    assertEquals(
        "d50f16c5149b4dc103c68e249a136ab7c82f7569a7df707a2d6150bff5994c33",
        descriptor.sha256().orElseThrow());
    assertEquals(4_683_073_600L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .features()
            .containsAll(
                Set.of(
                    "community-conversion",
                    "financial-advice-warning",
                    "license-card-only")));
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("SUFE-AIFLM-Lab_Fin-R1-Q4_K_M.gguf"));
    assertTrue(
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://bartowski/SUFE-AIFLM-Lab_Fin-R1-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .build())
            .isEmpty());
  }

  @Test
  void loadsEuroLlmMarkerWithoutPrematurePureJavaClaim() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://mradermacher/EuroLLM-1.7B-Instruct-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("llama.cpp")
                    .capability("translation")
                    .build())
            .orElseThrow();

    assertEquals("eurollm_1_7b_instruct_q4_k_m", descriptor.alias());
    assertEquals("llama", descriptor.architecture());
    assertEquals("Q4_K_M", descriptor.quantization());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertEquals("2951f08f66429c934c8b01a94347161362430808", descriptor.revision().orElseThrow());
    assertEquals(
        "1cade17f491ea46a686dbee51fbd52442e0f001f102380c3b9d66b4a77f84093",
        descriptor.sha256().orElseThrow());
    assertEquals(1_045_157_088L, descriptor.sizeBytes().orElseThrow());
    assertTrue(
        descriptor
            .features()
            .containsAll(
                Set.of("community-conversion", "multilingual-35", "unaligned-output-warning")));
    assertTrue(
        descriptor
            .localPath()
            .orElseThrow()
            .toString()
            .endsWith("EuroLLM-1.7B-Instruct.Q4_K_M.gguf"));
    assertTrue(
        registry
            .resolve(
                ModelJarRequirement.forSource(
                        "hf://mradermacher/EuroLLM-1.7B-Instruct-GGUF")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .build())
            .isEmpty());
  }

  @Test
  void loadsAndVerifiesCanonicalWordTourPayloadFromClasspath() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    ModelJarDescriptor descriptor =
        registry
            .resolve(
                ModelJarRequirement.forSource("github://joisino/wordtour")
                    .versionRange("[1.0.0,2.0.0)")
                    .variant("optimal")
                    .backend("semantic-order")
                    .capability("semantic-neighbors")
                    .build())
            .orElseThrow();

    assertEquals("wordtour_glove_6b_300d_optimal", descriptor.alias());
    assertEquals("wordtour-v1", descriptor.format());
    assertEquals("wordtour", descriptor.architecture());
    assertEquals("NONE", descriptor.quantization());
    assertEquals("PDDL-1.0", descriptor.license().orElseThrow());
    assertTrue(descriptor.localPath().isEmpty());
    assertEquals(
        "META-INF/modeljars/models/wordtour_glove_6b_300d_optimal/wordtour_opt.txt",
        descriptor.classpathResource().orElseThrow());

    byte[] payload =
        new ModelJarResourceLoader(getClass().getClassLoader()).readVerified(descriptor);
    List<String> terms = new String(payload, StandardCharsets.UTF_8).lines().toList();
    assertEquals(40_000, terms.size());
    assertEquals(40_000, Set.copyOf(terms).size());
    assertEquals("the", terms.getFirst());
    assertEquals("of", terms.getLast());
  }
}
