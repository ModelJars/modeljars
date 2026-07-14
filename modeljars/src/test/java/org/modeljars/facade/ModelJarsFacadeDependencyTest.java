package org.modeljars.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelVersion;

class ModelJarsFacadeDependencyTest {
  @Test
  void exposesTheModelJarsApiThroughTheFacadeDependency() {
    assertEquals("1.2.3", ModelVersion.parse("1.2.3").toString());
  }

  @Test
  void exposesTheLaunchCatalogThroughTheFacadeDependency() {
    assertTrue(ModelJarRegistry.fromClasspath().descriptors().size() >= 100);
  }
}
