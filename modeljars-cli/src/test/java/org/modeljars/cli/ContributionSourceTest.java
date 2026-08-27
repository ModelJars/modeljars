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
package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContributionSourceTest {
  @Test
  void normalizesSupportedHuggingFaceRepositoryForms() {
    assertEquals(
        "Qwen/Qwen2.5-0.5B-Instruct",
        ContributionSource.parse("Qwen/Qwen2.5-0.5B-Instruct").repository());
    assertEquals(
        "Qwen/Qwen2.5-0.5B-Instruct",
        ContributionSource.parse("hf://Qwen/Qwen2.5-0.5B-Instruct").repository());
    assertEquals(
        "Qwen/Qwen2.5-0.5B-Instruct",
        ContributionSource.parse("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct").repository());
  }

  @Test
  void rejectsNonHuggingFaceAndPathLikeSources() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContributionSource.parse("https://example.com/owner/model"));
    assertThrows(IllegalArgumentException.class, () -> ContributionSource.parse("owner/../model"));
    assertThrows(IllegalArgumentException.class, () -> ContributionSource.parse("owner"));
  }
}
