package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelDimensionsTest {
  @Test
  void estimatesWeightsPlusKvCacheWithoutClaimingRuntimeOverhead() {
    ModelDimensions dimensions =
        new ModelDimensions(
            Optional.of(7_615_616_000L),
            Optional.of(32_768),
            Optional.of(3_584),
            Optional.of(28),
            Optional.of(28),
            Optional.of(4),
            Optional.of(18_944),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(28));

    ModelMemoryEstimate estimate =
        dimensions
            .estimateMemory(4_096, KvCachePrecision.FLOAT16, 4_683_074_720L)
            .orElseThrow();

    assertEquals(4_096, estimate.contextTokens());
    assertEquals(234_881_024L, estimate.kvCacheBytes());
    assertEquals(4_917_955_744L, estimate.minimumBytes());
    assertEquals(KvCachePrecision.FLOAT16, estimate.kvCachePrecision());
    assertTrue(estimate.excludesRuntimeOverhead());
  }

  @Test
  void returnsEmptyWhenKvDimensionsAreUnavailable() {
    assertTrue(
        ModelDimensions.unknown()
            .estimateMemory(4_096, KvCachePrecision.FLOAT16, 1_000L)
            .isEmpty());
  }

  @Test
  void usesExplicitAsymmetricKeyAndValueLengths() {
    ModelDimensions dimensions =
        new ModelDimensions(
            Optional.of(27_000_000_000L),
            Optional.of(262_144),
            Optional.of(5_120),
            Optional.of(64),
            Optional.of(48),
            Optional.of(8),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(256),
            Optional.of(128),
            Optional.of(16));

    ModelMemoryEstimate estimate =
        dimensions.estimateMemory(1, KvCachePrecision.FLOAT16, 1_000L).orElseThrow();

    assertEquals(98_304L, estimate.kvCacheBytes());
    assertEquals(99_304L, estimate.minimumBytes());
  }

  @Test
  void rejectsContextBeyondTheAdvertisedLimit() {
    ModelDimensions dimensions =
        new ModelDimensions(
            Optional.empty(),
            Optional.of(2_048),
            Optional.of(128),
            Optional.of(2),
            Optional.of(2),
            Optional.of(2),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(2));

    assertThrows(
        IllegalArgumentException.class,
        () -> dimensions.estimateMemory(2_049, KvCachePrecision.FLOAT16, 1_000L));
  }
}
