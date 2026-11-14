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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModelLoadOptionsTest {

  @Test
  void defaultsLeaveTheContextUnbounded() {
    ModelLoadOptions options = ModelLoadOptions.defaults();
    assertEquals(ModelLoadOptions.UNBOUNDED_CONTEXT, options.maxContextLength());
    assertFalse(options.boundsContext());
  }

  @Test
  void builderBoundsTheContext() {
    ModelLoadOptions options = ModelLoadOptions.builder().maxContextLength(4096).build();
    assertEquals(4096, options.maxContextLength());
    assertTrue(options.boundsContext());
  }

  @Test
  void nonPositiveBoundsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> ModelLoadOptions.builder().maxContextLength(0));
    assertThrows(
        IllegalArgumentException.class, () -> ModelLoadOptions.builder().maxContextLength(-1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelLoadOptions(ModelBackend.AUTO, false, Path.of("cache"), -5));
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void threeArgumentConstructorKeepsTheOldMeaning() {
    ModelLoadOptions options = new ModelLoadOptions(ModelBackend.JAVA, true, Path.of("cache"));
    assertEquals(ModelBackend.JAVA, options.backend());
    assertTrue(options.offline());
    assertEquals(ModelLoadOptions.UNBOUNDED_CONTEXT, options.maxContextLength());
    assertFalse(options.boundsContext());
  }
}
