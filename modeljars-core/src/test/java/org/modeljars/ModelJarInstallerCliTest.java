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

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelJarInstallerCliTest {
  @Test
  void rejectsMissingSource() {
    assertThrows(IllegalArgumentException.class, () -> ModelJarInstallerCli.main(new String[0]));
  }

  @Test
  void rejectsUnknownOptionsBeforeResolving() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelJarInstallerCli.main(new String[] {"hf://example/model", "--unknown", "x"}));
  }

  @Test
  void rejectsOptionsWithoutValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelJarInstallerCli.main(new String[] {"hf://example/model", "--variant"}));
  }
}
