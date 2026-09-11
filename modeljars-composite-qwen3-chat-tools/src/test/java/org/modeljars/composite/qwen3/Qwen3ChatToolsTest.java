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
package org.modeljars.composite.qwen3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Qwen3ChatToolsTest {
  @Test
  void bindsTheExactQualifiedArtifactsAndEvidence() {
    assertEquals(
        "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1",
        Qwen3ChatTools.CHAT.source());
    assertEquals(
        "org.modeljars.huggingface:qwen.qwen3-1.7b-gguf.q8_0:3.0.0-q8_0.2",
        Qwen3ChatTools.TOOLS.source());
    assertEquals(53_166L, Qwen3ChatTools.QUALIFICATION.controlMedianMillis());
    assertEquals(35_151L, Qwen3ChatTools.QUALIFICATION.hybridMedianMillis());
    assertTrue(Qwen3ChatTools.QUALIFICATION.improvement() > 0.33);
  }
}
