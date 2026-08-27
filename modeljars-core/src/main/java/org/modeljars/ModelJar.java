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

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable selector used to resolve one compatible model variant from a registry.
 *
 * @param source upstream model source ID or marker coordinate
 * @param versionRange accepted model-version range, when constrained
 * @param variant normalized model variant, when constrained
 * @param backend required inference backend, when constrained
 * @param capability required model capability, when constrained
 */
public record ModelJar(
    String source,
    Optional<VersionRange> versionRange,
    Optional<String> variant,
    Optional<String> backend,
    Optional<String> capability) {

  /** Validates and normalizes the selection constraints. */
  public ModelJar {
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    source = source.trim();
    versionRange = Objects.requireNonNull(versionRange, "versionRange");
    variant = normalize(variant, "variant");
    backend = normalize(backend, "backend");
    capability = normalize(capability, "capability");
  }

  /**
   * Creates a selector for an upstream model source.
   *
   * @param source upstream model source ID or marker coordinate
   * @return an unconstrained selector for the source
   */
  public static ModelJar of(String source) {
    return new ModelJar(
        source, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /**
   * Returns a selector constrained by a Maven-style model version range.
   *
   * @param value exact version or Maven-style range
   * @return a copy of this selector with the version constraint
   */
  public ModelJar version(String value) {
    return new ModelJar(
        source, Optional.of(VersionRange.parse(value)), variant, backend, capability);
  }

  /**
   * Returns a selector constrained to a normalized model variant such as {@code q4_k_m}.
   *
   * @param value model variant
   * @return a copy of this selector with the variant constraint
   */
  public ModelJar variant(String value) {
    return new ModelJar(source, versionRange, required(value, "variant"), backend, capability);
  }

  /**
   * Returns a selector constrained to a normalized runtime backend.
   *
   * @param value inference backend identifier
   * @return a copy of this selector with the backend constraint
   */
  public ModelJar backend(String value) {
    return new ModelJar(source, versionRange, variant, required(value, "backend"), capability);
  }

  /**
   * Returns a selector constrained to a normalized model capability.
   *
   * @param value model capability
   * @return a copy of this selector with the capability constraint
   */
  public ModelJar capability(String value) {
    return new ModelJar(source, versionRange, variant, backend, required(value, "capability"));
  }

  private static Optional<String> normalize(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    return value.map(item -> normalized(item, name));
  }

  private static Optional<String> required(String value, String name) {
    return Optional.of(normalized(value, name));
  }

  private static String normalized(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
