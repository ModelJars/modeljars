package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelJarCacheTest {
  @TempDir Path temporaryDirectory;

  @Test
  void createsTheSharedContentAddressedArtifactPath() {
    String sha256 = "a".repeat(64);

    assertEquals(
        temporaryDirectory
            .resolve("sha256")
            .resolve("aa")
            .resolve(sha256)
            .resolve("model.gguf")
            .toAbsolutePath()
            .normalize(),
        ModelJarCache.artifactPath(descriptor(sha256), temporaryDirectory));
  }

  private static ModelJarDescriptor descriptor(String sha256) {
    return new ModelJarDescriptor(
        "example_q4_0",
        "hf://example/model",
        ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q4_0:1.0.0-q4_0.1"),
        ModelVersion.parse("1.0.0"),
        "q4_0",
        "gguf",
        "llama",
        "Q4_0",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/model")),
        Optional.of(URI.create("https://huggingface.co/example/model/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of(sha256),
        Optional.of(4L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation"),
        Set.of(),
        Map.of("java", true),
        Optional.of("Example"),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        ModelDimensions.unknown());
  }
}
