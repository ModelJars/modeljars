package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelJarCoordinateTest {
  @Test
  void parsesMarkerJarCoordinates() {
    ModelJarCoordinate coordinate =
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1");

    assertEquals("org.modeljars.huggingface", coordinate.groupId());
    assertEquals("ggml-org.qwen3-0.6b-gguf.q4_0", coordinate.artifactId());
    assertEquals("3.0.0-q4_0.1", coordinate.version());
    assertEquals("jar", coordinate.type());
    assertEquals(
        "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1",
        coordinate.toString());
  }

  @Test
  void parsesClassifierAndType() {
    ModelJarCoordinate coordinate =
        ModelJarCoordinate.parse("org.example:qwen3:3.0.0:q4_0@gguf");

    assertEquals("q4_0", coordinate.classifier().orElseThrow());
    assertEquals("gguf", coordinate.type());
    assertEquals("org.example:qwen3:3.0.0:q4_0@gguf", coordinate.toString());
  }

  @Test
  void rejectsIncompleteCoordinates() {
    assertThrows(IllegalArgumentException.class, () -> ModelJarCoordinate.parse("qwen3"));
  }
}

