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

  @Test
  void chatDemoStreamsOnlyTheAnswerAcrossEveryReasoningDelimiterBoundary(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var demo = generator.generate(descriptor(Set.of("chat"), "flint"), Optional.empty());
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), directory);
    try (var classes =
        new java.net.URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
      var type = classes.loadClass("ModelJarsDemo$AnswerStream");
      var constructor = type.getDeclaredConstructor(java.util.function.Consumer.class);
      constructor.setAccessible(true);
      var cases =
          Map.of(
              "<think>Internal analysis.</think>Paris", "Paris",
              "  <think>Outer <think>nested</think> reasoning.</think>\nParis", "Paris",
              "<think>one</think><think>two</think>Paris", "Paris",
              "<think>unfinished reasoning", "",
              "<THINK>analysis</THINK>Paris", "Paris",
              "Paris", "Paris",
              "The literal tag <think> is text here.", "The literal tag <think> is text here.",
              "<think>分析</think>東京 🗼", "東京 🗼");
      for (var entry : cases.entrySet()) {
        for (int split = 0; split <= entry.getKey().length(); split++) {
          var answer = new StringBuilder();
          var stream =
              (com.integrallis.models.api.TokenStream)
                  constructor.newInstance((java.util.function.Consumer<String>) answer::append);
          stream.onToken(entry.getKey().substring(0, split));
          stream.onToken(entry.getKey().substring(split));
          stream.onComplete(
              new com.integrallis.models.api.GenerationUsage(16, 45),
              com.integrallis.models.api.StopReason.EOS);
          assertEquals(entry.getValue(), answer.toString(), "split " + split);
        }
      }
      var answer = new StringBuilder();
      var stream =
          (com.integrallis.models.api.TokenStream)
              constructor.newInstance((java.util.function.Consumer<String>) answer::append);
      for (char c : "<think>hidden</think>Paris".toCharArray()) stream.onToken(String.valueOf(c));
      assertEquals("Paris", answer.toString(), "visible answer must stream before completion");
      var explicit = type.getDeclaredConstructor(java.util.function.Consumer.class, boolean.class);
      explicit.setAccessible(true);
      var raw = new StringBuilder();
      var rawStream =
          (com.integrallis.models.api.TokenStream)
              explicit.newInstance((java.util.function.Consumer<String>) raw::append, true);
      rawStream.onToken("<think>analysis</think>Paris");
      rawStream.onComplete();
      assertEquals(
          "<think>analysis</think>Paris", raw.toString(), "reasoning requires explicit opt-in");
      assertContains(
          demo.source(),
          "First visible answer",
          "Generated-token metrics include reasoning.",
          "No final answer was produced",
          "Answer may be incomplete",
          "modeljars.demo.diagnostics");
    }
  }

  @Test
  void everyQualifiedCatalogChatDemoHidesReasoningWithItsRealPrompt(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var registry = org.modeljars.ModelJarRegistry.fromClasspath();
    var qualifications =
        org.modeljars.ModelRagQualificationRegistry.load(
                java.nio.file.Path.of(
                    "../modeljars-catalog/build/generated/catalog-resources/main/META-INF/modeljars/qualifications-v1.properties"))
            .qualifications();
    assertTrue(qualifications.size() >= 79, "catalog coverage must not shrink silently");
    for (var qualification : qualifications) {
      var descriptor =
          registry.descriptors().stream()
              .filter(d -> d.alias().equals(qualification.modelId()))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("Missing descriptor: " + qualification.modelId()));
      var demo = generator.generate(descriptor, Optional.empty());
      assertEquals(DemoScriptGenerator.Type.CHAT, demo.type(), descriptor.alias());
      var dir = directory.resolve(descriptor.alias());
      SpringFixtures.assertCompiles(demo.fileName(), demo.source(), dir);
      try (var classes =
          new java.net.URLClassLoader(
              new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
        var ctor =
            classes
                .loadClass("ModelJarsDemo$AnswerStream")
                .getDeclaredConstructor(
                    java.util.function.Consumer.class, boolean.class, String.class);
        ctor.setAccessible(true);
        var prompt =
            org.modeljars.QualifiedChatTemplates.resolve(qualification.promptTemplate())
                .render(
                    java.util.List.of(
                        com.integrallis.models.runtime.chat.ChatMessage.user("Hello")))
                .text();
        for (String tag : java.util.List.of("think", "thinking", "reasoning")) {
          String raw = "<" + tag + ">private reasoning</" + tag + ">Paris";
          if (prompt.stripTrailing().endsWith("<|channel>thought\n<channel|>"))
            raw = "private reasoning<channel|>Paris";
          else if (prompt.stripTrailing().endsWith("<think>"))
            raw = "private reasoning</think>Paris";
          var answer = new StringBuilder();
          var stream =
              (com.integrallis.models.api.TokenStream)
                  ctor.newInstance(
                      (java.util.function.Consumer<String>) answer::append, false, prompt);
          for (char c : raw.toCharArray()) stream.onToken(String.valueOf(c));
          stream.onComplete();
          assertEquals("Paris", answer.toString(), descriptor.alias() + " / " + tag);
          if (prompt.contains("<|channel>thought")) {
            var direct = new StringBuilder();
            var directStream =
                (com.integrallis.models.api.TokenStream)
                    ctor.newInstance(
                        (java.util.function.Consumer<String>) direct::append, false, prompt);
            directStream.onToken("Paris");
            assertEquals("", direct.toString(), "ambiguous channel output waits for completion");
            directStream.onComplete(
                new com.integrallis.models.api.GenerationUsage(16, 1),
                com.integrallis.models.api.StopReason.EOS);
            assertEquals("Paris", direct.toString(), "direct Gemma answer must survive filtering");
          }
        }
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
