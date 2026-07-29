package org.modeljars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Small facade for resolving a model descriptor to a local model file path. */
public final class ModelJarLocator {
  private final ModelJarRegistry registry;

  /**
   * Creates a locator backed by a model registry.
   *
   * @param registry registry used to resolve model selectors
   */
  public ModelJarLocator(ModelJarRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /**
   * Resolves the configured local path without checking the file system.
   *
   * @param requirement model selection constraints
   * @return configured local path, or empty when no descriptor or path matches
   */
  public Optional<Path> localPath(ModelJar requirement) {
    return registry.resolve(requirement).flatMap(ModelJarDescriptor::localPath);
  }

  /**
   * Resolves a local model path and requires the file to exist.
   *
   * @param requirement model selection constraints
   * @return existing local model path
   */
  public Path requireLocalPath(ModelJar requirement) {
    Path path =
        localPath(requirement)
            .orElseThrow(() -> new ModelJarException("No local path for " + requirement.source()));
    if (!Files.exists(path)) {
      throw new ModelJarException("Model file does not exist: " + path);
    }
    return path;
  }
}
