package org.modeljars;

import java.util.Optional;

/** Maven-like inclusive/exclusive range for model lineage versions. */
public record VersionRange(
    Optional<ModelVersion> lower,
    boolean includeLower,
    Optional<ModelVersion> upper,
    boolean includeUpper) {
  public static VersionRange exact(ModelVersion version) {
    return new VersionRange(Optional.of(version), true, Optional.of(version), true);
  }

  public static VersionRange parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("version range must not be blank");
    }
    String trimmed = value.trim();
    if (!trimmed.startsWith("[") && !trimmed.startsWith("(")) {
      return exact(ModelVersion.parse(trimmed));
    }
    if ((!trimmed.endsWith("]") && !trimmed.endsWith(")")) || !trimmed.contains(",")) {
      throw new IllegalArgumentException("Invalid version range: " + value);
    }

    boolean includeLower = trimmed.startsWith("[");
    boolean includeUpper = trimmed.endsWith("]");
    String body = trimmed.substring(1, trimmed.length() - 1);
    String[] parts = body.split(",", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("Invalid version range: " + value);
    }
    Optional<ModelVersion> lower = parseBound(parts[0]);
    Optional<ModelVersion> upper = parseBound(parts[1]);
    return new VersionRange(lower, includeLower, upper, includeUpper);
  }

  public boolean contains(ModelVersion version) {
    if (lower.isPresent()) {
      int compared = version.compareTo(lower.orElseThrow());
      if (compared < 0 || (compared == 0 && !includeLower)) {
        return false;
      }
    }
    if (upper.isPresent()) {
      int compared = version.compareTo(upper.orElseThrow());
      if (compared > 0 || (compared == 0 && !includeUpper)) {
        return false;
      }
    }
    return true;
  }

  private static Optional<ModelVersion> parseBound(String value) {
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(ModelVersion.parse(trimmed));
  }
}

