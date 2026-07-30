package org.modeljars.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualification;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelVersion;

class ModelJarsFacadeDependencyTest {
  @Test
  void exposesTheModelJarsApiThroughTheFacadeDependency() {
    assertEquals("1.2.3", ModelVersion.parse("1.2.3").toString());
  }

  @Test
  void exposesOnlyTheQualifiedCatalogThroughTheFacadeDependency() {
    var descriptors = ModelJarRegistry.fromClasspath().descriptors();
    var qualifications = ModelRagQualificationRegistry.fromClasspath();

    assertEquals(qualifications.qualifiedModels(), descriptors.size());
    assertEquals(
        qualifications.qualified().stream()
            .map(ModelRagQualification::modelId)
            .collect(Collectors.toSet()),
        descriptors.stream().map(descriptor -> descriptor.alias()).collect(Collectors.toSet()));
    assertTrue(
        descriptors.stream()
            .allMatch(descriptor -> !qualifications.qualificationsFor(descriptor).isEmpty()));
    assertNull(
        getClass()
            .getClassLoader()
            .getResource(
                "META-INF/modeljars/models/wordtour_glove_6b_300d_optimal/wordtour_opt.txt"));
  }

  @Test
  void exposesBothModelsBackendsThroughTheFacadeDependency() {
    assertEquals("PureJavaBackend", PureJavaBackend.class.getSimpleName());
    assertEquals("RustFfmBackend", RustFfmBackend.class.getSimpleName());
  }
}
