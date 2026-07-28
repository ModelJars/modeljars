package org.modeljars;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Immutable selector used to resolve one compatible model variant from a registry. */
public record ModelJar(
    String source,
    Optional<VersionRange> versionRange,
    Optional<String> variant,
    Optional<String> backend,
    Optional<String> capability) {

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

  /** Creates a selector for an upstream model source. */
  public static ModelJar of(String source) {
    return new ModelJar(source, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** Returns a selector constrained by a Maven-style model version range. */
  public ModelJar version(String value) {
    return new ModelJar(
        source, Optional.of(VersionRange.parse(value)), variant, backend, capability);
  }

  /** Returns a selector constrained to a normalized model variant such as {@code q4_k_m}. */
  public ModelJar variant(String value) {
    return new ModelJar(source, versionRange, required(value, "variant"), backend, capability);
  }

  /** Returns a selector constrained to a normalized runtime backend. */
  public ModelJar backend(String value) {
    return new ModelJar(source, versionRange, variant, required(value, "backend"), capability);
  }

  /** Returns a selector constrained to a normalized model capability. */
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
