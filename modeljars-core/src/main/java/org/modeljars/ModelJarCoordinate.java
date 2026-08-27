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

import java.util.Objects;
import java.util.Optional;

/**
 * Maven-like coordinate for a marker JAR or model artifact.
 *
 * @param groupId Maven group ID
 * @param artifactId Maven artifact ID
 * @param version artifact version
 * @param classifier optional artifact classifier
 * @param type artifact extension, normally {@code jar}
 */
public record ModelJarCoordinate(
    String groupId, String artifactId, String version, Optional<String> classifier, String type) {
  /** Validates the coordinate and supplies the default {@code jar} type. */
  public ModelJarCoordinate {
    groupId = requireText(groupId, "groupId");
    artifactId = requireText(artifactId, "artifactId");
    version = requireText(version, "version");
    classifier = Objects.requireNonNull(classifier, "classifier").filter(s -> !s.isBlank());
    type = type == null || type.isBlank() ? "jar" : type.trim();
  }

  /**
   * Parses {@code groupId:artifactId:version[:classifier][@type]} notation.
   *
   * @param value coordinate notation
   * @return parsed coordinate
   */
  public static ModelJarCoordinate parse(String value) {
    String coordinate = requireText(value, "coordinate");
    String type = "jar";
    int typeSeparator = coordinate.indexOf('@');
    if (typeSeparator >= 0) {
      type = coordinate.substring(typeSeparator + 1);
      coordinate = coordinate.substring(0, typeSeparator);
    }

    String[] parts = coordinate.split(":", -1);
    if (parts.length < 3 || parts.length > 4) {
      throw new IllegalArgumentException("Expected groupId:artifactId:version[:classifier][@type]");
    }

    Optional<String> classifier = parts.length == 4 ? Optional.of(parts[3]) : Optional.empty();
    return new ModelJarCoordinate(parts[0], parts[1], parts[2], classifier, type);
  }

  @Override
  public String toString() {
    StringBuilder builder =
        new StringBuilder(groupId).append(':').append(artifactId).append(':').append(version);
    classifier.ifPresent(value -> builder.append(':').append(value));
    if (!"jar".equals(type)) {
      builder.append('@').append(type);
    }
    return builder.toString();
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
