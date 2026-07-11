package org.modeljars;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Describes one model variant advertised by a ModelJars marker. */
public record ModelJarDescriptor(
    String alias,
    String sourceId,
    ModelJarCoordinate markerCoordinate,
    ModelVersion modelVersion,
    String variant,
    String format,
    String architecture,
    String quantization,
    Optional<Path> localPath,
    Optional<URI> sourceUri,
    Optional<URI> downloadUri,
    Optional<String> revision,
    Optional<String> sha256,
    Optional<Long> sizeBytes,
    Optional<String> license,
    Set<String> capabilities,
    Map<String, Boolean> backendSupport) {
  public ModelJarDescriptor {
    alias = requireText(alias, "alias");
    sourceId = requireText(sourceId, "sourceId");
    markerCoordinate = Objects.requireNonNull(markerCoordinate, "markerCoordinate");
    modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
    variant = requireText(variant, "variant").toLowerCase(Locale.ROOT);
    format = requireText(format, "format").toLowerCase(Locale.ROOT);
    architecture = requireText(architecture, "architecture").toLowerCase(Locale.ROOT);
    quantization = requireText(quantization, "quantization").toUpperCase(Locale.ROOT);
    localPath = Objects.requireNonNull(localPath, "localPath");
    sourceUri = Objects.requireNonNull(sourceUri, "sourceUri");
    downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
    revision = normalizedOptional(revision, "revision");
    sha256 =
        normalizedOptional(sha256, "sha256")
            .map(String::toLowerCase)
            .map(ModelJarDescriptor::requireSha256);
    sizeBytes =
        Objects.requireNonNull(sizeBytes, "sizeBytes")
            .map(
                value -> {
                  if (value <= 0) {
                    throw new IllegalArgumentException("sizeBytes must be > 0");
                  }
                  return value;
                });
    license = normalizedOptional(license, "license");
    capabilities =
        Set.copyOf(Objects.requireNonNull(capabilities, "capabilities")).stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    backendSupport = Map.copyOf(Objects.requireNonNull(backendSupport, "backendSupport"));
  }

  public boolean matchesSource(String requestedSource) {
    return sourceId.equals(requestedSource) || markerCoordinate.toString().equals(requestedSource);
  }

  public boolean supportsBackend(String backend) {
    return backendSupport.getOrDefault(backend.toLowerCase(Locale.ROOT), false);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static Optional<String> normalizedOptional(Optional<String> value, String name) {
    return Objects.requireNonNull(value, name).map(String::trim).filter(text -> !text.isEmpty());
  }

  private static String requireSha256(String value) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
    }
    return value;
  }
}
