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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelSpeechQualificationRegistryTest {
  private static final String SHA =
      "4758ad908395dc73a1b973d9a29ce96941f4328594d1c6c1223e7b7710a6a131";

  @Test
  void parsesEvidenceAndSelectsTheExactSpeechArtifact() throws Exception {
    var registry = parse(true, 0.998389192, 24.922, 1.49, 430.0);

    var qualification = registry.qualificationFor(SHA).orElseThrow();
    assertEquals("soprano-q8", qualification.modelId());
    assertEquals("rust-ffm", qualification.backend());
    assertEquals(32_000, qualification.sampleRate());
    assertEquals(1, qualification.channels());
    assertEquals(5, qualification.trials());
    assertTrue(qualification.streaming());
    assertTrue(qualification.firstAudioBeforeCompletion());
    assertEquals("models-0.3.29", qualification.backendVersion());
    assertTrue(registry.qualificationFor("f".repeat(64)).isEmpty());
  }

  @Test
  void rejectsAQualifiedArtifactThatMissesAnyCorrectnessOrLatencyGate() {
    assertThrows(IllegalArgumentException.class, () -> parse(true, 0.994, 24.922, 1.49, 430.0));
    assertThrows(IllegalArgumentException.class, () -> parse(true, 0.998, 19.99, 1.49, 430.0));
    assertThrows(IllegalArgumentException.class, () -> parse(true, 0.998, 24.922, 2.01, 430.0));
    assertThrows(IllegalArgumentException.class, () -> parse(true, 0.998, 24.922, 1.49, 2000.1));
  }

  private static ModelSpeechQualificationRegistry parse(
      boolean qualified, double cosine, double sdr, double rtf, double ttfa) throws Exception {
    String properties =
        """
        modeljars.speechQualifications.schemaVersion=1
        modeljars.speechQualifications.generatedAt=2026-09-05T18:00:00Z
        modeljars.speechQualifications.policyVersion=speech-oracle-streaming-latency-v1
        modeljars.speechQualifications.modelsRevision=1234567890abcdef1234567890abcdef12345678
        speechQualification.soprano-q8.model=Soprano 1.1 80M Q8_0
        speechQualification.soprano-q8.backend=rust-ffm
        speechQualification.soprano-q8.backendVersion=models-0.3.29
        speechQualification.soprano-q8.workload=speech-oracle-streaming-latency-v1
        speechQualification.soprano-q8.artifactSha256=%s
        speechQualification.soprano-q8.artifactSizeBytes=123162336
        speechQualification.soprano-q8.report=benchmark-results/audio/soprano/report.json
        speechQualification.soprano-q8.reportSha256=1111111111111111111111111111111111111111111111111111111111111111
        speechQualification.soprano-q8.qualified=%s
        speechQualification.soprano-q8.oracleBackend=official-soprano-pytorch
        speechQualification.soprano-q8.oracleVersion=12fac06eb8fa53bad8b3941d3cb11e9c869477c4
        speechQualification.soprano-q8.probes=3
        speechQualification.soprano-q8.minimumPcmCosine=%s
        speechQualification.soprano-q8.minimumSignalToDifferenceDb=%s
        speechQualification.soprano-q8.sampleRate=32000
        speechQualification.soprano-q8.channels=1
        speechQualification.soprano-q8.streaming=true
        speechQualification.soprano-q8.firstAudioBeforeCompletion=true
        speechQualification.soprano-q8.trials=5
        speechQualification.soprano-q8.p95RealTimeFactor=%s
        speechQualification.soprano-q8.p95TimeToFirstAudioMillis=%s
        speechQualification.soprano-q8.peakRssBytes=536870912
        """
            .formatted(SHA, qualified, cosine, sdr, rtf, ttfa);
    return ModelSpeechQualificationRegistry.parse(
        new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1)));
  }
}
