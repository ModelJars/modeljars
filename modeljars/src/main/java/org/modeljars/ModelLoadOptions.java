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

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable controls for resolving, installing, and loading a ModelJar.
 *
 * @param backend automatic or explicit backend policy
 * @param offline whether loading is restricted to an existing verified cache entry
 * @param cacheDirectory content-addressed artifact cache root
 */
public record ModelLoadOptions(ModelBackend backend, boolean offline, Path cacheDirectory) {
  /** System property that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_PROPERTY = ModelJarCache.CACHE_DIRECTORY_PROPERTY;

  /** Environment variable that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_ENV = ModelJarCache.CACHE_DIRECTORY_ENV;

  /** Validates and normalizes model loading controls. */
  public ModelLoadOptions {
    backend = Objects.requireNonNull(backend, "backend");
    cacheDirectory =
        Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
  }

  /**
   * Returns options that select the qualified backend and use the default online cache.
   *
   * @return default model loading options
   */
  public static ModelLoadOptions defaults() {
    return builder().build();
  }

  /**
   * Creates a model loading options builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ModelLoadOptions}. */
  public static final class Builder {
    private ModelBackend backend = ModelBackend.AUTO;
    private boolean offline;
    private Path cacheDirectory;

    private Builder() {}

    /**
     * Selects automatic, Java, or native backend loading.
     *
     * @param value backend policy
     * @return this builder
     */
    public Builder backend(ModelBackend value) {
      backend = Objects.requireNonNull(value, "backend");
      return this;
    }

    /**
     * Controls whether loading may download a missing artifact.
     *
     * @param value {@code true} to require an existing verified cache entry
     * @return this builder
     */
    public Builder offline(boolean value) {
      offline = value;
      return this;
    }

    /**
     * Overrides the content-addressed cache root.
     *
     * @param value cache root
     * @return this builder
     */
    public Builder cacheDirectory(Path value) {
      cacheDirectory = Objects.requireNonNull(value, "cacheDirectory");
      return this;
    }

    /**
     * Builds immutable loading options.
     *
     * @return model loading options
     */
    public ModelLoadOptions build() {
      return new ModelLoadOptions(
          backend,
          offline,
          cacheDirectory == null ? ModelJarCache.defaultDirectory() : cacheDirectory);
    }
  }
}
