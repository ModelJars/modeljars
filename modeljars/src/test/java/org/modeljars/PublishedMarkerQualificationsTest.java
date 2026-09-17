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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Loads the qualification snapshots of markers already on Maven Central beside the catalog bundled
 * in this runtime, as an application classpath does.
 *
 * <p>Each fixture below {@code published-markers/} is the verbatim {@code META-INF/modeljars}
 * resource from the published marker JAR named by its directory (for Granite 4.1 3B Q4_K_M: Maven
 * Central SHA-1 {@code dac2900fd52900996c124af4e1a7bdb5330183c2}). Published markers are immutable,
 * so the bundled catalog must stay loadable beside them.
 */
class PublishedMarkerQualificationsTest {
  private static final String GRANITE_MARKER =
      "published-markers/ibm-granite.granite-4.1-3b-gguf.q4_k_m-4.1.0-q4_k_m.2";

  @Test
  void bundledRagQualificationsLoadBesideThePublishedGraniteMarker() throws Exception {
    // Regression: the Granite marker was published while the catalog declared
    // targetQualifiedModels=33; the catalog was then corrected to 25 without advancing
    // generatedAt. The registry refuses differing metadata at one generation instant, so ModelJars
    // 0.1.40 to 0.1.42 failed with "Conflicting RAG qualification catalog metadata at the same
    // generation instant" for any application with the Granite marker on its classpath.
    try (URLClassLoader classpath = withPublishedMarker(GRANITE_MARKER)) {
      String resource = "META-INF/modeljars/qualifications-v1.properties";
      assertEquals(
          Collections.list(getClass().getClassLoader().getResources(resource)).size() + 1,
          Collections.list(classpath.getResources(resource)).size(),
          "the published marker fixture must be visible beside the bundled catalog");

      ModelRagQualificationRegistry registry =
          ModelRagQualificationRegistry.fromClasspath(classpath);
      ModelRagQualificationRegistry bundled =
          ModelRagQualificationRegistry.fromClasspath(getClass().getClassLoader());

      assertEquals(bundled.generatedAt(), registry.generatedAt());
      assertEquals(bundled.targetQualifiedModels(), registry.targetQualifiedModels());
      assertTrue(
          registry.qualifications().stream()
              .anyMatch(
                  qualification ->
                      qualification.modelId().equals("ibm_granite_granite_4_1_3b_gguf_q4_k_m")));
    }
  }

  private URLClassLoader withPublishedMarker(String fixture)
      throws IOException, URISyntaxException {
    URL root = getClass().getClassLoader().getResource(fixture);
    if (root == null) {
      throw new IOException("missing published marker fixture: " + fixture);
    }
    return new URLClassLoader(
        new URL[] {Path.of(root.toURI()).toUri().toURL()}, getClass().getClassLoader());
  }
}
