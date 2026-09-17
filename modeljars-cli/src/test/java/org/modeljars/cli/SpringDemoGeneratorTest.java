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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.modeljars.cli.SpringFixtures.TEMPLATES;
import static org.modeljars.cli.SpringFixtures.VERSIONS;
import static org.modeljars.cli.SpringFixtures.assertContains;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.cli.SpringIntegration.Flavor;

class SpringDemoGeneratorTest {
  @TempDir Path temporaryDirectory;

  private final SpringDemoGenerator generator = new SpringDemoGenerator(VERSIONS, TEMPLATES);

  @Test
  void springAiChatDemoRunsChatClientOverTheQualifiedRuntime() throws Exception {
    ModelJarDescriptor model = SpringFixtures.chat();
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(model, Flavor.SPRING_AI, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.CHAT, demo.type());
    assertEquals("example-spring-ai-chat-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "//JAVA 25+",
        "//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector",
        "//DEPS org.springframework.ai:spring-ai-bom:2.0.0@pom",
        "//DEPS org.springframework.ai:spring-ai-client-chat",
        "//DEPS com.integrallis:models-spring-ai:0.3.40",
        "//DEPS org.modeljars:modeljars:0.1.42",
        "//DEPS " + model.markerCoordinate(),
        "ModelJars.openRuntime(MODEL)",
        "new ModelsSpringAiChatModel(",
        "runtime.chatTemplate()",
        "runtime.descriptor().capabilities()",
        "ChatClient.create(model)",
        "What is the capital of France?");
    assertFalse(demo.source().contains("spring-boot"));
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springAiToolChatDemoRegistersATypedWeatherCallback() throws Exception {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(SpringFixtures.toolChat(), Flavor.SPRING_AI, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.TOOLS, demo.type());
    assertEquals("example-tools-spring-ai-tools-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "@Tool(name = \"get-weather-for-zipcode\"",
        "@ToolParam(description = \"The zipcode to get weather for\")",
        "record Weather(",
        ".tools(weather)",
        "What is the weather for 88252?",
        "weather.invocations()");
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springAiNeedleDemoRendersTypedSmartHomeResults() throws Exception {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(SpringFixtures.needle(), Flavor.SPRING_AI, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.TOOLS, demo.type());
    assertContains(
        demo.source(),
        "@Tool(name = \"set_lights\"",
        "@Tool(name = \"lock_door\"",
        ".withToolResultRenderer(\"set_lights\", LightState.class,",
        ".withToolResultRenderer(\"lock_door\", DoorState.class,",
        "Dim the bedroom lights to 20 percent.");
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springAiEmbeddingDemoUsesTheSpringAiEmbeddingModel() throws Exception {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(
            SpringFixtures.embedding(), Flavor.SPRING_AI, Optional.of("Trains \"and\" buses"));

    assertEquals(DemoScriptGenerator.Type.EMBEDDING, demo.type());
    assertContains(
        demo.source(),
        "//DEPS org.springframework.ai:spring-ai-model",
        "try (var adapter = new ModelsSpringAiEmbeddingModel(",
        "EmbeddingModel model = adapter;",
        "ModelJars.openEmbedding(MODEL)",
        "model.embed(input)",
        "model.dimensions()",
        "Trains \\\"and\\\" buses");
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springAiRerankingDemoUsesTheDocumentPostProcessor() throws Exception {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(SpringFixtures.reranking(), Flavor.SPRING_AI, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.RERANKING, demo.type());
    assertContains(
        demo.source(),
        "//DEPS org.springframework.ai:spring-ai-rag",
        "new ModelsSpringAiDocumentReranker(",
        "ModelJars.openReranker(MODEL)",
        "new Query(query)",
        "Document.builder()",
        "document.getScore()");
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springBootChatDemoIsAutoConfiguredFromTheStarterAndQualifiedTemplate() throws Exception {
    ModelJarDescriptor model = SpringFixtures.chat();
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(model, Flavor.SPRING_BOOT, Optional.empty());

    assertEquals(DemoScriptGenerator.Type.CHAT, demo.type());
    assertEquals("example-spring-boot-chat-demo.java", demo.fileName());
    assertContains(
        demo.source(),
        "//DEPS org.springframework.boot:spring-boot-dependencies:4.1.0@pom",
        "//DEPS org.springframework.ai:spring-ai-bom:2.0.0@pom",
        "//DEPS org.springframework.boot:spring-boot-starter",
        "//DEPS com.integrallis:models-spring-boot-starter:0.3.40",
        "@SpringBootConfiguration(proxyBeanMethods = false)",
        "@EnableAutoConfiguration",
        "TextGenerationModel localModel()",
        "ModelJars.open(MODEL)",
        "@Qualifier(\"modelsChatModel\") ChatModel chatModel",
        "CommandLineRunner",
        "\"integrallis.models.chat-template\", \"chatml\"",
        "\"spring.main.web-application-type\", \"none\"");
    assertFalse(demo.source().contains("new ModelsSpringAiChatModel("));
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void springBootEmbeddingAndRerankingDemosDeclareAdapterBeans() throws Exception {
    DemoScriptGenerator.GeneratedDemo embedding =
        generator.generate(SpringFixtures.embedding(), Flavor.SPRING_BOOT, Optional.empty());
    DemoScriptGenerator.GeneratedDemo reranking =
        generator.generate(SpringFixtures.reranking(), Flavor.SPRING_BOOT, Optional.empty());

    assertContains(
        embedding.source(), "ModelsSpringAiEmbeddingModel embeddingModel(", "EmbeddingModel model");
    assertContains(
        reranking.source(),
        "ModelsSpringAiDocumentReranker documentReranker()",
        "//DEPS org.springframework.ai:spring-ai-rag");
    SpringFixtures.assertCompiles(
        embedding.fileName(), embedding.source(), temporaryDirectory.resolve("e"));
    SpringFixtures.assertCompiles(
        reranking.fileName(), reranking.source(), temporaryDirectory.resolve("r"));
  }

  @Test
  void springBootToolChatDemoCompiles() throws Exception {
    DemoScriptGenerator.GeneratedDemo demo =
        generator.generate(SpringFixtures.toolChat(), Flavor.SPRING_BOOT, Optional.empty());

    assertContains(demo.source(), "\"integrallis.models.chat-template\", \"chatml-no-think\"");
    SpringFixtures.assertCompiles(demo.fileName(), demo.source(), temporaryDirectory);
  }

  @Test
  void rejectsUnsupportedModelsForBothFlavors() {
    for (Flavor flavor : Flavor.values()) {
      for (ModelJarDescriptor model :
          List.of(SpringFixtures.speech(), SpringFixtures.composite())) {
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.generate(model, flavor, Optional.empty()));
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate(SpringFixtures.needle(), Flavor.SPRING_BOOT, Optional.empty()));
  }
}
