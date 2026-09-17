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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.cli.DependencyCoordinates.Tool;
import org.modeljars.cli.SpringIntegration.Flavor;
import org.modeljars.cli.SpringIntegration.Kind;

/** Copy-ready Spring AI and Spring Boot setup for one catalog model. */
final class SpringCoordinates {
  static final String SPRING_BOOT_BOM_GROUP = "org.springframework.boot";
  static final String SPRING_BOOT_BOM = "spring-boot-dependencies";
  static final String SPRING_AI_BOM_GROUP = "org.springframework.ai";
  static final String SPRING_AI_BOM = "spring-ai-bom";
  static final String TEMPERATURE = "0.0";
  static final int MAX_TOKENS = 256;

  enum ConfigFormat {
    YAML("application.yaml"),
    PROPERTIES("application.properties");

    private final String fileName;

    ConfigFormat(String fileName) {
      this.fileName = fileName;
    }

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** One dependency; {@code managed} versions are omitted where a BOM supplies them. */
  record Dependency(ModelJarCoordinate coordinate, boolean managed) {
    Dependency(String groupId, String artifactId, String version, boolean managed) {
      this(new ModelJarCoordinate(groupId, artifactId, version, Optional.empty(), "jar"), managed);
    }
  }

  record Setup(
      Kind kind,
      Flavor flavor,
      Map<Tool, String> declarations,
      Optional<String> configurationFileName,
      Optional<String> configuration,
      List<String> javaImports,
      String javaMembers,
      List<String> notes) {
    Setup {
      declarations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
      javaImports = List.copyOf(javaImports);
      notes = List.copyOf(notes);
    }

    /** Imports followed by the bean or method declarations to paste into an application class. */
    String java() {
      return javaImports.stream().map(value -> "import " + value + ";").collect(joining())
          + "\n"
          + javaMembers;
    }
  }

  private SpringCoordinates() {}

  static Setup setup(
      ModelJarDescriptor descriptor,
      Flavor flavor,
      SpringIntegration.Versions versions,
      SpringIntegration.ChatTemplates templates,
      List<Tool> tools,
      ConfigFormat format) {
    Objects.requireNonNull(format, "format");
    Kind kind = SpringIntegration.kind(descriptor, flavor);
    List<Dependency> dependencies = dependencies(descriptor, kind, flavor, versions);
    Map<Tool, String> declarations = new LinkedHashMap<>();
    for (Tool tool : tools) {
      declarations.put(tool, render(dependencies, flavor, versions, tool));
    }

    Optional<String> configuration = Optional.empty();
    Optional<String> fileName = Optional.empty();
    List<String> notes = new ArrayList<>();
    boolean chat = kind == Kind.CHAT || kind == Kind.TOOL_CHAT;
    if (flavor == Flavor.SPRING_BOOT && chat) {
      String template = SpringIntegration.requireChatTemplate(descriptor, templates);
      configuration = Optional.of(configuration(template, format));
      fileName = Optional.of(format.fileName);
      notes.add(
          "The Models starter adapts the TextGenerationModel bean into the Spring AI ChatModel"
              + " bean 'modelsChatModel' using the qualified chat template '"
              + template
              + "'.");
    } else if (flavor == Flavor.SPRING_BOOT) {
      notes.add(
          "The Models starter auto-configures chat only; declare the "
              + (kind == Kind.EMBEDDING ? "EmbeddingModel" : "DocumentPostProcessor")
              + " bean below.");
    }
    if (kind == Kind.TOOL_CHAT) {
      notes.add(
          "Register Java @Tool callbacks with ChatClient; the model is qualified for tool calling.");
    }
    notes.add(
        flavor == Flavor.SPRING_BOOT
            ? "Run on Java 25+ with --add-modules=jdk.incubator.vector; for bootRun or"
                + " spring-boot:run also disable Spring Boot's optimized launch (C1-only)."
            : "Run on Java 25+ with --add-modules=jdk.incubator.vector.");
    if (!versions.releasedRuntime()) {
      notes.add(
          "Development build: the org.modeljars:modeljars runtime dependency is omitted because no"
              + " release version is embedded.");
    }
    return new Setup(
        kind,
        flavor,
        declarations,
        fileName,
        configuration,
        imports(kind, flavor),
        members(descriptor, kind, flavor),
        notes);
  }

