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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Registry of model marker descriptors. */
public interface ModelJarRegistry {
  /**
   * Returns every descriptor in this registry.
   *
   * @return immutable model descriptors
   */
  List<ModelJarDescriptor> descriptors();

  /**
   * Resolves the highest compatible model version.
   *
   * @param requirement model selection constraints
   * @return the selected descriptor, or empty when no descriptor matches
   */
  Optional<ModelJarDescriptor> resolve(ModelJar requirement);

  /**
   * Loads markers visible to the current thread context class loader.
   *
   * @return the combined classpath registry
   */
  static ModelJarRegistry fromClasspath() {
    return ClasspathModelJarRegistry.load();
  }

  /**
   * Loads a registry from a marker properties file.
   *
   * @param path marker properties file
   * @return the parsed registry
   */
  static ModelJarRegistry fromProperties(Path path) {
    return PropertiesModelJarRegistry.load(path);
  }

  /**
   * Creates an immutable in-memory registry.
   *
   * @param descriptors descriptors exposed by the registry
   * @return the in-memory registry
   */
  static ModelJarRegistry of(List<ModelJarDescriptor> descriptors) {
    return new InMemoryModelJarRegistry(descriptors);
  }

  /**
   * Combines registries in source-priority order.
   *
   * @param registries ordered registries to combine
   * @return the composite registry
   */
  static ModelJarRegistry composite(ModelJarRegistry... registries) {
    return new CompositeModelJarRegistry(Arrays.asList(registries));
  }
}
