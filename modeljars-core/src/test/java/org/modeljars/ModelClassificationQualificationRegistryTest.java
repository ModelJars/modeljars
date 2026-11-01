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

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelClassificationQualificationRegistryTest {
  @TempDir Path temporary;

  static Properties properties() {
    var e =
        ModelClassificationQualificationTest.evidence(
            true,
            true,
            ModelClassificationQualification.qualityFloors(),
            ModelClassificationQualificationTest.buckets(3));
    var p = new Properties();
    p.setProperty("modeljars.classification.schemaVersion", "1");
    p.setProperty("modeljars.classification.count", "1");
    String x = "classification.0.";
    Map.of(
            "modelId",
            e.modelId(),
            "backend",
            e.backend(),
            "backendVersion",
            e.backendVersion(),
            "report",
            e.report().toString(),
            "reportSha256",
            e.reportSha256())
        .forEach((k, v) -> p.setProperty(x + k, v));
    Map.of(
            "maximumOracleQualityLoss",
            "0",
            "maximumBaseQualityRegression",
            "0",
            "qualityCases",
            "1529",
            "inferenceErrors",
            "0",
            "numericalParityPassed",
            "true",
            "releasedRuntimeVerified",
            "true",
            "qualified",
            "true")
        .forEach((k, v) -> p.setProperty(x + k, v));
    e.quality().forEach((k, v) -> p.setProperty(x + "quality." + k, v.toString()));
    p.setProperty(x + "files.count", "" + e.files().size());
    for (int i = 0; i < e.files().size(); i++) {
      var f = e.files().get(i);
      String n = x + "files." + i + ".";
      Map.of(
              "path",
              f.path(),
              "role",
              f.role(),
              "downloadUri",
              f.downloadUri().toString(),
              "sha256",
              f.sha256(),
              "sizeBytes",
              "" + f.sizeBytes())
          .forEach((k, v) -> p.setProperty(n + k, v));
    }
    p.setProperty(x + "performance.count", "" + e.performance().size());
    for (int i = 0; i < e.performance().size(); i++) {
      var b = e.performance().get(i);
      String n = x + "performance." + i + ".";
      Map.of(
              "pair",
              "" + b.pair(),
              "shape",
              b.shape(),
              "samples",
              "" + b.samples(),
              "throughputRatio",
              "" + b.throughputRatio(),
              "p95LatencyRatio",
              "" + b.p95LatencyRatio())
          .forEach((k, v) -> p.setProperty(n + k, v));
    }
    return p;
  }

  static ModelClassificationQualificationRegistry parse(Properties p) throws IOException {
    var bytes = new ByteArrayOutputStream();
    p.store(bytes, null);
    return ModelClassificationQualificationRegistry.parse(
        new ByteArrayInputStream(bytes.toByteArray()));
  }

  @Test
  void parsesCompleteEvidenceAndRejectsChangedGates() throws Exception {
    var p = properties();
    assertTrue(parse(p).entries().getFirst().qualified());
    p.setProperty("classification.0.performance.0.p95LatencyRatio", "1.501");
    var badPerformance = p;
    assertThrows(IllegalArgumentException.class, () -> parse(badPerformance));
    p = properties();
    p.setProperty("classification.0.numericalParityPassed", "tru");
    var malformed = p;
    assertThrows(ModelJarException.class, () -> parse(malformed));
  }

  @Test
  void missingEvidenceNeverAdmitsModels() throws Exception {
    try (var loader = new URLClassLoader(new URL[0], null)) {
      assertTrue(
          ModelClassificationQualificationRegistry.fromClasspath(loader).entries().isEmpty());
    }
    var p = properties();
    p.remove("classification.0.quality.fresh.boolq.accuracy");
    assertThrows(ModelJarException.class, () -> parse(p));
  }

  @Test
  void conflictingClasspathEvidenceFailsClosed() throws Exception {
    var first =
        temporary.resolve("first").resolve(ModelClassificationQualificationRegistry.RESOURCE);
    var second =
        temporary.resolve("second").resolve(ModelClassificationQualificationRegistry.RESOURCE);
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    var p = properties();
    try (var out = Files.newOutputStream(first)) {
      p.store(out, null);
    }
    p.setProperty("classification.0.files.1.sha256", "b".repeat(64));
    try (var out = Files.newOutputStream(second)) {
      p.store(out, null);
    }
    try (var loader =
        new URLClassLoader(
            new URL[] {
              temporary.resolve("first").toUri().toURL(),
              temporary.resolve("second").toUri().toURL()
            },
            null)) {
      assertThrows(
          ModelJarException.class,
          () -> ModelClassificationQualificationRegistry.fromClasspath(loader));
    }
  }
}
