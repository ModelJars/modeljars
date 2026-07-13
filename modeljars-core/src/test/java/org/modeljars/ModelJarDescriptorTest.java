package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelJarDescriptorTest {
  @Test
  void legacyConstructorDefaultsFeaturesToEmptySet() {
    ModelJarDescriptor descriptor =
        new ModelJarDescriptor(
            "example",
            "hf://example/model",
            ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q4_0:1.0.0-q4_0.1"),
            ModelVersion.parse("1.0.0"),
            "q4_0",
            "gguf",
            "llama",
            "Q4_0",
            Optional.<Path>empty(),
            Optional.<URI>empty(),
            Optional.<URI>empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Set.of("text-generation"),
            Map.of("llama.cpp", true));

    assertTrue(descriptor.features().isEmpty());
    assertTrue(descriptor.classpathResource().isEmpty());
  }
}
