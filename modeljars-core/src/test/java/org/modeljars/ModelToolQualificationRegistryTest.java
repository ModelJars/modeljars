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

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelToolQualificationRegistryTest {
  private static final String ARTIFACT_SHA =
      "b43aabfcaf1a6db6acf488076eab71d823c08697c7af4521fc1d174b60ede5ba";

  @Test
  void loadsAuditableToolQualificationAndMatchesOnlyTheCertifiedArtifact() {
    ModelToolQualificationRegistry registry =
        ModelToolQualificationRegistry.fromProperties(properties());

    assertEquals("needle2-tool-conformance-v2", registry.policyVersion());
    assertEquals("1".repeat(40), registry.modelsRevision());
    assertEquals(1, registry.qualifiedModels());
    assertEquals(0, registry.rejectedModels());

    ModelToolQualification qualification = registry.qualified().getFirst();
    assertTrue(qualification instanceof ModelExecutionQualification);
    assertEquals("cactus_compute_needle2_cact_cq2_mixed", qualification.modelId());
    assertEquals("pure-java", qualification.backend());
    assertEquals("needle2", qualification.promptTemplate());
    assertEquals("needle2-upstream-playground-v1", qualification.workload());
    assertEquals(14, qualification.attempts());
    assertEquals(12, qualification.passed());
    assertEquals(0.918918918918919, qualification.expectedArgumentAccuracy());
    assertEquals(
        URI.create("https://github.com/integrallis/models/blob/" + "2".repeat(40) + "/report.json"),
        qualification.reportUri());
    assertTrue(qualification.productionUsable());
    assertEquals(List.of(qualification), registry.qualificationsFor(descriptor(ARTIFACT_SHA)));
    assertTrue(registry.qualificationsFor(descriptor("0".repeat(64))).isEmpty());
  }

  @Test
  void refusesToCallSubstandardEvidenceQualified() {
    Properties properties = properties();
    properties.setProperty(
        "toolQualification.cactus_compute_needle2_cact_cq2_mixed.expectedArgumentAccuracy", "0.89");

    assertThrows(
        IllegalArgumentException.class,
        () -> ModelToolQualificationRegistry.fromProperties(properties));
  }

  @Test
  void acceptsPublishedChatTemplateSlugs() {
    Properties properties = properties();
    properties.setProperty(
        "toolQualification.cactus_compute_needle2_cact_cq2_mixed.promptTemplate",
        "chatml-no-think");

    ModelToolQualification qualification =
        ModelToolQualificationRegistry.fromProperties(properties).qualified().getFirst();

    assertEquals("chatml-no-think", qualification.promptTemplate());
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.setProperty("modeljars.toolQualifications.schemaVersion", "1");
    properties.setProperty("modeljars.toolQualifications.generatedAt", "2026-08-27T18:45:45Z");
    properties.setProperty(
        "modeljars.toolQualifications.policyVersion", "needle2-tool-conformance-v2");
    properties.setProperty("modeljars.toolQualifications.modelsRevision", "1".repeat(40));
    properties.setProperty("modeljars.toolQualifications.qualifiedModels", "1");
    properties.setProperty("modeljars.toolQualifications.rejectedModels", "0");
    String prefix = "toolQualification.cactus_compute_needle2_cact_cq2_mixed.";
    properties.setProperty(prefix + "model", "Cactus Compute Needle 2 CACT CQ2 Mixed");
    properties.setProperty(prefix + "backend", "pure-java");
    properties.setProperty(prefix + "backendVersion", "models@" + "1".repeat(40));
    properties.setProperty(prefix + "workload", "needle2-upstream-playground-v1");
    properties.setProperty(prefix + "promptTemplate", "needle2");
    properties.setProperty(prefix + "artifactSha256", ARTIFACT_SHA);
    properties.setProperty(prefix + "artifactSizeBytes", "13737807");
    properties.setProperty(prefix + "reportPath", "report.json");
    properties.setProperty(
        prefix + "reportUri",
        "https://github.com/integrallis/models/blob/" + "2".repeat(40) + "/report.json");
    properties.setProperty(prefix + "reportSha256", "3".repeat(64));
    properties.setProperty(prefix + "verdict", "PASS");
    properties.setProperty(prefix + "qualified", "true");
    properties.setProperty(prefix + "attempts", "14");
    properties.setProperty(prefix + "passed", "12");
    properties.setProperty(prefix + "structuredOutputRate", "1.0");
    properties.setProperty(prefix + "toolSelectionExactRate", "1.0");
    properties.setProperty(prefix + "schemaValidityRate", "1.0");
    properties.setProperty(prefix + "declaredArgumentsOnlyRate", "1.0");
    properties.setProperty(prefix + "expectedArgumentAccuracy", "0.918918918918919");
    properties.setProperty(prefix + "refusalAccuracy", "1.0");
    properties.setProperty(prefix + "p95EndToEndMillis", "53195.0");
    properties.setProperty(prefix + "suiteSha256", "4".repeat(64));
    properties.setProperty(prefix + "sourceRepository", "https://github.com/cactus-compute/needle");
    properties.setProperty(prefix + "sourceRevision", "5".repeat(40));
    properties.setProperty(prefix + "sourcePath", "needle/playground/app.js");
    String environment = prefix + "environment.";
    properties.setProperty(environment + "hostname", "qualification-host");
    properties.setProperty(environment + "osName", "Mac OS X");
    properties.setProperty(environment + "osVersion", "26.6.1");
    properties.setProperty(environment + "architecture", "x86_64");
    properties.setProperty(environment + "cpuModel", "Intel Core i7-9750H");
    properties.setProperty(environment + "availableProcessors", "12");
    properties.setProperty(environment + "totalMemoryBytes", "34359738368");
    properties.setProperty(environment + "maxHeapBytes", "8589934592");
    properties.setProperty(environment + "javaVersion", "25.0.3");
    properties.setProperty(environment + "javaVendor", "Eclipse Adoptium");
    properties.setProperty(environment + "vmName", "OpenJDK 64-Bit Server VM");
    return properties;
  }

  private static ModelJarDescriptor descriptor(String sha256) {
    return new ModelJarDescriptor(
        "cactus_compute_needle2_cact_cq2_mixed",
        "hf://Cactus-Compute/needle2",
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:cactus-compute.needle2.cact.cq2_mixed:2.0.0-cq2_mixed.1"),
        ModelVersion.parse("2.0.0"),
        "cq2_mixed",
        "cact",
        "needle2",
        "CQ2_MIXED",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(sha256),
        Optional.of(13_737_807L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation", "chat", "tool-calling"),
        Set.of(),
        List.of(),
        java.util.Map.of("pure-java", true),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Set.of("tool-use"),
        ModelDimensions.unknown());
  }
}
