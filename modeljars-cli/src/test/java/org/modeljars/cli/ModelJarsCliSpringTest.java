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
import static org.modeljars.cli.SpringFixtures.assertContains;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualificationRegistry;

/** Command-level Spring AI and Spring Boot paths, exercised against the shipped catalog. */
class ModelJarsCliSpringTest {
  @TempDir Path temporaryDirectory;

  private static final ModelJarRegistry CATALOG = ModelJarRegistry.fromClasspath();

  @Test
  void printsBootDependenciesConfigurationAndBeanForAQualifiedChatModel() {
    ModelJarDescriptor chat = qualifiedChatModel();
    String template =
        SpringIntegration.ChatTemplates.fromClasspath().templateFor(chat).orElseThrow();

    Result result =
        run(cli(chat), "coordinates", chat.alias(), "--spring-boot", "--tool", "gradle-kotlin");

    assertEquals(0, result.status(), result.error());
    String modelsVersion = System.getProperty("modeljars.test.modelsVersion");
    assertContains(
        result.output(),
        "GRADLE-KOTLIN",
        "implementation(\"com.integrallis:models-spring-boot-starter:" + modelsVersion + "\")",
        "implementation(\"" + chat.markerCoordinate() + "\")",
        "APPLICATION.YAML",
        "chat-template: " + template,
        "TextGenerationModel localModel()");
    assertFalse(result.output().contains("MAVEN"));
  }

  @Test
  void printsPropertiesAndMachineReadableSpringCoordinates() {
    ModelJarDescriptor chat = qualifiedChatModel();

    Result properties =
        run(
            cli(chat),
            "coordinates",
            chat.alias(),
            "--spring-boot",
            "--config",
            "properties",
            "--tool",
            "maven");
    Result json = run(cli(chat), "coordinates", chat.alias(), "--spring-ai", "--output", "json");

    assertEquals(0, properties.status(), properties.error());
    assertContains(
        properties.output(), "APPLICATION.PROPERTIES", "integrallis.models.chat-template=");
    assertEquals(0, json.status(), json.error());
    assertContains(
        json.output(),
        "\"flavor\": \"spring-ai\"",
        "\"kind\": \"chat\"",
        "\"gradle-kotlin\"",
        "models-spring-ai:",
        "\"java\"");
  }

  @Test
  void printsSpringAiCoordinatesForAnEmbeddingModel() {
    ModelJarDescriptor embedding = model(ModelJarsCliSpringTest::isEmbedding);

    Result result = run(cli(embedding), "coordinates", embedding.alias(), "--spring-ai");

    assertEquals(0, result.status(), result.error());
    assertContains(
        result.output(),
        "org.springframework.ai:spring-ai-model",
        "ModelsSpringAiEmbeddingModel",
        "ModelJars.openEmbedding(\"" + embedding.markerCoordinate() + "\")");
    assertFalse(result.output().contains("APPLICATION.YAML"));
  }

  @Test
  void generatesSpringAiAndSpringBootDemosWhileKeepingThePlainDemoTheDefault() throws Exception {
    ModelJarDescriptor chat = qualifiedChatModel();
    Path plain = temporaryDirectory.resolve("plain.java");
    Path springAi = temporaryDirectory.resolve("spring-ai.java");
    Path boot = temporaryDirectory.resolve("boot.java");

    Result plainResult = run(cli(chat), "demo", chat.alias(), "--output-file", plain.toString());
    Result springAiResult =
        run(cli(chat), "demo", chat.alias(), "--spring-ai", "--output-file", springAi.toString());
    Result bootResult =
        run(cli(chat), "demo", chat.alias(), "--spring-boot", "--output-file", boot.toString());

    assertEquals(0, plainResult.status(), plainResult.error());
    assertFalse(Files.readString(plain).contains("springframework"));
    assertEquals(0, springAiResult.status(), springAiResult.error());
    assertContains(Files.readString(springAi), "ChatClient.create(model)");
    assertContains(springAiResult.output(), "Spring AI chat demo", "jbang ");
    assertEquals(0, bootResult.status(), bootResult.error());
    assertContains(Files.readString(boot), "@EnableAutoConfiguration", "modelsChatModel");
    assertContains(bootResult.output(), "Spring Boot chat demo");
  }

  @Test
  void rejectsUnsupportedSpringCombinationsWithActionableMessages() {
    ModelJarDescriptor speech = model(d -> d.capabilities().contains("text-to-speech"));
    ModelJarDescriptor chat = qualifiedChatModel();

    Result speechBoot = run(cli(speech), "coordinates", speech.alias(), "--spring-boot");
    Result speechDemo = run(cli(speech), "demo", speech.alias(), "--spring-ai");
    Result both = run(cli(chat), "coordinates", chat.alias(), "--spring-ai", "--spring-boot");
    Result markerOnly =
        run(cli(chat), "coordinates", chat.alias(), "--spring-boot", "--marker-only");
    Result configWithoutSpring =
        run(cli(chat), "coordinates", chat.alias(), "--config", "properties");

    assertEquals(2, speechBoot.status());
    assertContains(
        speechBoot.error(), speech.alias(), "chat, tool-calling, embedding, and reranking");
    assertEquals(2, speechDemo.status());
    assertContains(speechDemo.error(), "modeljars demo " + speech.alias());
    assertEquals(2, both.status());
    assertContains(both.error(), "--spring-ai", "--spring-boot");
    assertEquals(2, markerOnly.status());
    assertContains(markerOnly.error(), "--marker-only");
    assertEquals(2, configWithoutSpring.status());
    assertContains(configWithoutSpring.error(), "--config");
  }

  private static boolean isEmbedding(ModelJarDescriptor descriptor) {
    return descriptor.capabilities().contains("text-embedding")
        || descriptor.capabilities().contains("embeddings");
  }

  private static ModelJarDescriptor qualifiedChatModel() {
    ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
    return model(
        descriptor ->
            !descriptor.format().equals("composite")
                && !descriptor.capabilities().contains("tool-calling")
                && rag.qualificationsFor(descriptor).stream().anyMatch(q -> q.productionUsable()));
  }

  private static ModelJarDescriptor model(Predicate<ModelJarDescriptor> predicate) {
    return CATALOG.descriptors().stream()
        .filter(predicate)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the shipped catalog has no matching model"));
  }

  private static ModelJarsCli cli(ModelJarDescriptor... descriptors) {
    return new ModelJarsCli(
        ModelJarRegistry.of(List.of(descriptors)),
        (selected, destination, progress) -> destination);
  }

  private static Result run(ModelJarsCli cli, String... arguments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int status =
        cli.run(
            arguments,
            new PrintStream(output, true, StandardCharsets.UTF_8),
            new PrintStream(error, true, StandardCharsets.UTF_8));
    return new Result(
        status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
  }

  private record Result(int status, String output, String error) {}
}
