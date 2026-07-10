package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertiesModelJarRegistryTest {
  @TempDir Path tempDir;

  @Test
  void loadsDescriptorFromPropertiesFile() throws IOException {
    Path registry = tempDir.resolve("registry.properties");
    Files.writeString(
        registry,
        """
        model.qwen.sourceId=hf://ggml-org/Qwen3-0.6B-GGUF
        model.qwen.markerCoordinate=org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1
        model.qwen.modelVersion=3.0.0
        model.qwen.variant=q4_0
        model.qwen.format=gguf
        model.qwen.architecture=qwen3
        model.qwen.quantization=Q4_0
        model.qwen.path=${user.home}/.jvllm/models/Qwen3-0.6B-Q4_0.gguf
        model.qwen.sourceUri=https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF
        model.qwen.capabilities=text-generation,chat
        model.qwen.backend.pure-java=true
        """);

    ModelJarRegistry modelRegistry = ModelJarRegistry.fromProperties(registry);

    ModelJarDescriptor descriptor =
        modelRegistry
            .resolve(
                ModelJarRequirement.forSource("hf://ggml-org/Qwen3-0.6B-GGUF")
                    .versionRange("[3.0.0,4.0.0)")
                    .variant("Q4_0")
                    .backend("pure-java")
                    .capability("chat")
                    .build())
            .orElseThrow();

    assertEquals("qwen", descriptor.alias());
    assertEquals("qwen3", descriptor.architecture());
    assertTrue(descriptor.localPath().orElseThrow().isAbsolute());
  }
}