  /** Wraps a setup's Java snippet into a compilable unit; used to keep printed code honest. */
  static String compilationUnit(Setup setup) {
    return setup.javaImports().stream().map(value -> "import " + value + ";").collect(joining())
        + "\nclass ModelJarsSpringSnippet {\n"
        + setup.javaMembers()
        + "}\n";
  }

  static List<Dependency> dependencies(
      ModelJarDescriptor descriptor,
      Kind kind,
      Flavor flavor,
      SpringIntegration.Versions versions) {
    List<Dependency> dependencies = new ArrayList<>();
    if (flavor == Flavor.SPRING_BOOT) {
      dependencies.add(
          new Dependency(
              "org.springframework.boot", "spring-boot-starter", versions.springBoot(), true));
    }
    dependencies.add(
        new Dependency("org.springframework.ai", springAiModule(kind), versions.springAi(), true));
    dependencies.add(
        new Dependency(
            "com.integrallis",
            flavor == Flavor.SPRING_BOOT ? "models-spring-boot-starter" : "models-spring-ai",
            versions.models(),
            false));
    if (versions.releasedRuntime()) {
      dependencies.add(new Dependency("org.modeljars", "modeljars", versions.modeljars(), false));
    }
    dependencies.add(new Dependency(descriptor.markerCoordinate(), false));
    return dependencies;
  }

  static String springAiModule(Kind kind) {
    return switch (kind) {
      case CHAT, TOOL_CHAT, ACTION_TOOLS -> "spring-ai-client-chat";
      case EMBEDDING -> "spring-ai-model";
      case RERANKING -> "spring-ai-rag";
    };
  }

  static List<ModelJarCoordinate> boms(Flavor flavor, SpringIntegration.Versions versions) {
    List<ModelJarCoordinate> boms = new ArrayList<>();
    if (flavor == Flavor.SPRING_BOOT) {
      boms.add(
          new ModelJarCoordinate(
              SPRING_BOOT_BOM_GROUP,
              SPRING_BOOT_BOM,
              versions.springBoot(),
              Optional.empty(),
              "pom"));
    }
    boms.add(
        new ModelJarCoordinate(
            SPRING_AI_BOM_GROUP, SPRING_AI_BOM, versions.springAi(), Optional.empty(), "pom"));
    return boms;
  }

  private static String render(
      List<Dependency> dependencies,
      Flavor flavor,
      SpringIntegration.Versions versions,
      Tool tool) {
    List<ModelJarCoordinate> boms = boms(flavor, versions);
    return switch (tool) {
      case MAVEN -> maven(dependencies, boms);
      case GRADLE ->
          lines(
              boms.stream().map(bom -> "implementation platform('" + notation(bom) + "')"),
              dependencies.stream()
                  .map(
                      d ->
                          d.managed()
                              ? "implementation '" + managedNotation(d) + "'"
                              : DependencyCoordinates.render(d.coordinate(), tool)));
      case GRADLE_KOTLIN ->
          lines(
              boms.stream().map(bom -> "implementation(platform(\"" + notation(bom) + "\"))"),
              dependencies.stream()
                  .map(
                      d ->
                          d.managed()
                              ? "implementation(\"" + managedNotation(d) + "\")"
                              : DependencyCoordinates.render(d.coordinate(), tool)));
      case JBANG ->
          lines(
              boms.stream().map(bom -> "//DEPS " + notation(bom) + "@pom"),
              dependencies.stream()
                  .map(
                      d ->
                          d.managed()
                              ? "//DEPS " + managedNotation(d)
                              : DependencyCoordinates.render(d.coordinate(), tool)));
      case SBT, IVY, LEININGEN ->
          dependencies.stream()
              .map(d -> DependencyCoordinates.render(d.coordinate(), tool))
              .collect(Collectors.joining("\n"));
    };
  }

  private static String maven(List<Dependency> dependencies, List<ModelJarCoordinate> boms) {
    StringBuilder result = new StringBuilder("<dependencyManagement>\n  <dependencies>\n");
    for (ModelJarCoordinate bom : boms) {
      result
          .append("    <dependency>\n")
          .append("      <groupId>")
          .append(bom.groupId())
          .append("</groupId>\n")
          .append("      <artifactId>")
          .append(bom.artifactId())
          .append("</artifactId>\n")
          .append("      <version>")
          .append(bom.version())
          .append("</version>\n")
          .append("      <type>pom</type>\n")
          .append("      <scope>import</scope>\n")
          .append("    </dependency>\n");
    }
    result.append("  </dependencies>\n</dependencyManagement>\n<dependencies>\n");
    for (Dependency dependency : dependencies) {
      if (dependency.managed()) {
        result
            .append("  <dependency>\n")
            .append("    <groupId>")
            .append(dependency.coordinate().groupId())
            .append("</groupId>\n")
            .append("    <artifactId>")
            .append(dependency.coordinate().artifactId())
            .append("</artifactId>\n")
            .append("  </dependency>\n");
      } else {
        result
            .append(indent(DependencyCoordinates.render(dependency.coordinate(), Tool.MAVEN), "  "))
            .append('\n');
      }
    }
    return result.append("</dependencies>").toString();
  }

