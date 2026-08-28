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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.modeljars.ModelJarDescriptor;

/** ModelJars runtime interactions exercised by the CLI. */
interface ModelInteractions {
  ChatSession openChat(ModelJarDescriptor descriptor, Path cacheDirectory);

  EmbeddingResult embed(ModelJarDescriptor descriptor, Path cacheDirectory, String text);

  static ModelInteractions unavailable() {
    return new ModelInteractions() {
      @Override
      public ChatSession openChat(ModelJarDescriptor descriptor, Path cacheDirectory) {
        throw new IllegalStateException("Model interactions are unavailable");
      }

      @Override
      public EmbeddingResult embed(
          ModelJarDescriptor descriptor, Path cacheDirectory, String text) {
        throw new IllegalStateException("Model interactions are unavailable");
      }
    };
  }

  interface ChatSession extends AutoCloseable {
    ChatResult generate(List<ChatTurn> history, ChatOptions options, Consumer<String> tokens);

    default void clear() {}

    @Override
    void close();
  }

  enum Role {
    USER,
    ASSISTANT
  }

  record ChatTurn(Role role, String text) {
    public ChatTurn {
      role = Objects.requireNonNull(role, "role");
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("chat text must not be blank");
      }
    }
  }

  record ChatOptions(int maxTokens, float temperature, Long seed) {
    public ChatOptions {
      if (maxTokens <= 0) {
        throw new IllegalArgumentException("maxTokens must be positive");
      }
      if (!Float.isFinite(temperature) || temperature < 0) {
        throw new IllegalArgumentException("temperature must be finite and non-negative");
      }
    }
  }

  record ChatMetrics(
      long loadMillis,
      long ttftMillis,
      long generationMillis,
      int promptTokens,
      int completionTokens,
      double tokensPerSecond) {
    public ChatMetrics {
      if (loadMillis < 0 || ttftMillis < 0 || generationMillis < 0) {
        throw new IllegalArgumentException("timings must not be negative");
      }
      if (promptTokens < 0 || completionTokens < 0 || tokensPerSecond < 0) {
        throw new IllegalArgumentException("token metrics must not be negative");
      }
    }
  }

  record ChatResult(String text, ChatMetrics metrics) {
    public ChatResult {
      text = Objects.requireNonNull(text, "text");
      metrics = Objects.requireNonNull(metrics, "metrics");
    }
  }

  record EmbeddingResult(float[] vector, long loadMillis, long embeddingMillis, double vectorNorm) {
    public EmbeddingResult {
      vector = Objects.requireNonNull(vector, "vector").clone();
      if (loadMillis < 0 || embeddingMillis < 0 || !Double.isFinite(vectorNorm) || vectorNorm < 0) {
        throw new IllegalArgumentException("invalid embedding metrics");
      }
    }

    @Override
    public float[] vector() {
      return vector.clone();
    }
  }
}
