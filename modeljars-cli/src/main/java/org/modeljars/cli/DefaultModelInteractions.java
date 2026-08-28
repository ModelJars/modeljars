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

import com.integrallis.models.api.GenerationUsage;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.TokenStream;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarEmbeddingRuntime;
import org.modeljars.ModelJarRuntime;
import org.modeljars.ModelJars;
import org.modeljars.ModelLoadOptions;

/** Executes the same qualified ModelJars APIs used by Java applications. */
final class DefaultModelInteractions implements ModelInteractions {
  @Override
  public ChatSession openChat(ModelJarDescriptor descriptor, Path cacheDirectory) {
    Objects.requireNonNull(descriptor, "descriptor");
    long started = System.nanoTime();
    ModelJarRuntime runtime =
        ModelJars.openRuntime(
            ModelJar.of(descriptor.markerCoordinate().toString()), options(cacheDirectory));
    long loadMillis = elapsedMillis(started, System.nanoTime());
    return new RuntimeChatSession(runtime, loadMillis);
  }

  @Override
  public EmbeddingResult embed(ModelJarDescriptor descriptor, Path cacheDirectory, String text) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(text, "text");
    long loadStarted = System.nanoTime();
    try (ModelJarEmbeddingRuntime runtime =
        ModelJars.openEmbeddingRuntime(
            ModelJar.of(descriptor.markerCoordinate().toString()), options(cacheDirectory))) {
      long loaded = System.nanoTime();
      float[] vector = runtime.model().embed(text);
      long completed = System.nanoTime();
      return new EmbeddingResult(
          vector,
          elapsedMillis(loadStarted, loaded),
          elapsedMillis(loaded, completed),
          norm(vector));
    }
  }

  private static ModelLoadOptions options(Path cacheDirectory) {
    return ModelLoadOptions.builder().cacheDirectory(cacheDirectory).build();
  }

  private static long elapsedMillis(long started, long completed) {
    return Math.max(0, Math.round((completed - started) / 1_000_000.0));
  }

  private static double norm(float[] vector) {
    double squared = 0;
    for (float value : vector) {
      squared = Math.fma(value, value, squared);
    }
    return Math.sqrt(squared);
  }

  static double decodeTokensPerSecond(int completionTokens, long decodeNanos) {
    int measuredIntervals = Math.max(0, completionTokens - 1);
    if (measuredIntervals == 0) {
      return 0;
    }
    double decodeSeconds = Math.max(0, decodeNanos) / 1_000_000_000.0;
    return measuredIntervals / Math.max(0.001, decodeSeconds);
  }

  private static final class RuntimeChatSession implements ChatSession {
    private final ModelJarRuntime runtime;
    private final long loadMillis;

    private RuntimeChatSession(ModelJarRuntime runtime, long loadMillis) {
      this.runtime = runtime;
      this.loadMillis = loadMillis;
    }

    @Override
    public ChatResult generate(
        List<ChatTurn> history, ChatOptions options, Consumer<String> tokenConsumer) {
      Objects.requireNonNull(history, "history");
      Objects.requireNonNull(options, "options");
      Objects.requireNonNull(tokenConsumer, "tokenConsumer");
      List<ChatMessage> messages =
          history.stream()
              .map(
                  turn ->
                      turn.role() == Role.USER
                          ? ChatMessage.user(turn.text())
                          : ChatMessage.assistant(turn.text()))
              .toList();
      var prompt = runtime.chatTemplate().render(messages);
      SamplingOptions.Builder sampling =
          SamplingOptions.builder()
              .temperature(options.temperature())
              .maxTokens(options.maxTokens());
      if (options.seed() != null) {
        sampling.seed(options.seed());
      }

      StringBuilder output = new StringBuilder();
      AtomicReference<GenerationUsage> usage = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicLong firstTokenAt = new AtomicLong(-1);
      long generationStarted = System.nanoTime();
      runtime
          .pipeline()
          .generate(
              prompt,
              sampling.build(),
              new TokenStream() {
                @Override
                public void onToken(String token) {
                  if (!token.isEmpty()) {
                    firstTokenAt.compareAndSet(-1, System.nanoTime());
                  }
                  output.append(token);
                  tokenConsumer.accept(token);
                }

                @Override
                public void onComplete() {}

                @Override
                public void onComplete(GenerationUsage completedUsage) {
                  usage.set(completedUsage);
                }

                @Override
                public void onError(Throwable generationFailure) {
                  failure.set(generationFailure);
                }
              });
      long generationCompleted = System.nanoTime();
      rethrow(failure.get());
      GenerationUsage completedUsage =
          Objects.requireNonNullElseGet(usage.get(), () -> new GenerationUsage(0, 0));
      long first = firstTokenAt.get() < 0 ? generationCompleted : firstTokenAt.get();
      double throughput =
          decodeTokensPerSecond(
              completedUsage.completionTokens(), Math.max(0, generationCompleted - first));
      return new ChatResult(
          output.toString(),
          new ChatMetrics(
              loadMillis,
              elapsedMillis(generationStarted, first),
              elapsedMillis(generationStarted, generationCompleted),
              completedUsage.promptTokens(),
              completedUsage.completionTokens(),
              throughput));
    }

    @Override
    public void clear() {
      runtime.pipeline().resetContext();
    }

    @Override
    public void close() {
      runtime.close();
    }

    private static void rethrow(Throwable failure) {
      if (failure instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      if (failure != null) {
        throw new IllegalStateException("Model generation failed", failure);
      }
    }
  }
}
