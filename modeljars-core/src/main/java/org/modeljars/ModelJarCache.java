package org.modeljars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared content-addressed cache layout for ModelJars runtimes and installation tools. */
public final class ModelJarCache {
  /** System property that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_PROPERTY = "modeljars.cache";

  /** Environment variable that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_ENV = "MODELJARS_CACHE";

  private ModelJarCache() {}

  /**
   * Returns the configured cache root.
   *
   * <p>The {@code modeljars.cache} system property takes precedence over the
   * {@code MODELJARS_CACHE} environment variable. When neither is set, the cache lives below
   * {@code ${user.home}/.modeljars/cache}.
   *
   * @return normalized absolute cache root
   */
  public static Path defaultDirectory() {
    String configured = System.getProperty(CACHE_DIRECTORY_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(CACHE_DIRECTORY_ENV);
    }
    if (configured != null && !configured.isBlank()) {
      return normalize(Path.of(configured.trim()));
    }
    return normalize(Path.of(System.getProperty("user.home"), ".modeljars", "cache"));
  }

  /**
   * Returns the cache path for a descriptor below the configured cache root.
   *
   * @param descriptor immutable model metadata
   * @return content-addressed artifact path
   */
  public static Path artifactPath(ModelJarDescriptor descriptor) {
    return artifactPath(descriptor, defaultDirectory());
  }

  /**
   * Returns the cache path for a descriptor below an explicit cache root.
   *
   * @param descriptor immutable model metadata
   * @param cacheDirectory cache root
   * @return content-addressed artifact path
   */
  public static Path artifactPath(ModelJarDescriptor descriptor, Path cacheDirectory) {
    Path bundle = bundlePath(descriptor, cacheDirectory);
    String artifactName =
        descriptor.primaryFile().map(ModelArtifactFile::path).orElse("model." + descriptor.format());
    Path artifact = bundle.resolve(artifactName).normalize();
    if (!artifact.startsWith(bundle)) {
      throw new ModelJarException("Model artifact path escapes its cache bundle: " + artifactName);
    }
    return artifact;
  }

  /**
   * Returns the content-addressed directory that owns every file in a model artifact.
   *
   * @param descriptor immutable model metadata
   * @param cacheDirectory cache root
   * @return normalized model bundle directory
   */
  public static Path bundlePath(ModelJarDescriptor descriptor, Path cacheDirectory) {
    Objects.requireNonNull(descriptor, "descriptor");
    String sha256 =
        descriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "ModelJar has no artifact SHA-256: " + descriptor.markerCoordinate()));
    String format = descriptor.format();
    if (!format.matches("[a-z0-9][a-z0-9_-]*")) {
      throw new ModelJarException("ModelJar format cannot be used as a cache filename: " + format);
    }
    return normalize(cacheDirectory)
        .resolve("sha256")
        .resolve(sha256.substring(0, 2))
        .resolve(sha256);
  }

  /**
   * Returns whether every file required by a descriptor is present without following symbolic
   * links.
   *
   * @param descriptor immutable model metadata
   * @param artifact primary artifact path returned by {@link #artifactPath(ModelJarDescriptor,
   *     Path)}
   * @return whether the complete model is installed
   */
  public static boolean isComplete(ModelJarDescriptor descriptor, Path artifact) {
    Objects.requireNonNull(descriptor, "descriptor");
    Path normalizedArtifact =
        Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
    if (descriptor.files().isEmpty()) {
      return Files.isRegularFile(normalizedArtifact, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    Path bundle = fileRoot(descriptor, normalizedArtifact);
    if (bundle == null
        || !Files.isDirectory(bundle, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    return descriptor.files().stream()
        .map(file -> bundle.resolve(file.path()).normalize())
        .allMatch(
            path ->
                path.startsWith(bundle)
                    && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS));
  }

  private static Path fileRoot(ModelJarDescriptor descriptor, Path artifact) {
    Path root = artifact;
    int pathParts = Path.of(descriptor.primaryFile().orElseThrow().path()).getNameCount();
    for (int part = 0; part < pathParts; part++) {
      root = root.getParent();
      if (root == null) {
        return null;
      }
    }
    return root;
  }

  private static Path normalize(Path path) {
    return Objects.requireNonNull(path, "cacheDirectory").toAbsolutePath().normalize();
  }
}
