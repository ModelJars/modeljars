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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarCoordinate;

class DependencyCoordinatesTest {
  private static final ModelJarCoordinate COORDINATE =
      ModelJarCoordinate.parse("org.modeljars.huggingface:example.q4:1.2.3");

  @Test
  void rendersMavenAndGradleDeclarations() {
    String maven = DependencyCoordinates.render(COORDINATE, DependencyCoordinates.Tool.MAVEN);

    assertTrue(maven.contains("<groupId>org.modeljars.huggingface</groupId>"));
    assertTrue(maven.contains("<artifactId>example.q4</artifactId>"));
    assertEquals(
        "implementation(\"org.modeljars.huggingface:example.q4:1.2.3\")",
        DependencyCoordinates.render(COORDINATE, DependencyCoordinates.Tool.GRADLE_KOTLIN));
  }

  @Test
  void rendersSbtLeiningenAndJbangDeclarations() {
    assertEquals(
        "libraryDependencies += \"org.modeljars.huggingface\" % \"example.q4\" % \"1.2.3\"",
        DependencyCoordinates.render(COORDINATE, DependencyCoordinates.Tool.SBT));
    assertEquals(
        "[org.modeljars.huggingface/example.q4 \"1.2.3\"]",
        DependencyCoordinates.render(COORDINATE, DependencyCoordinates.Tool.LEININGEN));
    assertEquals(
        "//DEPS org.modeljars.huggingface:example.q4:1.2.3",
        DependencyCoordinates.render(COORDINATE, DependencyCoordinates.Tool.JBANG));
  }
}