  private static String configuration(String template, ConfigFormat format) {
    return switch (format) {
      case YAML ->
          """
          integrallis:
            models:
              chat-template: %s
              sampling:
                temperature: %s
                max-tokens: %d
          """
              .formatted(template, TEMPERATURE, MAX_TOKENS);
      case PROPERTIES ->
          """
          integrallis.models.chat-template=%s
          integrallis.models.sampling.temperature=%s
          integrallis.models.sampling.max-tokens=%d
          """
              .formatted(template, TEMPERATURE, MAX_TOKENS);
    };
  }

  private static List<String> imports(Kind kind, Flavor flavor) {
    List<String> imports = new ArrayList<>();
    if (flavor == Flavor.SPRING_BOOT) {
      switch (kind) {
        case CHAT, TOOL_CHAT -> imports.add("com.integrallis.models.api.TextGenerationModel");
        case EMBEDDING -> {
          imports.add("com.integrallis.models.spring.ai.ModelsSpringAiEmbeddingModel");
          imports.add("io.micrometer.observation.ObservationRegistry");
          imports.add("org.springframework.beans.factory.ObjectProvider");
        }
        case RERANKING ->
            imports.add("com.integrallis.models.spring.ai.ModelsSpringAiDocumentReranker");
        case ACTION_TOOLS -> throw new IllegalStateException("unsupported under Spring Boot");
      }
      imports.add("org.modeljars.ModelJars");
      imports.add("org.springframework.context.annotation.Bean");
      return imports;
    }
    switch (kind) {
      case CHAT, TOOL_CHAT, ACTION_TOOLS -> {
        imports.add("com.integrallis.models.api.SamplingOptions");
        imports.add("com.integrallis.models.spring.ai.ModelsSpringAiChatModel");
        imports.add("org.modeljars.ModelJars");
        imports.add("org.springframework.ai.chat.client.ChatClient");
        if (kind != Kind.CHAT) {
          imports.add("org.springframework.ai.tool.annotation.Tool");
          imports.add("org.springframework.ai.tool.annotation.ToolParam");
        }
      }
      case EMBEDDING -> {
        imports.add("com.integrallis.models.spring.ai.ModelsSpringAiEmbeddingModel");
        imports.add("io.micrometer.observation.ObservationRegistry");
        imports.add("org.modeljars.ModelJars");
      }
      case RERANKING -> {
        imports.add("com.integrallis.models.spring.ai.ModelsSpringAiDocumentReranker");
        imports.add("java.util.List");
        imports.add("org.modeljars.ModelJars");
        imports.add("org.springframework.ai.document.Document");
        imports.add("org.springframework.ai.rag.Query");
      }
    }
    return imports;
  }

