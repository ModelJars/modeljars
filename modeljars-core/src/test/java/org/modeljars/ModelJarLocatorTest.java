package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelJarLocatorTest {
  @Test
  void resolvesLocalModelPathWithoutHardCodingItInTheCaller() {
    ModelJarLocator locator = new ModelJarLocator(ModelJarRegistry.fromClasspath());

    var path =
        locator
            .localPath(
                ModelJarRequirement.forSource("hf://ggml-org/Qwen3-0.6B-GGUF")
                    .variant("q4_0")
                    .backend("pure-java")
                    .build())
            .orElseThrow();

    assertTrue(path.toString().endsWith(".jvllm/models/Qwen3-0.6B-Q4_0.gguf"));
  }
}

