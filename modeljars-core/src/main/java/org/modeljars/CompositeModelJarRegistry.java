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

import java.util.ArrayList;
import java.util.List;

/** Registry composed from multiple sources, preserving source priority order. */
public final class CompositeModelJarRegistry extends InMemoryModelJarRegistry {
  /**
   * Creates a registry by concatenating descriptors in source-priority order.
   *
   * @param registries ordered registries to combine
   */
  public CompositeModelJarRegistry(List<ModelJarRegistry> registries) {
    super(flatten(registries));
  }

  private static List<ModelJarDescriptor> flatten(List<ModelJarRegistry> registries) {
    List<ModelJarDescriptor> descriptors = new ArrayList<>();
    for (ModelJarRegistry registry : registries) {
      descriptors.addAll(registry.descriptors());
    }
    return descriptors;
  }
}
