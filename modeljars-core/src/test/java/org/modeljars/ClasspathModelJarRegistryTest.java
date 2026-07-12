package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClasspathModelJarRegistryTest {
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
}
