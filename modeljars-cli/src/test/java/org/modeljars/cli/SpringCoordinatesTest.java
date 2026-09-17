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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.modeljars.cli.SpringFixtures.TEMPLATES;
import static org.modeljars.cli.SpringFixtures.VERSIONS;
import static org.modeljars.cli.SpringFixtures.assertContains;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.cli.DependencyCoordinates.Tool;
import org.modeljars.cli.SpringCoordinates.ConfigFormat;
import org.modeljars.cli.SpringIntegration.Flavor;
import org.modeljars.cli.SpringIntegration.Kind;

class SpringCoordinatesTest {
  @TempDir Path temporaryDirectory;

  private static SpringCoordinates.Setup setup(
      ModelJarDescriptor descriptor, Flavor flavor, ConfigFormat format) {
    return SpringCoordinates.setup(
        descriptor,
        flavor,
        VERSIONS,
        TEMPLATES,
        List.of(Tool.MAVEN, Tool.GRADLE, Tool.GRADLE_KOTLIN, Tool.JBANG),
        format);
  }

  @Test
  void bootChatListsTheBomsStarterRuntimeAndMarkerForEveryBuildTool() {
    ModelJarDescriptor model = SpringFixtures.chat();
    SpringCoordinates.Setup setup = setup(model, Flavor.SPRING_BOOT, ConfigFormat.YAML);

    assertEquals(Kind.CHAT, setup.kind());
    assertContains(
        setup.declarations().get(Tool.GRADLE_KOTLIN),
        "implementation(platform(\"org.springframework.boot:spring-boot-dependencies:4.1.0\"))",
        "implementation(platform(\"org.springframework.ai:spring-ai-bom:2.0.0\"))",
        "implementation(\"org.springframework.boot:spring-boot-starter\")",
        "implementation(\"org.springframework.ai:spring-ai-client-chat\")",
        "implementation(\"com.integrallis:models-spring-boot-starter:0.3.40\")",
        "implementation(\"org.modeljars:modeljars:0.1.42\")",
        "implementation(\"" + model.markerCoordinate() + "\")");
    assertContains(
        setup.declarations().get(Tool.GRADLE),
        "implementation platform('org.springframework.ai:spring-ai-bom:2.0.0')",
        "implementation 'com.integrallis:models-spring-boot-starter:0.3.40'",
        "implementation '" + model.markerCoordinate() + "'");
    assertContains(
        setup.declarations().get(Tool.MAVEN),
        "<dependencyManagement>",
        "<artifactId>spring-boot-dependencies</artifactId>",
        "<artifactId>spring-ai-bom</artifactId>",
        "<scope>import</scope>",
        "<type>pom</type>",
        "<artifactId>models-spring-boot-starter</artifactId>\n    <version>0.3.40</version>",
        "<artifactId>" + model.markerCoordinate().artifactId() + "</artifactId>");
    assertContains(
        setup.declarations().get(Tool.JBANG),
        "//DEPS org.springframework.boot:spring-boot-dependencies:4.1.0@pom",
        "//DEPS org.springframework.ai:spring-ai-bom:2.0.0@pom",
        "//DEPS com.integrallis:models-spring-boot-starter:0.3.40");
    assertFalse(setup.declarations().get(Tool.GRADLE_KOTLIN).contains("models-spring-ai:"));
  }

  @Test
  void bootChatConfigurationSelectsTheQualifiedTemplateInsteadOfTheStarterRawDefault() {
    SpringCoordinates.Setup yaml =
        setup(SpringFixtures.chat(), Flavor.SPRING_BOOT, ConfigFormat.YAML);
    SpringCoordinates.Setup properties =
        setup(SpringFixtures.chat(), Flavor.SPRING_BOOT, ConfigFormat.PROPERTIES);

    assertEquals("application.yaml", yaml.configurationFileName().orElseThrow());
    assertEquals(
        """
        integrallis:
          models:
            chat-template: chatml
            sampling:
              temperature: 0.0
              max-tokens: 256
        """,
        yaml.configuration().orElseThrow());
    assertEquals("application.properties", properties.configurationFileName().orElseThrow());
    assertEquals(
        """
        integrallis.models.chat-template=chatml
        integrallis.models.sampling.temperature=0.0
        integrallis.models.sampling.max-tokens=256
        """,
        properties.configuration().orElseThrow());
    assertContains(
        yaml.java(),
        "@Bean(destroyMethod = \"close\")",
        "TextGenerationModel localModel()",
        "ModelJars.open(\"" + SpringFixtures.chat().markerCoordinate() + "\")");
  }

  @Test
  void bootToolChatUsesTheToolQualifiedTemplate() {
    SpringCoordinates.Setup setup =
        setup(SpringFixtures.toolChat(), Flavor.SPRING_BOOT, ConfigFormat.YAML);

    assertEquals(Kind.TOOL_CHAT, setup.kind());
    assertContains(setup.configuration().orElseThrow(), "chat-template: chatml-no-think");
  }

