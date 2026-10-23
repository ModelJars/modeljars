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

import org.junit.jupiter.api.Test;

/**
 * What a decision qualification record will and will not claim.
 *
 * <p>Each refusal here corresponds to a way a published record could mislead a consumer who trusts
 * it, which is the only reason a record validates anything at all.
 */
class ModelDecisionQualificationTest {

  private static final String DIGEST = "c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2";

  @Test
  void acceptsTheMeasuredLayaRecordAndExportsNoConfidenceQuality() {
    ModelDecisionQualification qualification = laya().build();

    assertEquals("RANKED_DECISION", qualification.tier());
    assertFalse(
        qualification.exportsConfidenceQuality(),
        "a ranked-tier model must not publish a confidence quality");
    assertFalse(
        qualification.testedUncalibratedReporting(),
        "JevBench stops at six options, so the uncalibrated promise was never exercised");
  }

  @Test
  void refusesATierItDoesNotUnderstand() {
    // An unqualified model has no record at all. A tier this code cannot interpret would be
    // published as though its meaning were known.
    ModelJarException failure =
        assertThrows(ModelJarException.class, () -> laya().tier("PROBABLY_FINE").build());
    assertTrue(failure.getMessage().contains("Unknown decision tier"), failure.getMessage());
  }

  @Test
  void refusesAnAccuracyThatDoesNotBeatChance() {
    // Not a threshold -- a sanity check. A model no better than guessing has not been shown to
    // decide anything, whichever policy admitted it.
    ModelJarException failure =
        assertThrows(
            ModelJarException.class, () -> laya().accuracy(0.3004).chanceAccuracy(0.3004).build());
    assertTrue(failure.getMessage().contains("does not beat chance"), failure.getMessage());
  }

  @Test
  void refusesACalibratedTierWithNoMeasuredCalibration() {
    // The one combination that would be a lie: claiming the probability can be trusted while
    // recording that nothing measured it.
    assertThrows(
        ModelJarException.class,
        () -> laya().tier("CALIBRATED_DECISION").calibrationError(Double.NaN).build());
  }

  @Test
  void acceptsACalibratedTierWithAMeasuredCalibration() {
    ModelDecisionQualification qualification =
        laya().tier("CALIBRATED_DECISION").calibrationError(0.04).build();

    assertTrue(qualification.exportsConfidenceQuality());
  }

  @Test
  void refusesADigestThatIsNotOne() {
    assertThrows(ModelJarException.class, () -> laya().artifactSha256("not-a-digest").build());
    assertThrows(ModelJarException.class, () -> laya().reportSha256("ABCDEF").build());
  }

  @Test
  void refusesAQualificationOverNoCases() {
    assertThrows(ModelJarException.class, () -> laya().cases(0).build());
  }

  @Test
  void allowsAnAbsentPerTypeSubsetWithoutPretendingItScoredZero() {
    // A cohort with no `score` questions must not publish 0.0 for it, which would read as total
    // failure on a subset that was never asked.
    ModelDecisionQualification qualification = laya().scoreAccuracy(-1.0).build();

    assertTrue(qualification.scoreAccuracy() < 0.0);
  }

  /** The record as actually measured for Laya on 2026-10-22, used as the baseline to perturb. */
  private static Builder laya() {
    return new Builder();
  }

  private static final class Builder {
    private String tier = "RANKED_DECISION";
    private int cases = 120;
    private double chanceAccuracy = 0.3004;
    private double accuracy = 0.7833;
    private double choiceAccuracy = 0.7917;
    private double noulAccuracy = 0.7778;
    private double scoreAccuracy = 0.75;
    private double calibrationError = 0.1638;
    private String artifactSha256 = DIGEST;
    private String reportSha256 = DIGEST;

    Builder tier(String value) {
      this.tier = value;
      return this;
    }

    Builder cases(int value) {
      this.cases = value;
      return this;
    }

    Builder chanceAccuracy(double value) {
      this.chanceAccuracy = value;
      return this;
    }

    Builder accuracy(double value) {
      this.accuracy = value;
      return this;
    }

    Builder scoreAccuracy(double value) {
      this.scoreAccuracy = value;
      return this;
    }

    Builder calibrationError(double value) {
      this.calibrationError = value;
      return this;
    }

    Builder artifactSha256(String value) {
      this.artifactSha256 = value;
      return this;
    }

    Builder reportSha256(String value) {
      this.reportSha256 = value;
      return this;
    }

    ModelDecisionQualification build() {
      return new ModelDecisionQualification(
          "laya_q8_0",
          "Laya Q8_0",
          "pure-java",
          "0.5.3",
          "decision-calibration-v2",
          "jevbench-120",
          DIGEST,
          artifactSha256,
          449397600L,
          "experiments/laya-modern-bert/evidence/jevbench-120-decision-calibration-v2.json",
          reportSha256,
          tier,
          cases,
          chanceAccuracy,
          accuracy,
          choiceAccuracy,
          noulAccuracy,
          scoreAccuracy,
          calibrationError,
          0,
          0,
          "i9-14900HX / JDK 25");
    }
  }
}
