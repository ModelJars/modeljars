package org.modeljars;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable in-memory registry implementation. */
public class InMemoryModelJarRegistry implements ModelJarRegistry {
  private final List<ModelJarDescriptor> descriptors;

  public InMemoryModelJarRegistry(List<ModelJarDescriptor> descriptors) {
    this.descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
  }

  @Override
  public List<ModelJarDescriptor> descriptors() {
    return descriptors;
  }

  @Override
  public Optional<ModelJarDescriptor> resolve(ModelJarRequirement requirement) {
    Objects.requireNonNull(requirement, "requirement");
    List<ModelJarDescriptor> matches = new ArrayList<>();
    for (ModelJarDescriptor descriptor : descriptors) {
      if (matches(requirement, descriptor)) {
        matches.add(descriptor);
      }
    }

    return matches.stream()
        .max(Comparator.comparing(ModelJarDescriptor::modelVersion).thenComparing(ModelJarDescriptor::alias));
  }

  private static boolean matches(ModelJarRequirement requirement, ModelJarDescriptor descriptor) {
    if (!descriptor.matchesSource(requirement.source())) {
      return false;
    }
    if (requirement.versionRange().isPresent()
        && !requirement.versionRange().orElseThrow().contains(descriptor.modelVersion())) {
      return false;
    }
    if (requirement.variant().isPresent()
        && !requirement.variant().orElseThrow().equalsIgnoreCase(descriptor.variant())) {
      return false;
    }
    if (requirement.backend().isPresent()
        && !descriptor.supportsBackend(requirement.backend().orElseThrow())) {
      return false;
    }
    return requirement.capability().isEmpty()
        || descriptor.capabilities().contains(requirement.capability().orElseThrow());
  }
}

