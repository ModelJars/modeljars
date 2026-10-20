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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.router.CatalogDiscovery;
import com.integrallis.models.router.ModelCandidate;
import com.integrallis.models.router.ModelRouter;
import com.integrallis.models.router.NoEligibleModelException;
import com.integrallis.models.router.RoutingPolicy;
import com.integrallis.models.router.RoutingRequest;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * THE WHOLE CHAIN: REAL CATALOG, REAL DISCOVERY, REAL ROUTER, REAL CACHED WEIGHTS.
 *
 * <p>models-router carries no ModelJars dependency, so its own tests can only reach a catalog it
 * fakes. This module is the only place both halves exist, and the behaviour that needed proving
 * lives exactly at the seam: the catalog leaves unmeasured quality absent, and until Models 0.5.0
 * the router scored that absence as zero against a quality weight of 0.40. An honest catalog
 * therefore lost every route, which is what taught one demo to type quality numbers in by hand --
 * numbers that measured 16 to 35 points optimistic on public data.
 *
 * <p>So this asserts the seam end to end, against the artifacts actually installed on the machine
 * running it: that discovery reports absence rather than inventing a score, that the router scores
 * it as a declared prior rather than a zero, and that a quality floor refuses what nobody has
 * measured and says which of the two reasons applies.
 */
class RouterQualityEndToEndIntegrationTest {

  private static List<ModelCandidate> discovered() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("modeljars.integration.router.live"),
        "Run :modeljars:routerQualityIntegrationTest with cached weights on disk");
    var candidates = CatalogDiscovery.discover();
    Assumptions.assumeFalse(
        candidates.isEmpty(), "no catalogued weights are cached on this machine to route between");
    return candidates;
  }

  @Test
  void discoveryReportsAbsenceForTasksNobodyMeasuredOnTheseArtifacts() {
    var candidates = discovered();
    int measuredGrounding = 0;
    for (var candidate : candidates) {
      // Every qualified RAG artifact in this catalog is GUARDED_RAG, which the policy defines as
      // production-usable *with* the grounding and extractive fallback policy. That is a statement
      // about a pipeline, so it is not reported as the model's own grounding quality, and the
      // honest result is no score at all.
      if (candidate.measuredQualityFor("grounding").isPresent()) {
        measuredGrounding++;
      }
      // Chat is never measured by a RAG or tool qualification and must not be inferred from one.
      assertTrue(
          candidate.measuredQualityFor("chat").isEmpty(),
          candidate.id() + " reports a chat quality nobody measured");
    }
    assertEquals(
        0,
        measuredGrounding,
        "no catalogued artifact is qualified to answer grounded questions unguarded");
  }

  @Test
  void anUnmeasuredTaskScoresAPriorRatherThanZero() {
    var router = ModelRouter.builder().candidates(discovered()).build();
    var decision = router.route(RoutingRequest.builder("hello there").taskType("chat").build());

    var parts = decision.scoreBreakdown();
    // One key, the one the breakdown has always had. Models 0.5.0 put the prior under a second
    // key, `quality-unmeasured`; 0.5.1 reserved that name to stop a caller's scorer colliding with
    // it; 0.5.2 removed the key and kept the behaviour, because reserving a name that had been
    // legal in every prior release broke a fleet that happened to use it. The distinction between
    // absence and a measured zero lives in ModelCandidate.measuredQualityFor, which is typed.
    assertTrue(
        parts.containsKey("quality"),
        "an unmeasured task must still score under the quality key: " + parts.keySet());
    assertFalse(
        parts.containsKey("quality-unmeasured"),
        "0.5.2 removed the second key; a fleet's own scorer may be called that again");
    // Nothing on this machine measured chat, so the share is the prior -- positive, where the
    // scoring up to 0.4.3 made it zero and an honest catalog lost every route because of it.
    assertTrue(
        parts.get("quality") > 0,
        "the prior must contribute a positive share of the score: " + parts);
    assertTrue(
        ModelRouter.UNMEASURED_QUALITY_PRIOR > 0,
        "the prior is the router's own constant, not a number this test invented");
  }

  @Test
  void aQualityFloorRefusesWhatNobodyMeasuredAndSaysWhy() {
    var router =
        ModelRouter.builder()
            .candidates(discovered())
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.6))
            .build();

    var refusal =
        assertThrows(
            NoEligibleModelException.class,
            () -> router.route(RoutingRequest.builder("hello there").taskType("chat").build()));
    assertTrue(
        refusal.getMessage().contains("measured quality"),
        "the refusal must distinguish unmeasured from below-the-floor: " + refusal.getMessage());
    assertTrue(refusal.getMessage().contains("chat"), refusal.getMessage());
  }

  @Test
  void aCandidateTheCatalogMeasuredCanStillClearAFloor() {
    var candidates = discovered();
    var measured =
        candidates.stream()
            .filter(candidate -> candidate.measuredQualityFor("tool-use").isPresent())
            .findFirst();
    Assumptions.assumeTrue(
        measured.isPresent(),
        "no cached artifact carries a tool-use qualification on this machine");

    double score = measured.get().measuredQualityFor("tool-use").getAsDouble();
    var router =
        ModelRouter.builder()
            .candidates(candidates)
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(score - 0.01))
            .build();

    var decision = router.route(RoutingRequest.builder("call a tool").taskType("tool-use").build());
    assertTrue(
        decision.selected().measuredQualityFor("tool-use").isPresent(),
        "a floor must admit a candidate whose measurement clears it: " + decision.selected().id());
  }
}
