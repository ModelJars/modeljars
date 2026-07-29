package org.modeljars;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Describes one model variant advertised by a ModelJars marker.
 *
 * @param alias stable catalog alias
 * @param sourceId upstream model source ID
 * @param markerCoordinate marker JAR coordinate
 * @param modelVersion normalized upstream model version
 * @param variant normalized model variant
 * @param format model artifact format
 * @param architecture model architecture
 * @param quantization model weight quantization
 * @param localPath configured local artifact path
 * @param classpathResource model metadata resource packaged in the marker
 * @param sourceUri upstream model page
 * @param downloadUri immutable model artifact download location
 * @param revision immutable upstream source revision
 * @param sha256 model artifact SHA-256 digest
 * @param sizeBytes model artifact size in bytes
 * @param license model license identifier
 * @param capabilities normalized model capabilities
 * @param features normalized architecture and packaging features
 * @param backendSupport supported inference backends by normalized identifier
 * @param name human-readable model name
 * @param description short catalog description
 * @param licenseUri canonical model license location
 * @param domains normalized task or industry domains
 * @param dimensions model dimensions used for discovery and resource estimates
 */
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
    Optional<String> classpathResource,
    Optional<URI> sourceUri,
    Optional<URI> downloadUri,
    Optional<String> revision,
    Optional<String> sha256,
    Optional<Long> sizeBytes,
    Optional<String> license,
    Set<String> capabilities,
    Set<String> features,
    Map<String, Boolean> backendSupport,
    Optional<String> name,
    Optional<String> description,
    Optional<URI> licenseUri,
    Set<String> domains,
    ModelDimensions dimensions) {
  /** Validates and normalizes marker metadata. */
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
    classpathResource =
        normalizedOptional(classpathResource, "classpathResource")
            .map(ModelJarDescriptor::requireClasspathResource);
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
    capabilities = normalizedSet(capabilities, "capabilities");
    features = normalizedSet(features, "features");
    backendSupport = Map.copyOf(Objects.requireNonNull(backendSupport, "backendSupport"));
    name = normalizedOptional(name, "name");
    description = normalizedOptional(description, "description");
    licenseUri = Objects.requireNonNull(licenseUri, "licenseUri");
    domains = normalizedSet(domains, "domains");
    dimensions = Objects.requireNonNull(dimensions, "dimensions");
  }

  /**
   * Tests an upstream source ID or complete marker coordinate.
   *
   * @param requestedSource requested source identifier
   * @return whether this descriptor has the requested source identity
   */
  public boolean matchesSource(String requestedSource) {
    return sourceId.equals(requestedSource) || markerCoordinate.toString().equals(requestedSource);
  }

  /**
   * Tests whether the marker advertises support for an inference backend.
   *
   * @param backend inference backend identifier
   * @return whether the backend is supported
   */
  public boolean supportsBackend(String backend) {
    return backendSupport.getOrDefault(backend.toLowerCase(Locale.ROOT), false);
  }

  /**
   * Estimates model weights and KV-cache storage for a context length.
   *
   * @param contextTokens context-window size to estimate
   * @param precision precision of each KV-cache element
   * @return the lower-bound estimate, or empty when size or dimensions are unavailable
   */
  public Optional<ModelMemoryEstimate> estimateMemory(
      int contextTokens, KvCachePrecision precision) {
    return sizeBytes.flatMap(bytes -> dimensions.estimateMemory(contextTokens, precision, bytes));
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

  private static Set<String> normalizedSet(Set<String> values, String name) {
    return Set.copyOf(Objects.requireNonNull(values, name)).stream()
        .map(value -> requireText(value, name + " value").toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static String requireSha256(String value) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
    }
    return value;
  }

  private static String requireClasspathResource(String value) {
    if (value.startsWith("/")
        || value.contains("\\")
        || java.util.Arrays.stream(value.split("/", -1))
            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
      throw new IllegalArgumentException(
          "classpathResource must be a normalized relative resource name");
    }
    return value;
  }
}
