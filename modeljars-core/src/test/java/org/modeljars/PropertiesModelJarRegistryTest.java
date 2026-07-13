package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
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
        model.qwen.downloadUri=https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/a41486f827d17edd055fe6b3b0ba3f8d427c0519/Qwen3-0.6B-Q4_0.gguf
        model.qwen.revision=a41486f827d17edd055fe6b3b0ba3f8d427c0519
        model.qwen.sha256=da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4
        model.qwen.sizeBytes=428970080
        model.qwen.license=Apache-2.0
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
    assertEquals(
        URI.create(
            "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/"
                + "a41486f827d17edd055fe6b3b0ba3f8d427c0519/Qwen3-0.6B-Q4_0.gguf"),
        descriptor.downloadUri().orElseThrow());
    assertEquals(
        "a41486f827d17edd055fe6b3b0ba3f8d427c0519", descriptor.revision().orElseThrow());
    assertEquals(
        "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4",
        descriptor.sha256().orElseThrow());
    assertEquals(428970080L, descriptor.sizeBytes().orElseThrow());
    assertEquals("Apache-2.0", descriptor.license().orElseThrow());
    assertTrue(descriptor.features().isEmpty());
  }
}
