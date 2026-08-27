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

import java.util.Locale;
import org.modeljars.ModelJarCoordinate;

/** Dependency declarations for the build tools commonly used by JVM applications. */
final class DependencyCoordinates {
  enum Tool {
    MAVEN,
    GRADLE,
    GRADLE_KOTLIN,
    SBT,
    IVY,
    LEININGEN,
    JBANG;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  private DependencyCoordinates() {}

  static String render(ModelJarCoordinate coordinate, Tool tool) {
    return switch (tool) {
      case MAVEN -> maven(coordinate);
      case GRADLE -> "implementation '" + notation(coordinate) + "'";
      case GRADLE_KOTLIN -> "implementation(\"" + notation(coordinate) + "\")";
      case SBT ->
          "libraryDependencies += \""
              + coordinate.groupId()
              + "\" % \""
              + coordinate.artifactId()
              + "\" % \""
              + coordinate.version()
              + "\"";
      case IVY -> ivy(coordinate);
      case LEININGEN ->
          "["
              + coordinate.groupId()
              + '/'
              + coordinate.artifactId()
              + " \""
              + coordinate.version()
              + "\"]";
      case JBANG -> "//DEPS " + notation(coordinate);
    };
  }

  private static String maven(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append("<dependency>\n")
            .append("  <groupId>")
            .append(coordinate.groupId())
            .append("</groupId>\n")
            .append("  <artifactId>")
            .append(coordinate.artifactId())
            .append("</artifactId>\n")
            .append("  <version>")
            .append(coordinate.version())
            .append("</version>\n");
    coordinate
        .classifier()
        .ifPresent(
            value -> result.append("  <classifier>").append(value).append("</classifier>\n"));
    if (!coordinate.type().equals("jar")) {
      result.append("  <type>").append(coordinate.type()).append("</type>\n");
    }
    return result.append("</dependency>").toString();
  }

  private static String ivy(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append("<dependency org=\"")
            .append(coordinate.groupId())
            .append("\" name=\"")
            .append(coordinate.artifactId())
            .append("\" rev=\"")
            .append(coordinate.version())
            .append('"');
    coordinate.classifier().ifPresent(value -> result.append(" conf=\"").append(value).append('"'));
    return result.append(" />").toString();
  }

  private static String notation(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append(coordinate.groupId())
            .append(':')
            .append(coordinate.artifactId())
            .append(':')
            .append(coordinate.version());
    coordinate.classifier().ifPresent(value -> result.append(':').append(value));
    if (!coordinate.type().equals("jar")) {
      result.append('@').append(coordinate.type());
    }
    return result.toString();
  }
}
