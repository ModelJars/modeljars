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
