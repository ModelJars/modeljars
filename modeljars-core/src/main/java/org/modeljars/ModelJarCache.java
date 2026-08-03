package org.modeljars;

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
        .resolve(sha256)
        .resolve("model." + format);
  }

  private static Path normalize(Path path) {
    return Objects.requireNonNull(path, "cacheDirectory").toAbsolutePath().normalize();
  }
}
