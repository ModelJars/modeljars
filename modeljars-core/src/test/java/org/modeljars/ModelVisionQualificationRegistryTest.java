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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelVisionQualificationRegistryTest {
  @TempDir Path temporary;

  private static String fixture() throws IOException {
    try (var stream =
        ModelVisionQualificationRegistryTest.class.getResourceAsStream(
            "/vision/synthetic.properties")) {
      return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static ModelVisionQualificationRegistry parse(String value) throws IOException {
    return ModelVisionQualificationRegistry.parse(
        new ByteArrayInputStream(value.getBytes(StandardCharsets.ISO_8859_1)));
  }

  @Test
  void parsesNodeGeneratedPropertiesAndRetainsFailedEvidence() throws IOException {
    var entry = parse(fixture()).entries().getFirst();
    assertTrue(entry.qualified());
    assertEquals("test-fixture", entry.backendVersion());
    assertEquals(2, entry.files().size());
    assertEquals(4, entry.performance().size());
    assertEquals(.75, entry.datasets().get("textvqa").candidateScore());
    assertFalse(
        parse(fixture().replace("qualified=true", "qualified=false"))
            .entries()
            .getFirst()
            .qualified());
    assertTrue(
        parse("modeljars.vision.schemaVersion=1\nmodeljars.vision.count=0\n").entries().isEmpty());
  }

  @Test
  void rejectsMalformedOrIncompleteQualificationInsteadOfReturningAnEmptyRegistry()
      throws IOException {
    String valid = fixture();
    for (String invalid :
        List.of(
            valid.replace("schemaVersion=1", "schemaVersion=2"),
            valid.replace("count=1", "count=2"),
            valid.replace("qualified=true", "qualified=yes"),
            valid.replace("tokenAgreement=1", "tokenAgreement=NaN"),
            valid.replace("prefillRatio=1", "prefillRatio=1.11"),
            valid.replace("vqav2.cases=32", "vqav2.cases=31"))) {
      assertThrows(RuntimeException.class, () -> parse(invalid));
    }
  }

  @Test
  void classpathDeduplicatesIdenticalEvidenceButRejectsConflictingMarkers() throws Exception {
    Path first = resource("first", fixture());
    Path second = resource("second", fixture());
    assertEquals(1, load(first, second).entries().size());
    resource(
        "second", fixture().replace("backendVersion=test-fixture", "backendVersion=other-build"));
    assertThrows(ModelJarException.class, () -> load(first, second));
  }

  private Path resource(String name, String content) throws IOException {
    Path root = temporary.resolve(name);
    Path target = root.resolve(ModelVisionQualificationRegistry.RESOURCE);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.ISO_8859_1);
    return root;
  }

  private static ModelVisionQualificationRegistry load(Path first, Path second) throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    try (var loader =
        new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
      thread.setContextClassLoader(loader);
      return ModelVisionQualificationRegistry.fromClasspath();
    } finally {
      thread.setContextClassLoader(original);
    }
  }
}
