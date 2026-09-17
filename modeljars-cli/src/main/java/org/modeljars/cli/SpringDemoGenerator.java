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

import static org.modeljars.cli.DemoScriptGenerator.javaString;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.cli.DemoScriptGenerator.GeneratedDemo;
import org.modeljars.cli.DemoScriptGenerator.Type;
import org.modeljars.cli.SpringIntegration.Flavor;
import org.modeljars.cli.SpringIntegration.Kind;

/** Produces runnable JBang programs that use a model through Spring AI or Spring Boot. */
final class SpringDemoGenerator {
  private static final int DEMO_MAX_TOKENS = 64;

  private final SpringIntegration.Versions versions;
  private final SpringIntegration.ChatTemplates templates;

  SpringDemoGenerator(
      SpringIntegration.Versions versions, SpringIntegration.ChatTemplates templates) {
    this.versions = Objects.requireNonNull(versions, "versions");
    this.templates = Objects.requireNonNull(templates, "templates");
  }

  GeneratedDemo generate(
      ModelJarDescriptor descriptor, Flavor flavor, Optional<String> requestedInput) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(requestedInput, "requestedInput");
    Kind kind = SpringIntegration.kind(descriptor, flavor);
    String input =
        requestedInput
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .orElseGet(() -> defaultInput(kind));
    String body =
        flavor == Flavor.SPRING_AI
            ? springAiSource(descriptor, kind, input)
            : springBootSource(descriptor, kind, input);
    Type type = type(kind);
    return new GeneratedDemo(
        type,
        DemoScriptGenerator.baseName(descriptor)
            + "-"
            + flavor.id()
            + "-"
            + suffix(type)
            + "-demo.java",
        directives(descriptor, kind, flavor) + body);
  }

  private static Type type(Kind kind) {
    return switch (kind) {
      case CHAT -> Type.CHAT;
      case TOOL_CHAT, ACTION_TOOLS -> Type.TOOLS;
      case EMBEDDING -> Type.EMBEDDING;
      case RERANKING -> Type.RERANKING;
    };
  }

  private static String suffix(Type type) {
    return switch (type) {
      case CHAT -> "chat";
      case TOOLS -> "tools";
      case EMBEDDING -> "embedding";
      case RERANKING -> "reranking";
      case COMPOSITE, SPEECH -> throw new IllegalStateException("no Spring demo for " + type);
    };
  }

  private static String defaultInput(Kind kind) {
    return switch (kind) {
      case CHAT -> "What is the capital of France? Reply with only the city name.";
      case TOOL_CHAT -> "What is the weather for 88252?";
      case ACTION_TOOLS -> "Dim the bedroom lights to 20 percent.";
      case EMBEDDING -> "Public transit connects people and cities.";
      case RERANKING -> "How many people live in Berlin?";
    };
  }

  private String directives(ModelJarDescriptor descriptor, Kind kind, Flavor flavor) {
    String dependencies =
        SpringCoordinates.setup(
                descriptor,
                flavor,
                versions,
                templates,
                java.util.List.of(DependencyCoordinates.Tool.JBANG),
                SpringCoordinates.ConfigFormat.YAML)
            .declarations()
            .get(DependencyCoordinates.Tool.JBANG);
    return "//JAVA 25+\n"
        + "//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector\n"
        + "//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED\n"
        + dependencies.lines().collect(Collectors.joining("\n", "", "\n"))
        + "\n";
  }

  private String springAiSource(ModelJarDescriptor descriptor, Kind kind, String input) {
    String model = javaString(descriptor.markerCoordinate().toString());
    String alias = javaString(descriptor.alias());
    String text = javaString(input);
    return switch (kind) {
      case CHAT ->
          """
          import com.integrallis.models.api.SamplingOptions;
          import com.integrallis.models.spring.ai.ModelsSpringAiChatModel;
          import java.util.logging.Level;
          import java.util.logging.Logger;
          import org.modeljars.ModelJars;
          import org.springframework.ai.chat.client.ChatClient;

          class ModelJarsSpringAiDemo {
            private static final String MODEL = "%s";

            public static void main(String... args) {
              quietLibraries();
              var input = args.length == 0 ? "%s" : String.join(" ", args);
              var defaults = SamplingOptions.builder().temperature(0).maxTokens(%d).build();
              long loadStarted = System.nanoTime();
              try (var runtime = ModelJars.openRuntime(MODEL)) {
                long loaded = System.nanoTime();
                var model = new ModelsSpringAiChatModel(
                    runtime.model(),
                    runtime.descriptor().alias(),
                    runtime.chatTemplate(),
                    defaults,
                    runtime.descriptor().capabilities());

                System.out.println("Input:  " + input);
                var response = ChatClient.create(model).prompt().user(input).call().chatResponse();
                long completed = System.nanoTime();
                System.out.println("Output: " + response.getResult().getOutput().getText().strip());
                var usage = response.getMetadata().getUsage();
                System.out.printf("%%nLoad:      %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
                System.out.printf("Execution: %%,d ms%%n", (completed - loaded) / 1_000_000);
                System.out.printf("Tokens:    %%,d prompt + %%,d completion%%n",
                    usage.getPromptTokens(), usage.getCompletionTokens());
              }
            }
          %s}
          """
              .formatted(model, text, DEMO_MAX_TOKENS, QUIET_LIBRARIES);
      case TOOL_CHAT ->
          """
          import com.integrallis.models.api.SamplingOptions;
          import com.integrallis.models.spring.ai.ModelsSpringAiChatModel;
          import java.util.concurrent.atomic.AtomicInteger;
          import java.util.logging.Level;
          import java.util.logging.Logger;
          import org.modeljars.ModelJars;
          import org.springframework.ai.chat.client.ChatClient;
          import org.springframework.ai.tool.annotation.Tool;
          import org.springframework.ai.tool.annotation.ToolParam;

          class ModelJarsSpringAiDemo {
            private static final String MODEL = "%s";

            record Weather(String zipcode, String conditions, int temperature) {}

            static final class WeatherTools {
              private final AtomicInteger invocations = new AtomicInteger();

              @Tool(name = "get-weather-for-zipcode", description = "Gets weather for a given zipcode")
              Weather getWeatherForZipcode(
                  @ToolParam(description = "The zipcode to get weather for") String zipcode) {
                invocations.incrementAndGet();
                System.out.println("Tool:   get-weather-for-zipcode(" + zipcode + ")");
                return new Weather(zipcode, "Raining cats and dogs", 78);
              }

              int invocations() {
                return invocations.get();
              }
            }

            public static void main(String... args) {
              quietLibraries();
              var input = args.length == 0 ? "%s" : String.join(" ", args);
              var defaults = SamplingOptions.builder().temperature(0).maxTokens(%d).build();
              var weather = new WeatherTools();
              long loadStarted = System.nanoTime();
              try (var runtime = ModelJars.openRuntime(MODEL)) {
                long loaded = System.nanoTime();
                var model = new ModelsSpringAiChatModel(
                    runtime.model(),
                    runtime.descriptor().alias(),
                    runtime.chatTemplate(),
                    defaults,
                    runtime.descriptor().capabilities());

                System.out.println("Input:  " + input);
                String answer = ChatClient.create(model)
                    .prompt()
                    .user(input)
                    .tools(weather)
                    .call()
                    .content();
                long completed = System.nanoTime();
                System.out.println("Output: " + answer.strip());
                System.out.println("Tool calls: " + weather.invocations());
                System.out.printf("%%nLoad:      %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
                System.out.printf("Execution: %%,d ms%%n", (completed - loaded) / 1_000_000);
              }
            }
          %s}
          """
              .formatted(model, text, 256, QUIET_LIBRARIES);
      case ACTION_TOOLS ->
          """
          import com.integrallis.models.api.SamplingOptions;
          import com.integrallis.models.spring.ai.ModelsSpringAiChatModel;
          import java.util.logging.Level;
          import java.util.logging.Logger;
          import org.modeljars.ModelJars;
          import org.springframework.ai.chat.client.ChatClient;
          import org.springframework.ai.tool.annotation.Tool;
          import org.springframework.ai.tool.annotation.ToolParam;

          class ModelJarsSpringAiDemo {
            private static final String MODEL = "%s";

            record LightState(String room, int brightness) {}

            record DoorState(String door, boolean locked) {}

            static final class SmartHome {
              @Tool(name = "set_lights", description = "Set a room's light brightness.")
              LightState setLights(
                  @ToolParam(description = "Room name") String room,
                  @ToolParam(description = "Brightness from 0 to 100") int brightness) {
                System.out.println("Tool:   set_lights(" + room + ", " + brightness + ")");
                return new LightState(room, brightness);
              }

              @Tool(name = "lock_door", description = "Lock a named exterior door.")
              DoorState lockDoor(@ToolParam(description = "Door name") String door) {
                System.out.println("Tool:   lock_door(" + door + ")");
                return new DoorState(door, true);
              }
            }

            public static void main(String... args) {
              quietLibraries();
              var input = args.length == 0 ? "%s" : String.join(" ", args);
              var defaults = SamplingOptions.builder().temperature(0).maxTokens(128).build();
              long loadStarted = System.nanoTime();
              try (var runtime = ModelJars.openRuntime(MODEL)) {
                long loaded = System.nanoTime();
                // Needle selects actions; typed renderers turn each Java result into the answer.
                var model = new ModelsSpringAiChatModel(
                        runtime.model(),
                        runtime.descriptor().alias(),
                        runtime.chatTemplate(),
                        defaults,
                        runtime.descriptor().capabilities())
                    .withToolResultRenderer("set_lights", LightState.class,
                        state -> "The %%s lights are at %%d%%%%.".formatted(state.room(), state.brightness()))
                    .withToolResultRenderer("lock_door", DoorState.class,
                        state -> "The %%s door is locked.".formatted(state.door()));

                System.out.println("Input:  " + input);
                String answer = ChatClient.create(model)
                    .prompt()
                    .user(input)
                    .tools(new SmartHome())
                    .call()
                    .content();
                long completed = System.nanoTime();
                System.out.println("Output: " + answer.strip());
                System.out.printf("%%nLoad:      %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
                System.out.printf("Execution: %%,d ms%%n", (completed - loaded) / 1_000_000);
              }
            }
          %s}
          """
              .formatted(model, text, QUIET_LIBRARIES);
      case EMBEDDING ->
          """
          import com.integrallis.models.spring.ai.ModelsSpringAiEmbeddingModel;
          import io.micrometer.observation.ObservationRegistry;
          import java.util.Arrays;
          import java.util.logging.Level;
          import java.util.logging.Logger;
          import org.modeljars.ModelJars;
          import org.springframework.ai.embedding.EmbeddingModel;

          class ModelJarsSpringAiDemo {
            private static final String MODEL = "%s";

            public static void main(String... args) {
              quietLibraries();
              var input = args.length == 0 ? "%s" : String.join(" ", args);
              long loadStarted = System.nanoTime();
              try (var adapter = new ModelsSpringAiEmbeddingModel(
                  ModelJars.openEmbedding(MODEL), "%s", ObservationRegistry.NOOP)) {
                EmbeddingModel model = adapter;
                long loaded = System.nanoTime();
                float[] vector = model.embed(input);
                long completed = System.nanoTime();

                System.out.println("Input:      " + input);
                System.out.println("Embedding:  " + Arrays.toString(vector));
                System.out.printf("Dimensions: %%,d%%n", model.dimensions());
                System.out.printf("Load:       %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
                System.out.printf("Execution:  %%,d ms%%n", (completed - loaded) / 1_000_000);
              }
            }
          %s}
          """
              .formatted(model, text, alias, QUIET_LIBRARIES);
      case RERANKING ->
          """
          import com.integrallis.models.spring.ai.ModelsSpringAiDocumentReranker;
          import java.util.List;
          import java.util.logging.Level;
          import java.util.logging.Logger;
          import org.modeljars.ModelJars;
          import org.springframework.ai.document.Document;
          import org.springframework.ai.rag.Query;

          class ModelJarsSpringAiDemo {
            private static final String MODEL = "%s";

            public static void main(String... args) {
              quietLibraries();
              var query = args.length == 0 ? "%s" : String.join(" ", args);
              var documents = List.of(
                  Document.builder().text("Berlin has a population of 3,520,031 registered inhabitants.").build(),
                  Document.builder().text("Paris is the capital and most populous city of France.").build(),
                  Document.builder().text("Berlin is well known for its museums and metropolitan area.").build());
              long loadStarted = System.nanoTime();
              try (var reranker = new ModelsSpringAiDocumentReranker(ModelJars.openReranker(MODEL))) {
                long loaded = System.nanoTime();
                List<Document> ranked = reranker.process(new Query(query), documents);
                long completed = System.nanoTime();

                System.out.println("Query: " + query);
                System.out.println("Rank  Score       Document");
                for (int rank = 0; rank < ranked.size(); rank++) {
                  var document = ranked.get(rank);
                  System.out.printf("%%4d  %%10.6f  %%s%%n", rank + 1, document.getScore(), document.getText());
                }
                System.out.printf("%%nLoad:      %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
                System.out.printf("Execution: %%,d ms%%n", (completed - loaded) / 1_000_000);
              }
            }
          %s}
          """
              .formatted(model, text, QUIET_LIBRARIES);
    };
  }

  private String springBootSource(ModelJarDescriptor descriptor, Kind kind, String input) {
    String model = javaString(descriptor.markerCoordinate().toString());
    String alias = javaString(descriptor.alias());
    String text = javaString(input);
    String properties =
        switch (kind) {
          case CHAT, TOOL_CHAT ->
              """
                      "integrallis.models.chat-template", "%s",
                      "integrallis.models.sampling.temperature", "%s",
                      "integrallis.models.sampling.max-tokens", "%d",
              """
                  .formatted(
                      javaString(SpringIntegration.requireChatTemplate(descriptor, templates)),
                      SpringCoordinates.TEMPERATURE,
                      kind == Kind.TOOL_CHAT ? 256 : DEMO_MAX_TOKENS);
          default -> "";
        };
    String header =
        """
        class ModelJarsSpringBootDemo {
          private static final String MODEL = "%s";

          public static void main(String... args) {
            var application = new SpringApplication(ModelJarsSpringBootDemo.class);
            // Equivalent to application.yaml entries; a real application keeps them there.
            application.setDefaultProperties(Map.of(
        %s        "spring.main.web-application-type", "none",
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn"));
            application.run(args);
          }
        """
            .formatted(model, properties);
    String imports =
        """
        import java.util.Map;
        import org.modeljars.ModelJars;
        import org.springframework.boot.CommandLineRunner;
        import org.springframework.boot.SpringApplication;
        import org.springframework.boot.SpringBootConfiguration;
        import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
        import org.springframework.context.annotation.Bean;
        """;
    String annotations =
        """

        @SpringBootConfiguration(proxyBeanMethods = false)
        @EnableAutoConfiguration
        """;
    return switch (kind) {
      case CHAT, TOOL_CHAT ->
          """
          import com.integrallis.models.api.TextGenerationModel;
          import org.springframework.ai.chat.client.ChatClient;
          import org.springframework.ai.chat.model.ChatModel;
          import org.springframework.beans.factory.annotation.Qualifier;
          """
              + imports
              + annotations
              + header
              + """

                /** The application owns model selection; the Models starter adapts it to Spring AI. */
                @Bean(destroyMethod = "close")
                TextGenerationModel localModel() {
                  return ModelJars.open(MODEL);
                }

                @Bean
                CommandLineRunner demo(@Qualifier("modelsChatModel") ChatModel chatModel) {
                  return args -> {
                    var input = args.length == 0 ? "%s" : String.join(" ", args);
                    System.out.println("Input:  " + input);
                    String answer = ChatClient.create(chatModel).prompt().user(input).call().content();
                    System.out.println("Output: " + answer.strip());
                  };
                }
              }
              """
                  .formatted(text);
      case EMBEDDING ->
          """
          import com.integrallis.models.spring.ai.ModelsSpringAiEmbeddingModel;
          import io.micrometer.observation.ObservationRegistry;
          import org.springframework.ai.embedding.EmbeddingModel;
          import org.springframework.beans.factory.ObjectProvider;
          """
              + imports
              + annotations
              + header
              + """

                /** The Models starter auto-configures chat only, so the embedding adapter is a bean. */
                @Bean(destroyMethod = "close")
                ModelsSpringAiEmbeddingModel embeddingModel(
                    ObjectProvider<ObservationRegistry> observations) {
                  return new ModelsSpringAiEmbeddingModel(
                      ModelJars.openEmbedding(MODEL),
                      "%s",
                      observations.getIfAvailable(() -> ObservationRegistry.NOOP));
                }

                @Bean
                CommandLineRunner demo(EmbeddingModel model) {
                  return args -> {
                    var input = args.length == 0 ? "%s" : String.join(" ", args);
                    float[] vector = model.embed(input);
                    System.out.println("Input:      " + input);
                    System.out.printf("Dimensions: %%,d%%n", vector.length);
                    System.out.printf("First values: %%s%%n",
                        java.util.Arrays.toString(java.util.Arrays.copyOf(vector, Math.min(8, vector.length))));
                  };
                }
              }
              """
                  .formatted(alias, text);
      case RERANKING ->
          """
          import com.integrallis.models.spring.ai.ModelsSpringAiDocumentReranker;
          import java.util.List;
          import org.springframework.ai.document.Document;
          import org.springframework.ai.rag.Query;
          import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
          """
              + imports
              + annotations
              + header
              + """

                /** The Models starter auto-configures chat only, so the reranker is a bean. */
                @Bean(destroyMethod = "close")
                ModelsSpringAiDocumentReranker documentReranker() {
                  return new ModelsSpringAiDocumentReranker(ModelJars.openReranker(MODEL));
                }

                @Bean
                CommandLineRunner demo(DocumentPostProcessor reranker) {
                  return args -> {
                    var query = args.length == 0 ? "%s" : String.join(" ", args);
                    var documents = List.of(
                        Document.builder().text("Berlin has a population of 3,520,031 registered inhabitants.").build(),
                        Document.builder().text("Paris is the capital and most populous city of France.").build(),
                        Document.builder().text("Berlin is well known for its museums and metropolitan area.").build());
                    System.out.println("Query: " + query);
                    for (Document document : reranker.process(new Query(query), documents)) {
                      System.out.printf("%%10.6f  %%s%%n", document.getScore(), document.getText());
                    }
                  };
                }
              }
              """
                  .formatted(text);
      case ACTION_TOOLS -> throw new IllegalStateException("unsupported under Spring Boot");
    };
  }

  private static final String QUIET_LIBRARIES =
      """

        private static void quietLibraries() {
          Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
          Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
        }
      """;
}
