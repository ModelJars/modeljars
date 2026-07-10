package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionRangeTest {
  @Test
  void supportsInclusiveExclusiveRanges() {
    VersionRange range = VersionRange.parse("[3.0.0,4.0.0)");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertTrue(range.contains(ModelVersion.parse("3.5.0")));
    assertFalse(range.contains(ModelVersion.parse("4.0.0")));
  }

  @Test
  void supportsOpenEndedRanges() {
    VersionRange range = VersionRange.parse("[3.0.0,)");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertTrue(range.contains(ModelVersion.parse("9.0.0")));
    assertFalse(range.contains(ModelVersion.parse("2.9.9")));
  }

  @Test
  void exactVersionMatchesOnlyThatVersion() {
    VersionRange range = VersionRange.parse("3.0.0");

    assertTrue(range.contains(ModelVersion.parse("3.0.0")));
    assertFalse(range.contains(ModelVersion.parse("3.0.1")));
  }
}

