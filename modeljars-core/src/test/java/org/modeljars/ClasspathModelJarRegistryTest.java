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
}
