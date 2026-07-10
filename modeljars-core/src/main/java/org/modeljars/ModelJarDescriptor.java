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
    Optional<String> sha256,
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
    sha256 = Objects.requireNonNull(sha256, "sha256").filter(s -> !s.isBlank());
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
}

