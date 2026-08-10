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
