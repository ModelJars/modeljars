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
package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelVersion;

class DemoScriptGeneratorTest {

  private final DemoScriptGenerator generator = new DemoScriptGenerator("0.1.25");

  @Test
  void generatesAChatDemoUsingThePublicRuntimeAndRuntimeOwnedMetrics() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(descriptor(Set.of("chat", "text-generation")), Optional.empty());

    assertEquals(DemoScriptGenerator.Type.CHAT, demo.type());
    assertEquals("example-chat-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "//DEPS org.modeljars:modeljars:0.1.25",
        "//DEPS " + descriptor(Set.of()).markerCoordinate(),
        "ModelJars.openRuntime(MODEL)",
        "runtime.chatTemplate().render",
        "runtime.pipeline().lastGenerationMetrics()",
        "Logger.getLogger(\"org.modeljars\").setLevel(Level.WARNING)",
        "Input:",
        "Output:");
    assertTrue(!demo.source().contains("Ollama") && !demo.source().contains("llama.cpp"));
  }

  @Test
  void generatesAnEmbeddingDemoThatShowsTheInputVectorAndMeasurements() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(
            descriptor(Set.of("embeddings", "text-embedding")),
            Optional.of("Public transit connects people and cities."));

    assertEquals(DemoScriptGenerator.Type.EMBEDDING, demo.type());
    assertEquals("example-embedding-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "ModelJars.openEmbedding(MODEL)",
        "Public transit connects people and cities.",
        "Arrays.toString(vector)",
        "Dimensions:",
        "Embedding:");
  }

  @Test
  void generatesARealNeedleToolDemoWithConstrainedDecodingAndInMemoryExecution() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(
            descriptor(Set.of("chat", "text-generation", "tool-calling"), "needle2"),
            Optional.empty());

    assertEquals(DemoScriptGenerator.Type.TOOLS, demo.type());
    assertEquals("example-tools-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "new ToolSpec(",
        "set_lights",
        "lock_door",
        "\"{\\\"type\\\":\\\"object",
        "ToolCallTokenConstraints.compile",
        "ToolCallScanner.scan",
        "home.execute(call)",
        "runtime.pipeline().lastGenerationMetrics()");
  }

  @Test
  void rejectsModelsWithoutAnExecutableCapability() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.generate(descriptor(Set.of("reranking")), Optional.empty()));
    assertTrue(failure.getMessage().contains("supported chat, embedding, or Needle tool-calling"));
  }

  private static void assertContains(String source, String... fragments) {
    for (String fragment : fragments) {
      assertTrue(source.contains(fragment), () -> "missing generated source fragment: " + fragment);
    }
  }

  private static ModelJarDescriptor descriptor(Set<String> capabilities) {
    return descriptor(capabilities, "llama");
  }

  private static ModelJarDescriptor descriptor(Set<String> capabilities, String architecture) {
    return new ModelJarDescriptor(
        "example_q4_0",
        "hf://example/model",
        ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q4_0:1.0.0-q4_0.1"),
        ModelVersion.parse("1.0.0"),
        "q4_0",
        "gguf",
        architecture,
        "Q4_0",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/model")),
        Optional.of(URI.create("https://huggingface.co/example/model/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of("a".repeat(64)),
        Optional.of(1024L),
        Optional.of("Apache-2.0"),
        capabilities,
        Set.of("chat-template"),
        java.util.List.of(),
        Map.of("pure-java", true),
        Optional.of("Example model"),
        Optional.of("Example description"),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }
}
