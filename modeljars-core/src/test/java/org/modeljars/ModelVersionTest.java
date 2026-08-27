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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelVersionTest {
  @Test
  void comparesSemanticVersions() {
    assertTrue(ModelVersion.parse("3.1.0").compareTo(ModelVersion.parse("3.0.9")) > 0);
    assertTrue(ModelVersion.parse("3.0.0").compareTo(ModelVersion.parse("3.0.0-rc.1")) > 0);
    assertTrue(ModelVersion.parse("3.0.0-rc.2").compareTo(ModelVersion.parse("3.0.0-rc.1")) > 0);
  }

  @Test
  void ignoresBuildMetadataForOrderingButPreservesValue() {
    ModelVersion version = ModelVersion.parse("3.0.0+q4_0");

    assertEquals(0, version.compareTo(ModelVersion.parse("3.0.0+q8_0")));
    assertEquals("3.0.0+q4_0", version.toString());
  }

  @Test
  void rejectsNonSemanticVersion() {
    assertThrows(IllegalArgumentException.class, () -> ModelVersion.parse("Qwen3-0.6B"));
  }
}
