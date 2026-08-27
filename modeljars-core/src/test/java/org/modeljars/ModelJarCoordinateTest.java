/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    ModelJarCoordinate coordinate = ModelJarCoordinate.parse("org.example:qwen3:3.0.0:q4_0@gguf");

    assertEquals("q4_0", coordinate.classifier().orElseThrow());
    assertEquals("gguf", coordinate.type());
    assertEquals("org.example:qwen3:3.0.0:q4_0@gguf", coordinate.toString());
  }

  @Test
  void rejectsIncompleteCoordinates() {
    assertThrows(IllegalArgumentException.class, () -> ModelJarCoordinate.parse("qwen3"));
  }
}
