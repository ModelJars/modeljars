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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Lfm25EmbeddingIntegrationTest {
  private static final ModelJar MODEL =
      ModelJar.of(
          "org.modeljars.huggingface:liquidai.lfm2.5-embedding-350m-gguf.q4_0:2.5.0-q4_0.1");

  @Test
  void publicLoaderHonorsClsPoolingAndEmbedsTheWholeSequence() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("modeljars.integration.lfm25.live"),
        "Run :modeljars:lfm25EmbeddingIntegrationTest to download and verify the pinned weights");
    try (var runtime = ModelJars.openEmbeddingRuntime(MODEL)) {
      assertEquals("cls", runtime.qualification().pooling());
      assertEquals(1024, runtime.model().dimension());
      String first = "The museum opens at nine in the morning.";
      float[] museum = runtime.model().embed(first);
      float[] library = runtime.model().embed("The library lends books for twenty-one days.");
      assertUnitVector(museum);
      assertUnitVector(library);
      double distance = 0;
      for (int index = 0; index < museum.length; index++) {
        distance += Math.pow(museum[index] - library[index], 2);
      }
      // Both texts start with the same token. Reading only its causal hidden state would
      // produce identical vectors and miss the sequence encoder's bidirectional pooling.
      assertTrue(distance > 1e-4, "Different sequences must not collapse to the first token");
      assertArrayEquals(
          museum,
          runtime.model().embed(first),
          1e-6f,
          "Embedding must reset state between independent texts");
    }
  }

  private static void assertUnitVector(float[] vector) {
    assertEquals(1024, vector.length);
    double normSquared = 0;
    for (float value : vector) {
      assertTrue(Float.isFinite(value));
      normSquared += (double) value * value;
    }
    assertEquals(1.0, normSquared, 1e-5);
  }
}
