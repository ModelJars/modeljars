package org.modeljars;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * One immutable file required to load a multi-file model artifact.
 *
 * @param path normalized relative path inside the installed artifact directory
 * @param role file purpose, such as {@code weights}, {@code config}, or {@code tokenizer}
 * @param downloadUri immutable source URI used to retrieve the file
 * @param sha256 lowercase SHA-256 digest of the file contents
 * @param sizeBytes expected file size in bytes
 */
public record ModelArtifactFile(
    String path, String role, URI downloadUri, String sha256, long sizeBytes) {
  /** Validates and normalizes immutable artifact-file metadata. */
  public ModelArtifactFile {
    path = requirePath(path);
    role = requireText(role, "role").toLowerCase(Locale.ROOT);
    downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
    sha256 = requireText(sha256, "sha256").toLowerCase(Locale.ROOT);
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "sha256 must contain exactly 64 hexadecimal characters");
    }
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("sizeBytes must be > 0");
    }
  }

  private static String requirePath(String value) {
    String path = requireText(value, "path");
    if (path.startsWith("/")
        || path.contains("\\")
        || java.util.Arrays.stream(path.split("/", -1))
            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
      throw new IllegalArgumentException("path must be a normalized relative resource name");
    }
    return path;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
