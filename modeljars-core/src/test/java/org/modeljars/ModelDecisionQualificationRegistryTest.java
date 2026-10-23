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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reading decision evidence, and refusing to read it wrongly. */
class ModelDecisionQualificationRegistryTest {

  private static final String DIGEST =
      "c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2";

  @Test
  void readsTheMeasuredLayaEntry() throws IOException {
    ModelDecisionQualificationRegistry registry = parse(laya("RANKED_DECISION", "0.1638", "0"));

    assertEquals(1, registry.entries().size());
    ModelDecisionQualificationRegistry.Entry entry = registry.entries().get(0);
    assertEquals("laya_q8_0", entry.modelId());
    assertEquals("RANKED_DECISION", entry.tier());
    assertEquals(120, entry.cases());
    assertEquals(0.7833, entry.accuracy(), 1e-9);
    assertEquals(0.3004, entry.chanceAccuracy(), 1e-9);
    assertEquals(0.1638, entry.calibrationError(), 1e-9);
    assertEquals(2.607, entry.chanceMultiple(), 1e-3);
    assertFalse(
        entry.exportsConfidenceQuality(), "the ranked tier must not export a confidence quality");
    assertFalse(
        entry.testedUncalibratedReporting(), "zero exercised buckets means untested, not upheld");
  }

  @Test
  void bindsEvidenceToAnExactArtifactAndNotJustAnId() throws IOException {
    // A re-quantized or re-converted artifact keeps the alias and changes the bytes. Evidence that
    // followed the alias alone would describe a model nobody scored.
    //
    // Driven from a REAL descriptor on the classpath rather than a synthetic one, because the
    // lookup reads descriptor.alias() and descriptor.sha256() and a hand-built stand-in would be
    // testing this test's idea of a descriptor.
    ModelJarDescriptor real =
        ModelJarRegistry.fromClasspath().descriptors().stream()
            .filter(descriptor -> descriptor.sha256().isPresent())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no digest-bearing descriptor available"));
    String alias = real.alias();
    String digest = real.sha256().orElseThrow().toLowerCase(java.util.Locale.ROOT);

    ModelDecisionQualificationRegistry matching = parse(entry(alias, digest));
    assertTrue(
        matching.qualificationFor(real).isPresent(),
        "evidence naming this exact alias and digest must match");

    ModelDecisionQualificationRegistry wrongDigest = parse(entry(alias, "0".repeat(64)));
    assertTrue(
        wrongDigest.qualificationFor(real).isEmpty(),
        "evidence for different bytes must not follow the alias");

    ModelDecisionQualificationRegistry wrongAlias = parse(entry("not_this_model", digest));
    assertTrue(
        wrongAlias.qualificationFor(real).isEmpty(),
        "evidence for a different model must not match on digest alone");
  }

  @Test
  void refusesATierItCannotInterpretAtLoadRatherThanAtUse() throws IOException {
    ModelJarException failure =
        assertThrows(ModelJarException.class, () -> parse(laya("PROBABLY_FINE", "0.1638", "0")));
    assertTrue(failure.getMessage().contains("Unknown decision tier"), failure.getMessage());
  }

  @Test
  void refusesTheWrongSchemaVersion() {
    String body =
        "modeljars.decisionQualifications.schemaVersion=2\n"
            + "decisionQualification.laya_q8_0.model=Laya\n";
    assertThrows(ModelJarException.class, () -> parse(body));
  }

  @Test
  void refusesAMissingProperty() {
    String body =
        "modeljars.decisionQualifications.schemaVersion=1\n"
            + "decisionQualification.laya_q8_0.model=Laya Q8_0\n";
    ModelJarException failure = assertThrows(ModelJarException.class, () -> parse(body));
    assertTrue(
        failure.getMessage().contains("Missing decision qualification property"),
        failure.getMessage());
  }

  @Test
  void readsAnAbsentCalibrationAsNaNRatherThanZero() throws IOException {
    // Zero would read as perfect calibration. A cohort where every answer declared itself
    // uncalibrated has nothing to report, and the record must say so.
    ModelDecisionQualificationRegistry registry = parse(laya("RANKED_DECISION", "NaN", "0"));

    assertTrue(Double.isNaN(registry.entries().get(0).calibrationError()));
  }

  @Test
  void anEmptyClasspathIsAnEmptyRegistryAndNotAFailure() {
    // A machine with no decision models installed is the ordinary case, not an error worth
    // propagating into a caller happily using text generators.
    ModelDecisionQualificationRegistry registry =
        ModelDecisionQualificationRegistry.fromClasspath(
            new java.net.URLClassLoader(new java.net.URL[0], null));

    assertTrue(registry.entries().isEmpty());
    assertEquals("", registry.generatedAt());
  }

  /** One entry under an arbitrary alias and digest, for the binding test. */
  private static String entry(String alias, String digest) {
    return laya("RANKED_DECISION", "0.1638", "0")
        .replace("laya_q8_0", alias)
        .replace(DIGEST, digest);
  }

  private static String laya(String tier, String calibrationError, String exercised) {
    return "modeljars.decisionQualifications.schemaVersion=1\n"
        + "modeljars.decisionQualifications.generatedAt=2026-10-22T01:00:00Z\n"
        + "modeljars.decisionQualifications.policyVersion=decision-calibration-v2\n"
        + "modeljars.decisionQualifications.modelsRevision=0.5.3\n"
        + "decisionQualification.laya_q8_0.model=Laya Q8_0\n"
        + "decisionQualification.laya_q8_0.backend=pure-java\n"
        + "decisionQualification.laya_q8_0.artifactSha256="
        + DIGEST
        + "\n"
        + "decisionQualification.laya_q8_0.tier="
        + tier
        + "\n"
        + "decisionQualification.laya_q8_0.cohort=jevbench-120\n"
        + "decisionQualification.laya_q8_0.cases=120\n"
        + "decisionQualification.laya_q8_0.chanceAccuracy=0.3004\n"
        + "decisionQualification.laya_q8_0.accuracy=0.7833\n"
        + "decisionQualification.laya_q8_0.calibrationError="
        + calibrationError
        + "\n"
        + "decisionQualification.laya_q8_0.uncalibratedBucketsExercised="
        + exercised
        + "\n";
  }

  private static ModelDecisionQualificationRegistry parse(String body) throws IOException {
    return ModelDecisionQualificationRegistry.parse(
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }
}
