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
        model.qwen.licenseUri=https://www.apache.org/licenses/LICENSE-2.0.txt
        model.qwen.name=Qwen3 0.6B GGUF Q4_0
        model.qwen.description=Compact Qwen3 model for local inference.
        model.qwen.domains=general,coding
        model.qwen.dimension.parameterCount=596049920
        model.qwen.dimension.contextLength=40960
        model.qwen.dimension.embeddingLength=1024
        model.qwen.dimension.blockCount=28
        model.qwen.dimension.attentionBlockCount=28
        model.qwen.dimension.attentionHeadCount=16
        model.qwen.dimension.keyValueHeadCount=8
        model.qwen.dimension.feedForwardLength=3072
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
    assertEquals("Qwen3 0.6B GGUF Q4_0", descriptor.name().orElseThrow());
    assertEquals(
        URI.create("https://www.apache.org/licenses/LICENSE-2.0.txt"),
        descriptor.licenseUri().orElseThrow());
    assertEquals(596049920L, descriptor.dimensions().parameterCount().orElseThrow());
    assertEquals(40960, descriptor.dimensions().contextLength().orElseThrow());
    assertEquals(1024, descriptor.dimensions().embeddingLength().orElseThrow());
    assertTrue(descriptor.domains().contains("coding"));
    assertTrue(
        descriptor.estimateMemory(4096, KvCachePrecision.FLOAT16).orElseThrow().minimumBytes()
            > descriptor.sizeBytes().orElseThrow());
    assertTrue(descriptor.features().isEmpty());
  }
}
