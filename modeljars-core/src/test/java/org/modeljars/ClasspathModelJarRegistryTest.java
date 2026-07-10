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
}

