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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelVersion;

/** Shared descriptors and an in-process compiler for generated Spring programs. */
final class SpringFixtures {
  static final SpringIntegration.Versions VERSIONS =
      new SpringIntegration.Versions("0.1.42", "0.3.40", "2.0.0", "4.1.0");

  static final SpringIntegration.ChatTemplates TEMPLATES =
      SpringIntegration.ChatTemplates.of(
          Map.of("example_q4_0", "chatml", "example_tools_q8_0", "chatml-no-think"));

  private SpringFixtures() {}

  static ModelJarDescriptor chat() {
    return descriptor("example_q4_0", Set.of("chat", "text-generation"), "llama", "gguf", Set.of());
  }

  static ModelJarDescriptor toolChat() {
    return descriptor(
        "example_tools_q8_0",
        Set.of("chat", "text-generation", "tool-calling"),
        "qwen3",
        "gguf",
        Set.of());
  }

  static ModelJarDescriptor needle() {
    return descriptor(
        "example_needle_cq2",
        Set.of("chat", "text-generation", "tool-calling"),
        "needle2",
        "cact",
        Set.of());
  }

  static ModelJarDescriptor embedding() {
    return descriptor(
        "example_embedding_q8_0", Set.of("embeddings", "text-embedding"), "bert", "gguf", Set.of());
  }

  static ModelJarDescriptor reranking() {
    return descriptor(
        "example_reranker_q4_k", Set.of("reranking", "text-ranking"), "bert", "gguf", Set.of());
  }

  static ModelJarDescriptor speech() {
    return descriptor(
        "example_speech_q8_0",
        Set.of("text-to-speech", "audio-generation"),
        "soprano",
        "gguf",
        Set.of());
  }

  static ModelJarDescriptor composite() {
    return descriptor(
        "example_chat_tools_composite",
        Set.of("chat", "text-generation", "tool-calling"),
        "hybrid",
        "composite",
        Set.of("virtual-model"));
  }

  static ModelJarDescriptor descriptor(
      String alias,
      Set<String> capabilities,
      String architecture,
      String format,
      Set<String> features) {
    String artifact = alias.replace('_', '.');
    return new ModelJarDescriptor(
        alias,
        "hf://example/" + alias,
        ModelJarCoordinate.parse("org.modeljars.huggingface:" + artifact + ":1.0.0-q.1"),
        ModelVersion.parse("1.0.0"),
        "q",
        format,
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
        features,
        List.of(),
        Map.of("pure-java", true),
        Optional.of("Example " + alias.toLowerCase(Locale.ROOT)),
        Optional.of("Example description"),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }

  /**
   * Compiles a generated single-file program against the test classpath, which carries the real
   * ModelJars runtime, Models Spring adapters, Spring AI, and Spring Boot jars.
   */
  static void assertCompiles(String fileName, String source, Path outputDirectory)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Files.createDirectories(outputDirectory);
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavaFileObject unit =
          new SimpleJavaFileObject(
              URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return source;
            }
          };
      List<String> options = new ArrayList<>();
      options.addAll(List.of("-proc:none", "-Xlint:none", "--add-modules", "jdk.incubator.vector"));
      options.addAll(List.of("-classpath", System.getProperty("java.class.path")));
      options.addAll(List.of("-d", outputDirectory.toString()));
      boolean compiled =
          compiler.getTask(null, files, diagnostics, options, null, List.of(unit)).call();
      String errors =
          diagnostics.getDiagnostics().stream()
              .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
              .map(diagnostic -> "line " + diagnostic.getLineNumber() + ": " + diagnostic)
              .collect(Collectors.joining("\n"));
      assertTrue(compiled, () -> fileName + " does not compile:\n" + errors + "\n\n" + source);
    }
  }

  static void assertContains(String source, String... fragments) {
    for (String fragment : fragments) {
      assertTrue(
          source.contains(fragment),
          () -> "missing fragment: " + fragment + "\n---\n" + source + "\n---");
    }
  }
}
