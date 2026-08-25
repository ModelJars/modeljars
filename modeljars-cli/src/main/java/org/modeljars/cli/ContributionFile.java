package org.modeljars.cli;

import java.util.Objects;

record ContributionFile(String path, String role, String sha256, long sizeBytes) {
  ContributionFile {
    path = Objects.requireNonNull(path, "path");
    role = Objects.requireNonNull(role, "role");
    sha256 = Objects.requireNonNull(sha256, "sha256");
    if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
      throw new IllegalArgumentException("Contribution file path must be normalized and relative: " + path);
    }
    if (!sha256.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Contribution file SHA-256 is invalid: " + path);
    }
    if (sizeBytes <= 0) throw new IllegalArgumentException("Contribution file size must be positive: " + path);
  }
}
