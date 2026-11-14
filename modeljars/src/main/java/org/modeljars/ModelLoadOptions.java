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
 * @param maxContextLength the caller's bound on this runtime's context, in tokens, or {@link
 *     #UNBOUNDED_CONTEXT} to leave the decision to the deployment and the catalog
 */
public record ModelLoadOptions(
    ModelBackend backend, boolean offline, Path cacheDirectory, int maxContextLength) {
  /** System property that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_PROPERTY = ModelJarCache.CACHE_DIRECTORY_PROPERTY;

  /**
   * The Models setting a caller's {@link #maxContextLength()} is delivered as. It enters the
   * backend's override tier, which outranks the JVM system property of the same name and the
   * catalog's recommendation, so one runtime can be bounded without a JVM-global setting
   * (integrallis/models#357).
   */
  public static final String MAX_CONTEXT_LENGTH_PROPERTY = "models.purejava.maxContextLength";

  /** {@link #maxContextLength()} value meaning "no caller bound". */
  public static final int UNBOUNDED_CONTEXT = 0;

  /** Environment variable that overrides the default ModelJars cache directory. */
  public static final String CACHE_DIRECTORY_ENV = ModelJarCache.CACHE_DIRECTORY_ENV;

  /** Validates and normalizes model loading controls. */
  public ModelLoadOptions {
    backend = Objects.requireNonNull(backend, "backend");
    cacheDirectory =
        Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
    if (maxContextLength < UNBOUNDED_CONTEXT) {
      throw new IllegalArgumentException(
          "maxContextLength must be positive, or "
              + UNBOUNDED_CONTEXT
              + " for no caller bound: "
              + maxContextLength);
    }
  }

  /**
   * Options without a caller context bound.
   *
   * @param backend automatic or explicit backend policy
   * @param offline whether loading is restricted to an existing verified cache entry
   * @param cacheDirectory content-addressed artifact cache root
   * @deprecated use the canonical constructor or {@link #builder()}; kept so adding {@code
   *     maxContextLength} does not break existing callers.
   */
  @Deprecated(since = "0.1.79", forRemoval = true)
  public ModelLoadOptions(ModelBackend backend, boolean offline, Path cacheDirectory) {
    this(backend, offline, cacheDirectory, UNBOUNDED_CONTEXT);
  }

  /**
   * Whether the caller bounded this runtime's context.
   *
   * @return {@code true} when {@link #maxContextLength()} is a positive bound
   */
  public boolean boundsContext() {
    return maxContextLength > UNBOUNDED_CONTEXT;
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
    private int maxContextLength = UNBOUNDED_CONTEXT;

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
     * Bounds the context this one runtime is opened with, in tokens. Delivered to the backend as
     * {@link #MAX_CONTEXT_LENGTH_PROPERTY} in the override tier: it wins over the JVM system
     * property of that name and over the catalog's qualified recommendation for that runtime only;
     * other runtimes in the process are untouched. The default leaves the decision to the
     * deployment and the catalog.
     *
     * @param tokens the bound, in tokens; must be positive
     * @return this builder
     */
    public Builder maxContextLength(int tokens) {
      if (tokens <= UNBOUNDED_CONTEXT) {
        throw new IllegalArgumentException("maxContextLength must be positive: " + tokens);
      }
      maxContextLength = tokens;
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
          cacheDirectory == null ? ModelJarCache.defaultDirectory() : cacheDirectory,
          maxContextLength);
    }
  }
}
