package org.modeljars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Small facade for resolving a model descriptor to a local model file path. */
public final class ModelJarLocator {
  private final ModelJarRegistry registry;

  public ModelJarLocator(ModelJarRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  public Optional<Path> localPath(ModelJarRequirement requirement) {
    return registry.resolve(requirement).flatMap(ModelJarDescriptor::localPath);
  }

  public Path requireLocalPath(ModelJarRequirement requirement) {
    Path path =
        localPath(requirement)
            .orElseThrow(() -> new ModelJarException("No local path for " + requirement.source()));
    if (!Files.exists(path)) {
      throw new ModelJarException("Model file does not exist: " + path);
    }
    return path;
  }
}

