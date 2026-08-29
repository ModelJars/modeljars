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

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.modeljars.ModelJarDescriptor;

/** Produces small JBang programs that exercise qualified models through their public Java APIs. */
final class DemoScriptGenerator {
  private static final Set<String> EMBEDDING_CAPABILITIES =
      Set.of("embedding", "embeddings", "text-embedding");
  private static final Set<String> CHAT_CAPABILITIES =
      Set.of("chat", "generation", "text-generation");

  enum Type {
    CHAT,
    EMBEDDING,
    TOOLS
  }

  record GeneratedDemo(Type type, String fileName, String source) {
    GeneratedDemo {
      type = Objects.requireNonNull(type, "type");
      if (fileName == null || fileName.isBlank()) {
        throw new IllegalArgumentException("fileName must not be blank");
      }
      if (source == null || source.isBlank()) {
        throw new IllegalArgumentException("source must not be blank");
      }
    }
  }

  private final String runtimeVersion;

  DemoScriptGenerator(String runtimeVersion) {
    if (runtimeVersion == null || runtimeVersion.isBlank()) {
      throw new IllegalArgumentException("runtimeVersion must not be blank");
    }
    this.runtimeVersion = runtimeVersion;
  }

  GeneratedDemo generate(ModelJarDescriptor descriptor, Optional<String> requestedInput) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(requestedInput, "requestedInput");
    Type type = typeFor(descriptor);
    String input =
        requestedInput
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .orElseGet(() -> input(type));
    String source =
        switch (type) {
          case CHAT -> chatSource(descriptor, input);
          case EMBEDDING -> embeddingSource(descriptor, input);
          case TOOLS -> toolSource(descriptor, input);
        };
    return new GeneratedDemo(
        type, baseName(descriptor) + "-" + suffix(type) + "-demo.java", source);
  }

  private static Type typeFor(ModelJarDescriptor descriptor) {
    Set<String> capabilities = descriptor.capabilities();
    if (capabilities.contains("tool-calling") && descriptor.architecture().equals("needle2")) {
      return Type.TOOLS;
    }
    if (capabilities.stream().anyMatch(EMBEDDING_CAPABILITIES::contains)) {
      return Type.EMBEDDING;
    }
    if (capabilities.stream().anyMatch(CHAT_CAPABILITIES::contains)) {
      return Type.CHAT;
    }
    throw new IllegalArgumentException(
        "Model "
            + descriptor.alias()
            + " does not have a supported chat, embedding, or Needle tool-calling demo");
  }

  private static String input(Type type) {
    return switch (type) {
      case CHAT -> "What is the capital of France? Reply with only the city name.";
      case EMBEDDING -> "Public transit connects people and cities.";
      case TOOLS -> "Dim the bedroom lights to 20 percent and lock the front door.";
    };
  }

  private static String suffix(Type type) {
    return switch (type) {
      case CHAT -> "chat";
      case EMBEDDING -> "embedding";
      case TOOLS -> "tools";
    };
  }

  private static String baseName(ModelJarDescriptor descriptor) {
    String name = descriptor.alias().toLowerCase(Locale.ROOT);
    name = name.replaceFirst("_(gguf|cact|safetensors)(_|$).*$", "");
    name = name.replaceFirst("_(q|f|iq|cq)[0-9].*$", "");
    name = name.replace('_', '-').replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-");
    name = name.replaceAll("^-|-$", "");
    return name.isEmpty() ? "modeljars" : name;
  }

  private String directives(ModelJarDescriptor descriptor, boolean json) {
    StringBuilder source =
        new StringBuilder()
            .append("//JAVA 25+\n")
            .append("//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector\n")
            .append("//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED\n")
            .append("//DEPS org.modeljars:modeljars:")
            .append(runtimeVersion)
            .append('\n')
            .append("//DEPS ")
            .append(descriptor.markerCoordinate())
            .append('\n');
    if (json) {
      source.append("//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.2\n");
    }
    return source.append('\n').toString();
  }

  private String chatSource(ModelJarDescriptor descriptor, String defaultInput) {
    return directives(descriptor, false)
        + """
        import com.integrallis.models.api.SamplingOptions;
        import com.integrallis.models.runtime.GenerationMetrics;
        import com.integrallis.models.runtime.chat.ChatMessage;
        import java.util.List;
        import java.util.logging.Level;
        import java.util.logging.Logger;
        import org.modeljars.ModelJars;

        class ModelJarsDemo {
          private static final String MODEL = "%s";

          public static void main(String... args) {
            quietLibraries();
            var input = args.length == 0 ? "%s" : String.join(" ", args);
            long loadStarted = System.nanoTime();
            try (var runtime = ModelJars.openRuntime(MODEL)) {
              long loaded = System.nanoTime();
              var prompt = runtime.chatTemplate().render(List.of(ChatMessage.user(input)));
              var options = SamplingOptions.builder().temperature(0).maxTokens(64).build();

              System.out.println("Input:  " + input);
              var answer = runtime.pipeline().generate(prompt, options);
              System.out.println("Output: " + answer.strip());
              printMetrics(runtime.pipeline().lastGenerationMetrics(), loaded - loadStarted);
            }
          }

          private static void printMetrics(GenerationMetrics metrics, long loadNanos) {
            String ttft = metrics.timeToFirstToken()
                .map(value -> String.format("%%,d ms", value.toMillis())).orElse("n/a");
            System.out.printf("%%nMetrics:%%n");
            System.out.printf("  Load                 %%,d ms%%n", loadNanos / 1_000_000);
            System.out.printf("  Tokenization         %%,d ms%%n", metrics.tokenization().toMillis());
            System.out.printf("  Prompt preparation   %%,d ms%%n", metrics.promptPreparation().toMillis());
            System.out.printf("  Prefill              %%,d ms%%n", metrics.prefill().toMillis());
            System.out.printf("  TTFT                 %%s%%n", ttft);
            System.out.printf("  Decode               %%,d ms (%%.1f tokens/s)%%n",
                metrics.decode().toMillis(), metrics.decodeTokensPerSecond());
            System.out.printf("  Tokens               %%,d prompt + %%,d completion%%n",
                metrics.usage().promptTokens(), metrics.usage().completionTokens());
          }

          private static void quietLibraries() {
            Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
            Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
          }
        }
        """
            .formatted(descriptor.markerCoordinate(), javaString(defaultInput));
  }

  private String embeddingSource(ModelJarDescriptor descriptor, String defaultInput) {
    return directives(descriptor, false)
        + """
        import java.util.Arrays;
        import java.util.logging.Level;
        import java.util.logging.Logger;
        import org.modeljars.ModelJars;

        class ModelJarsDemo {
          private static final String MODEL = "%s";

          public static void main(String... args) {
            quietLibraries();
            var input = args.length == 0 ? "%s" : String.join(" ", args);
            long loadStarted = System.nanoTime();
            try (var model = ModelJars.openEmbedding(MODEL)) {
              long loaded = System.nanoTime();
              var vector = model.embed(input);
              long completed = System.nanoTime();

              System.out.println("Input:     " + input);
              System.out.printf("Embedding: %%s%%n", Arrays.toString(vector));
              System.out.printf("Dimensions: %%,d%%n", vector.length);
              System.out.printf("Load:       %%,d ms%%n", (loaded - loadStarted) / 1_000_000);
              System.out.printf("Execution:   %%,d ms%%n", (completed - loaded) / 1_000_000);
            }
          }

          private static void quietLibraries() {
            Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
            Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
          }
        }
        """
            .formatted(descriptor.markerCoordinate(), javaString(defaultInput));
  }

  private String toolSource(ModelJarDescriptor descriptor, String defaultInput) {
    return directives(descriptor, true)
        + """
        import com.fasterxml.jackson.databind.JsonNode;
        import com.fasterxml.jackson.databind.ObjectMapper;
        import com.integrallis.models.api.SamplingOptions;
        import com.integrallis.models.api.ToolCall;
        import com.integrallis.models.api.ToolSpec;
        import com.integrallis.models.runtime.GenerationMetrics;
        import com.integrallis.models.runtime.ToolCallTokenConstraints;
        import com.integrallis.models.runtime.chat.ChatMessage;
        import com.integrallis.models.runtime.chat.ToolCallScanner;
        import java.util.LinkedHashMap;
        import java.util.LinkedHashSet;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        import java.util.logging.Level;
        import java.util.logging.Logger;
        import org.modeljars.ModelJars;

        class ModelJarsDemo {
          private static final String MODEL = "%s";
          private static final ObjectMapper JSON = new ObjectMapper();
          private static final List<ToolSpec> TOOLS = List.of(
              new ToolSpec("set_lights", "Set a room's light brightness.",
                  "{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"room\\\":{\\\"type\\\":\\\"string\\\"},"
                      + "\\\"brightness\\\":{\\\"type\\\":\\\"integer\\\",\\\"minimum\\\":0,\\\"maximum\\\":100}},"
                      + "\\\"required\\\":[\\\"room\\\",\\\"brightness\\\"]}"),
              new ToolSpec("lock_door", "Lock a named exterior door.",
                  "{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"door\\\":{\\\"type\\\":\\\"string\\\"}},"
                      + "\\\"required\\\":[\\\"door\\\"]}"));

          public static void main(String... args) throws Exception {
            quietLibraries();
            var request = args.length == 0 ? "%s" : String.join(" ", args);
            var home = new SmartHome();
            long loadStarted = System.nanoTime();
            try (var runtime = ModelJars.openRuntime(MODEL)) {
              long loaded = System.nanoTime();
              var prompt = runtime.chatTemplate().render(List.of(ChatMessage.user(request)), TOOLS);
              var constraint = ToolCallTokenConstraints.compile(
                  runtime.tokenizer(), runtime.chatTemplate().toolSyntax(), TOOLS,
                  ignored -> List.of()).orElseThrow();
              var options = SamplingOptions.builder().temperature(0).maxTokens(128).build();

              System.out.println("Request:      " + request);
              var output = runtime.pipeline().generate(prompt, options, constraint);
              System.out.println("Model output: " + output.strip());

              var calls = ToolCallScanner.scan(output, runtime.chatTemplate().toolSyntax()).toolCalls();
              System.out.println("Tool calls:   " + calls.size());
              for (var call : calls) {
                System.out.printf("  %%s %%s%%n", call.name(), call.argumentsJson());
                home.execute(call);
              }
              home.printState();
              printMetrics(runtime.pipeline().lastGenerationMetrics(), loaded - loadStarted);
            }
          }

          private static void printMetrics(GenerationMetrics metrics, long loadNanos) {
            String ttft = metrics.timeToFirstToken()
                .map(value -> String.format("%%,d ms", value.toMillis())).orElse("n/a");
            System.out.printf("%%nMetrics:%%n");
            System.out.printf("  Load                 %%,d ms%%n", loadNanos / 1_000_000);
            System.out.printf("  Tokenization         %%,d ms%%n", metrics.tokenization().toMillis());
            System.out.printf("  Prompt preparation   %%,d ms%%n", metrics.promptPreparation().toMillis());
            System.out.printf("  Prefill              %%,d ms%%n", metrics.prefill().toMillis());
            System.out.printf("  TTFT                 %%s%%n", ttft);
            System.out.printf("  Decode               %%,d ms (%%.1f tokens/s)%%n",
                metrics.decode().toMillis(), metrics.decodeTokensPerSecond());
            System.out.printf("  Tokens               %%,d prompt + %%,d completion%%n",
                metrics.usage().promptTokens(), metrics.usage().completionTokens());
          }

          private static void quietLibraries() {
            Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
            Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
          }

          private static final class SmartHome {
            private final Map<String, Integer> lights = new LinkedHashMap<>();
            private final Set<String> lockedDoors = new LinkedHashSet<>();

            void execute(ToolCall call) throws Exception {
              JsonNode arguments = JSON.readTree(call.argumentsJson());
              switch (call.name()) {
                case "set_lights" -> lights.put(
                    requiredText(arguments, "room"), requiredInt(arguments, "brightness"));
                case "lock_door" -> lockedDoors.add(requiredText(arguments, "door"));
                default -> throw new IllegalArgumentException("Unknown tool: " + call.name());
              }
            }

            void printState() {
              System.out.println("Home state:");
              System.out.println("  Lights:       " + lights);
              System.out.println("  Locked doors: " + lockedDoors);
            }

            private static String requiredText(JsonNode arguments, String name) {
              JsonNode value = arguments.get(name);
              if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("Missing string argument: " + name);
              }
              return value.textValue();
            }

            private static int requiredInt(JsonNode arguments, String name) {
              JsonNode value = arguments.get(name);
              if (value == null || !value.isIntegralNumber()) {
                throw new IllegalArgumentException("Missing integer argument: " + name);
              }
              return value.intValue();
            }
          }
        }
        """
            .formatted(descriptor.markerCoordinate(), javaString(defaultInput));
  }

  private static String javaString(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }
}
