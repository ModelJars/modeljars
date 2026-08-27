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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionRangeTest {
  @Test
  void supportsInclusiveExclusiveRanges() {
    VersionRange range = VersionRange.parse("[3.0.0,4.0.0)");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertTrue(range.contains(ModelVersion.parse("3.5.0")));
    assertFalse(range.contains(ModelVersion.parse("4.0.0")));
  }

  @Test
  void supportsOpenEndedRanges() {
    VersionRange range = VersionRange.parse("[3.0.0,)");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertTrue(range.contains(ModelVersion.parse("9.0.0")));
    assertFalse(range.contains(ModelVersion.parse("2.9.9")));
  }

  @Test
  void exactVersionMatchesOnlyThatVersion() {
    VersionRange range = VersionRange.parse("3.0.0");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertFalse(range.contains(ModelVersion.parse("3.0.1")));
  }
}