  private static String members(ModelJarDescriptor descriptor, Kind kind, Flavor flavor) {
    String coordinate = descriptor.markerCoordinate().toString();
    if (flavor == Flavor.SPRING_BOOT) {
      return switch (kind) {
        case CHAT, TOOL_CHAT ->
            """
            @Bean(destroyMethod = "close")
            TextGenerationModel localModel() {
                return ModelJars.open("%s");
            }
            """
                .formatted(coordinate);
        case EMBEDDING ->
            """
            @Bean(destroyMethod = "close")
            ModelsSpringAiEmbeddingModel embeddingModel(
                    ObjectProvider<ObservationRegistry> observations) {
                return new ModelsSpringAiEmbeddingModel(
                        ModelJars.openEmbedding("%s"),
                        "%s",
                        observations.getIfAvailable(() -> ObservationRegistry.NOOP));
            }
            """
                .formatted(coordinate, descriptor.alias());
        case RERANKING ->
            """
            @Bean(destroyMethod = "close")
            ModelsSpringAiDocumentReranker documentReranker() {
                return new ModelsSpringAiDocumentReranker(ModelJars.openReranker("%s"));
            }
            """
                .formatted(coordinate);
        case ACTION_TOOLS -> throw new IllegalStateException("unsupported under Spring Boot");
      };
    }
    return switch (kind) {
      case CHAT ->
          """
          String ask(String question) {
              var defaults = SamplingOptions.builder().temperature(0).maxTokens(%d).build();
              try (var runtime = ModelJars.openRuntime("%s")) {
                  var model = new ModelsSpringAiChatModel(
                          runtime.model(),
                          runtime.descriptor().alias(),
                          runtime.chatTemplate(),
                          defaults,
                          runtime.descriptor().capabilities());
                  return ChatClient.create(model).prompt().user(question).call().content();
              }
          }
          """
              .formatted(MAX_TOKENS, coordinate);
      case TOOL_CHAT ->
          WEATHER_TOOLS
              + """

              String ask(String question) {
                  var defaults = SamplingOptions.builder().temperature(0).maxTokens(%d).build();
                  try (var runtime = ModelJars.openRuntime("%s")) {
                      var model = new ModelsSpringAiChatModel(
                              runtime.model(),
                              runtime.descriptor().alias(),
                              runtime.chatTemplate(),
                              defaults,
                              runtime.descriptor().capabilities());
                      return ChatClient.create(model)
                              .prompt()
                              .user(question)
                              .tools(new WeatherTools())
                              .call()
                              .content();
                  }
              }
              """
                  .formatted(MAX_TOKENS, coordinate);
      case ACTION_TOOLS ->
          WEATHER_TOOLS
              + """

              String ask(String question) {
                  var defaults = SamplingOptions.builder().temperature(0).maxTokens(%d).build();
                  try (var runtime = ModelJars.openRuntime("%s")) {
                      var model = new ModelsSpringAiChatModel(
                              runtime.model(),
                              runtime.descriptor().alias(),
                              runtime.chatTemplate(),
                              defaults,
                              runtime.descriptor().capabilities())
                          .withToolResultRenderer("get-weather-for-zipcode", Weather.class,
                              weather -> "The weather in %%s is %%s with a temperature of %%d F."
                                  .formatted(weather.zipcode(), weather.conditions(),
                                      weather.temperature()));
                      return ChatClient.create(model)
                              .prompt()
                              .user(question)
                              .tools(new WeatherTools())
                              .call()
                              .content();
                  }
              }
              """
                  .formatted(MAX_TOKENS, coordinate);
      case EMBEDDING ->
          """
          float[] embed(String text) {
              try (var model = new ModelsSpringAiEmbeddingModel(
                      ModelJars.openEmbedding("%s"),
                      "%s",
                      ObservationRegistry.NOOP)) {
                  return model.embed(text);
              }
          }
          """
              .formatted(coordinate, descriptor.alias());
      case RERANKING ->
          """
          List<Document> rerank(String query, List<Document> documents) {
              try (var reranker = new ModelsSpringAiDocumentReranker(
                      ModelJars.openReranker("%s"))) {
                  return reranker.process(new Query(query), documents);
              }
          }
          """
              .formatted(coordinate);
    };
  }

  private static final String WEATHER_TOOLS =
      """
      record Weather(String zipcode, String conditions, int temperature) {}

      static final class WeatherTools {
          @Tool(name = "get-weather-for-zipcode", description = "Gets weather for a given zipcode")
          Weather getWeatherForZipcode(
                  @ToolParam(description = "The zipcode to get weather for") String zipcode) {
              return new Weather(zipcode, "Raining cats and dogs", 78);
          }
      }
      """;

  private static String notation(ModelJarCoordinate coordinate) {
    return coordinate.groupId() + ':' + coordinate.artifactId() + ':' + coordinate.version();
  }

  private static String managedNotation(Dependency dependency) {
    return dependency.coordinate().groupId() + ':' + dependency.coordinate().artifactId();
  }

  private static String lines(
      java.util.stream.Stream<String> first, java.util.stream.Stream<String> second) {
    return java.util.stream.Stream.concat(first, second).collect(Collectors.joining("\n"));
  }

  private static String indent(String value, String prefix) {
    return value.lines().map(line -> prefix + line).collect(Collectors.joining("\n"));
  }

  private static java.util.stream.Collector<CharSequence, ?, String> joining() {
    return Collectors.joining("\n", "", "\n");
  }
}
