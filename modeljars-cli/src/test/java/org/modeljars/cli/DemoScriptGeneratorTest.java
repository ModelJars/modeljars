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

  private final DemoScriptGenerator generator = new DemoScriptGenerator("0.1.39");

  @Test
  void generatesAChatDemoUsingThePublicRuntimeAndRuntimeOwnedMetrics() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(descriptor(Set.of("chat", "text-generation")), Optional.empty());

    assertEquals(DemoScriptGenerator.Type.CHAT, demo.type());
    assertEquals("example-chat-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "//DEPS org.modeljars:modeljars:0.1.39",
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
    assertContains(
        demo.source(),
        "//DEPS com.fasterxml.jackson.core:jackson-databind:"
            + System.getProperty("modeljars.test.jacksonVersion")
            + "\n");
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
  void generatesARerankingDemoThroughThePublicModelJarsApi() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(
            descriptor(Set.of("reranking", "text-ranking")),
            Optional.of("How many people live in Berlin?"));

    assertEquals(DemoScriptGenerator.Type.RERANKING, demo.type());
    assertEquals("example-reranking-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "ModelJars.openReranker(MODEL)",
        "How many people live in Berlin?",
        "model.rerank(query, documents)",
        "Score",
        "Load:",
        "Execution:");
  }

  @Test
  void generatesATextToSpeechDemoThatWritesWaveAudioAndReportsRealTimeFactor() {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(
            descriptor(Set.of("text-to-speech", "audio-generation", "streaming"), "soprano"),
            Optional.of("The JVM can speak for itself."));

    assertEquals(DemoScriptGenerator.Type.SPEECH, demo.type());
    assertEquals("example-speech-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "ModelJars.openSpeech(MODEL)",
        "The JVM can speak for itself.",
        "WavEncoder.pcm16(audio)",
        "speech.wav",
        "Audio:",
        "RTF:");
  }

  @Test
  void generatesAQualifiedHybridDemoThroughItsCompositionEntrypoint() {
    ModelJarDescriptor source = descriptor(Set.of("chat", "text-generation", "tool-calling"));
    ModelJarDescriptor hybrid =
        new ModelJarDescriptor(
            "qwen3_chat_tools_composite",
            "modeljars://qwen3-chat-tools",
            ModelJarCoordinate.parse("org.modeljars.composite:qwen3-chat-tools:0.1.38"),
            ModelVersion.parse("0.1.38"),
            "chat-tools",
            "composite",
            "hybrid",
            "MIXED",
            source.localPath(),
            source.classpathResource(),
            source.sourceUri(),
            Optional.empty(),
            source.revision(),
            source.sha256(),
            source.sizeBytes(),
            source.license(),
            source.capabilities(),
            Set.of("virtual-model"),
            source.files(),
            source.backendSupport(),
            Optional.of("Qwen3 chat + tools hybrid"),
            source.description(),
            source.licenseUri(),
            source.domains(),
            source.dimensions());

    DemoScriptGenerator.GeneratedDemo demo = generator.generate(hybrid, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.COMPOSITE, demo.type());
    assertContains(
        demo.source(),
        "//DEPS org.modeljars.composite:qwen3-chat-tools:0.1.38",
        "Qwen3ChatTools.open()",
        "hybrid.openSession()",
        "conversation.generate(");
  }

  @Test
  void generatesCompilableGraniteAnswerabilityRatherThanQwenChat(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var descriptor =
        org.modeljars.ModelJarRegistry.fromClasspath()
            .resolve(org.modeljars.ModelJar.of("modeljars://granite-answerability"))
            .orElseThrow();
    var demo = generator.generate(descriptor, Optional.empty());
    assertEquals(DemoScriptGenerator.Type.COMPOSITE, demo.type());
    assertContains(
        demo.source(),
        "GraniteAnswerability.open()",
        "GraniteAnswerability.classify(",
        "physicallySharesPrefix()",
        "In which German state is Aachen?");
    assertTrue(!demo.source().contains("Qwen3ChatTools"));
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), directory);
  }

  @Test
  void generatedToolHandlerRejectsOutOfRangeAndOverflowingBrightness(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var demo = generator.generate(descriptor(Set.of("tool-calling"), "needle2"), Optional.empty());
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), directory);
    try (var classes =
        new java.net.URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
      var home = classes.loadClass("ModelJarsDemo$SmartHome");
      var method =
          home.getDeclaredMethod(
              "requiredInt", com.fasterxml.jackson.databind.JsonNode.class, String.class);
      method.setAccessible(true);
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      assertEquals(0, method.invoke(null, json.readTree("{\"brightness\":0}"), "brightness"));
      assertEquals(100, method.invoke(null, json.readTree("{\"brightness\":100}"), "brightness"));
      for (String number : java.util.List.of("-1", "101", "4294967296")) {
        var arguments = json.readTree("{\"brightness\":" + number + "}");
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, arguments, "brightness"));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
      }
    }
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
