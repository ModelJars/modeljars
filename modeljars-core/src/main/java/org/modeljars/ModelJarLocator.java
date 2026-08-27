/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeljars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Resolves a model descriptor to a local model file path. */
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
