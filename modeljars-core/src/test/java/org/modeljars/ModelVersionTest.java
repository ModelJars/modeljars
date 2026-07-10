package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelVersionTest {
  @Test
  void comparesSemanticVersions() {
    assertTrue(ModelVersion.parse("3.1.0").compareTo(ModelVersion.parse("3.0.9")) > 0);
    assertTrue(ModelVersion.parse("3.0.0").compareTo(ModelVersion.parse("3.0.0-rc.1")) > 0);
    assertTrue(ModelVersion.parse("3.0.0-rc.2").compareTo(ModelVersion.parse("3.0.0-rc.1")) > 0);
  }

  @Test
  void ignoresBuildMetadataForOrderingButPreservesValue() {
    ModelVersion version = ModelVersion.parse("3.0.0+q4_0");

    assertEquals(0, version.compareTo(ModelVersion.parse("3.0.0+q8_0")));
    assertEquals("3.0.0+q4_0", version.toString());
  }

  @Test
  void rejectsNonSemanticVersion() {
    assertThrows(IllegalArgumentException.class, () -> ModelVersion.parse("Qwen3-0.6B"));
  }
}