  @Test
  void bootEmbeddingDeclaresTheAdapterBeanAndHasNoInventedProperties() {
    ModelJarDescriptor model = SpringFixtures.embedding();
    SpringCoordinates.Setup setup = setup(model, Flavor.SPRING_BOOT, ConfigFormat.YAML);

    assertEquals(Kind.EMBEDDING, setup.kind());
    assertTrue(setup.configuration().isEmpty());
    assertContains(
        setup.declarations().get(Tool.GRADLE_KOTLIN),
        "implementation(\"org.springframework.ai:spring-ai-model\")",
        "implementation(\"com.integrallis:models-spring-boot-starter:0.3.40\")");
    assertContains(
        setup.java(),
        "ModelsSpringAiEmbeddingModel",
        "ModelJars.openEmbedding(\"" + model.markerCoordinate() + "\")",
        "\"" + model.alias() + "\"");
    assertContains(String.join("\n", setup.notes()), "auto-configures chat");
  }

  @Test
  void springAiRerankingUsesTheAdapterWithoutBootOrTheStarter() {
    ModelJarDescriptor model = SpringFixtures.reranking();
    SpringCoordinates.Setup setup = setup(model, Flavor.SPRING_AI, ConfigFormat.YAML);

    assertEquals(Kind.RERANKING, setup.kind());
    assertTrue(setup.configuration().isEmpty());
    String kotlin = setup.declarations().get(Tool.GRADLE_KOTLIN);
    assertContains(
        kotlin,
        "implementation(platform(\"org.springframework.ai:spring-ai-bom:2.0.0\"))",
        "implementation(\"org.springframework.ai:spring-ai-rag\")",
        "implementation(\"com.integrallis:models-spring-ai:0.3.40\")");
    assertFalse(kotlin.contains("spring-boot"));
    assertContains(setup.java(), "new ModelsSpringAiDocumentReranker(", "ModelJars.openReranker(");
  }

  @Test
  void springAiChatSnippetUsesTheRuntimeQualifiedTemplateAndCapabilities() {
    SpringCoordinates.Setup setup =
        setup(SpringFixtures.toolChat(), Flavor.SPRING_AI, ConfigFormat.YAML);

    assertContains(
        setup.java(),
        "ModelJars.openRuntime(\"" + SpringFixtures.toolChat().markerCoordinate() + "\")",
        "runtime.chatTemplate()",
        "runtime.descriptor().capabilities()",
        "ChatClient.create(model)");
  }

  @Test
  void toolsWithoutBomImportsReceiveExplicitVersions() {
    SpringCoordinates.Setup setup =
        SpringCoordinates.setup(
            SpringFixtures.chat(),
            Flavor.SPRING_BOOT,
            VERSIONS,
            TEMPLATES,
            List.of(Tool.SBT),
            ConfigFormat.YAML);

    assertContains(
        setup.declarations().get(Tool.SBT),
        "\"org.springframework.boot\" % \"spring-boot-starter\" % \"4.1.0\"",
        "\"org.springframework.ai\" % \"spring-ai-client-chat\" % \"2.0.0\"",
        "\"com.integrallis\" % \"models-spring-boot-starter\" % \"0.3.40\"");
  }

  @Test
  void omitsTheRuntimeFromADevelopmentBuildAndSaysSo() {
    SpringCoordinates.Setup setup =
        SpringCoordinates.setup(
            SpringFixtures.chat(),
            Flavor.SPRING_AI,
            new SpringIntegration.Versions("development", "0.3.40", "2.0.0", "4.1.0"),
            TEMPLATES,
            List.of(Tool.GRADLE_KOTLIN),
            ConfigFormat.YAML);

    assertFalse(setup.declarations().get(Tool.GRADLE_KOTLIN).contains("org.modeljars:modeljars:"));
    assertContains(String.join("\n", setup.notes()), "Development build");
  }

  @Test
  void rejectsUnsupportedCapabilities() {
    assertThrows(
        IllegalArgumentException.class,
        () -> setup(SpringFixtures.speech(), Flavor.SPRING_BOOT, ConfigFormat.YAML));
    assertThrows(
        IllegalArgumentException.class,
        () -> setup(SpringFixtures.needle(), Flavor.SPRING_BOOT, ConfigFormat.YAML));
  }

  @Test
  void everyPrintedJavaSnippetCompilesAgainstTheRealAdapters() throws Exception {
    List<ModelJarDescriptor> models =
        List.of(
            SpringFixtures.chat(),
            SpringFixtures.toolChat(),
            SpringFixtures.needle(),
            SpringFixtures.embedding(),
            SpringFixtures.reranking());
    int index = 0;
    for (Flavor flavor : Flavor.values()) {
      for (ModelJarDescriptor model : models) {
        if (flavor == Flavor.SPRING_BOOT && model.architecture().equals("needle2")) {
          continue;
        }
        SpringCoordinates.Setup setup = setup(model, flavor, ConfigFormat.YAML);
        String source = SpringCoordinates.compilationUnit(setup);
        SpringFixtures.assertCompiles(
            "Snippet" + index + ".java", source, temporaryDirectory.resolve("s" + index++));
      }
    }
  }
}
