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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.modeljars.ModelExecutionQualification;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelToolQualificationRegistry;
import org.modeljars.QualifiedPromptTemplates;

/**
 * Decides which Models Spring integration applies to a catalog model.
 *
 * <p>The surfaces mirror what {@code models-spring-ai} and {@code models-spring-boot-starter}
 * actually provide: the starter auto-configures only the chat model (from an application-provided
 * {@code TextGenerationModel} bean, with {@code integrallis.models.chat-template} and {@code
 * integrallis.models.sampling.*}); embedding and reranking adapters are ordinary beans.
 */
final class SpringIntegration {
  private static final Set<String> EMBEDDING = Set.of("embedding", "embeddings", "text-embedding");
  private static final Set<String> RERANKING = Set.of("reranking", "text-ranking");
  private static final Set<String> CHAT = Set.of("chat", "generation", "text-generation");
  private static final Set<String> SPEECH = Set.of("text-to-speech", "audio-generation");
  private static final String VERSIONS_RESOURCE =
      "/org/modeljars/cli/spring-integration.properties";

  private SpringIntegration() {}

  enum Flavor {
    SPRING_AI("--spring-ai", "Spring AI", "spring-ai"),
    SPRING_BOOT("--spring-boot", "Spring Boot", "spring-boot");

    private final String flag;
    private final String label;
    private final String id;

    Flavor(String flag, String label, String id) {
      this.flag = flag;
      this.label = label;
      this.id = id;
    }

    String flag() {
      return flag;
    }

    String label() {
      return label;
    }

    String id() {
      return id;
    }
  }

  enum Kind {
    /** Generative chat through Spring AI's {@code ChatModel}. */
    CHAT,
    /** Generative chat qualified for tool calling; Spring AI executes typed Java callbacks. */
    TOOL_CHAT,
    /** Needle-style action selection; results need typed renderers on the adapter. */
    ACTION_TOOLS,
    EMBEDDING,
    RERANKING;

    String id() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /** Versions written into generated dependency declarations. */
  record Versions(String modeljars, String models, String springAi, String springBoot) {
    Versions {
      requireText(modeljars, "modeljars");
      requireText(models, "models");
      requireText(springAi, "springAi");
      requireText(springBoot, "springBoot");
    }

    /** Whether a published ModelJars runtime version is known. */
    boolean releasedRuntime() {
      return !modeljars.equals("development");
    }

    /**
     * Loads the Models, Spring AI, and Spring Boot versions this CLI was built and tested with.
     *
     * @param modeljarsVersion this CLI's ModelJars version
     */
    static Versions bundled(String modeljarsVersion) {
      Properties properties = new Properties();
      try (InputStream input = SpringIntegration.class.getResourceAsStream(VERSIONS_RESOURCE)) {
        if (input == null) {
          throw new IllegalStateException("Missing CLI resource " + VERSIONS_RESOURCE);
        }
        properties.load(input);
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
      return new Versions(
          modeljarsVersion,
          properties.getProperty("modelsVersion"),
          properties.getProperty("springAiVersion"),
          properties.getProperty("springBootVersion"));
    }

    private static void requireText(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " version must not be blank");
      }
    }
  }

  /** Looks up the chat template recorded by a model's qualification. */
  @FunctionalInterface
  interface ChatTemplates {
    Optional<String> templateFor(ModelJarDescriptor descriptor);

    static ChatTemplates of(Map<String, String> templatesByAlias) {
      Map<String, String> copy = Map.copyOf(templatesByAlias);
      return descriptor -> Optional.ofNullable(copy.get(descriptor.alias()));
    }

    /**
     * Selects the template the ModelJars runtime would use by default: among production-usable RAG
     * and tool qualifications for the exact artifact, the one with the lowest p95 end-to-end
     * latency, matching {@code ModelJars.openRuntime} with automatic backend selection. The
     * recorded prompt template is mapped to its runtime chat-template id, as the runtime does.
     */
    static ChatTemplates fromClasspath() {
      ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
      ModelToolQualificationRegistry tools = ModelToolQualificationRegistry.fromClasspath();
      return descriptor ->
          Stream.<ModelExecutionQualification>concat(
                  rag.qualificationsFor(descriptor).stream(),
                  tools.qualificationsFor(descriptor).stream())
              .filter(ModelExecutionQualification::productionUsable)
              .min(
                  Comparator.comparingDouble(ModelExecutionQualification::p95EndToEndMillis)
                      .thenComparing(ModelExecutionQualification::backend)
                      .thenComparing(ModelExecutionQualification::workload))
              .map(
                  qualification ->
                      QualifiedPromptTemplates.runtimeChatTemplateId(
                          qualification.promptTemplate()));
    }
  }

  static Kind kind(ModelJarDescriptor descriptor, Flavor flavor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(flavor, "flavor");
    String unavailable = unavailableReason(descriptor);
    if (unavailable != null) throw unsupported(descriptor, flavor, unavailable);
    Set<String> capabilities = descriptor.capabilities();
    if (capabilities.contains("tool-calling")) return toolKind(descriptor, flavor);
    if (capabilities.stream().anyMatch(EMBEDDING::contains)) return Kind.EMBEDDING;
    if (capabilities.stream().anyMatch(RERANKING::contains)) return Kind.RERANKING;
    if (capabilities.stream().anyMatch(CHAT::contains)) return Kind.CHAT;
    throw unsupported(descriptor, flavor, "has no capability with a Models Spring adapter");
  }

  private static String unavailableReason(ModelJarDescriptor descriptor) {
    if (descriptor.format().equals("composite"))
      return "is a qualified hybrid, which has no Models Spring adapter";
    if (descriptor.capabilities().stream().anyMatch(SPEECH::contains))
      return "is a speech model, which has no Models Spring adapter";
    if (descriptor.capabilities().contains("image-understanding"))
      return "is an image-question model, which has no Models Spring adapter";
    return null;
  }

  private static Kind toolKind(ModelJarDescriptor descriptor, Flavor flavor) {
    if (!descriptor.architecture().equals("needle2")) return Kind.TOOL_CHAT;
    if (flavor == Flavor.SPRING_BOOT) {
      throw new IllegalArgumentException(
          "Model "
              + descriptor.alias()
              + " selects actions rather than writing answers, so Spring AI needs a typed"
              + " tool-result renderer registered on its ModelsSpringAiChatModel. The Models"
              + " Spring Boot starter's auto-configured chat model cannot register one; use "
              + Flavor.SPRING_AI.flag()
              + " instead.");
    }
    return Kind.ACTION_TOOLS;
  }

  private static IllegalArgumentException unsupported(
      ModelJarDescriptor descriptor, Flavor flavor, String reason) {
    return new IllegalArgumentException(
        "Model "
            + descriptor.alias()
            + " "
            + reason
            + ". "
            + flavor.label()
            + " integration supports chat, tool-calling, embedding, and reranking models."
            + " Run 'modeljars demo "
            + descriptor.alias()
            + "' for the plain Java demo.");
  }

  static String requireChatTemplate(ModelJarDescriptor descriptor, ChatTemplates templates) {
    return templates
        .templateFor(descriptor)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No qualified chat template is recorded for "
                        + descriptor.alias()
                        + " in this CLI's catalog, so integrallis.models.chat-template cannot be"
                        + " set safely. Update the CLI, or use "
                        + Flavor.SPRING_AI.flag()
                        + ", which reads the template from the runtime."));
  }
}
