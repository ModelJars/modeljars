package org.modeljars;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Registry of model marker descriptors. */
public interface ModelJarRegistry {
  List<ModelJarDescriptor> descriptors();

  Optional<ModelJarDescriptor> resolve(ModelJarRequirement requirement);

  static ModelJarRegistry fromClasspath() {
    return ClasspathModelJarRegistry.load();
  }

  static ModelJarRegistry fromProperties(Path path) {
    return PropertiesModelJarRegistry.load(path);
  }

  static ModelJarRegistry of(List<ModelJarDescriptor> descriptors) {
    return new InMemoryModelJarRegistry(descriptors);
  }

  static ModelJarRegistry composite(ModelJarRegistry... registries) {
    return new CompositeModelJarRegistry(Arrays.asList(registries));
  }
}

