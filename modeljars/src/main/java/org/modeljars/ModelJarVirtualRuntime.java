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
package org.modeljars;

import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.VirtualChatModel;
import java.util.List;
import java.util.Objects;

/** A lifecycle-owning virtual model assembled from qualified ModelJar runtimes. */
public final class ModelJarVirtualRuntime implements AutoCloseable {
  private final ModelJarRuntime chatRuntime;
  private final ModelJarRuntime toolRuntime;
  private final VirtualChatModel model;
  private boolean closed;

  ModelJarVirtualRuntime(
      ModelJarRuntime chatRuntime, ModelJarRuntime toolRuntime, VirtualChatModel model) {
    this.chatRuntime = Objects.requireNonNull(chatRuntime, "chatRuntime");
    this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime");
    this.model = Objects.requireNonNull(model, "model");
  }

  /**
   * Returns the composed semantic chat model.
   *
   * @return composed virtual model
   */
  public VirtualChatModel model() {
    requireOpen();
    return model;
  }

  /**
   * Opens independent conversation state for both physical members.
   *
   * @return new virtual-model conversation
   */
  public VirtualChatModel.Session openSession() {
    requireOpen();
    return model.openSession();
  }

  /**
   * Opens a conversation with model-independent initial history.
   *
   * @param initialHistory canonical messages available before the first turn
   * @return new virtual-model conversation initialized with the supplied history
   */
  public VirtualChatModel.Session openSession(List<ChatMessage> initialHistory) {
    requireOpen();
    return model.openSession(initialHistory);
  }

  /**
   * Returns the qualified runtime used for ordinary chat and tool-result narration.
   *
   * @return chat-member runtime
   */
  public ModelJarRuntime chatRuntime() {
    requireOpen();
    return chatRuntime;
  }

  /**
   * Returns the qualified runtime used for tool selection and argument generation.
   *
   * @return tool-member runtime
   */
  public ModelJarRuntime toolRuntime() {
    requireOpen();
    return toolRuntime;
  }

  /** Closes both physical runtimes and every conversation session they still own. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    Throwable failure = null;
    try {
      toolRuntime.close();
    } catch (RuntimeException | Error closeFailure) {
      failure = closeFailure;
    }
    try {
      chatRuntime.close();
    } catch (RuntimeException | Error closeFailure) {
      if (failure == null) {
        failure = closeFailure;
      } else {
        failure.addSuppressed(closeFailure);
      }
    }
    if (failure instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    }
    if (failure instanceof Error error) {
      throw error;
    }
  }

  private synchronized void requireOpen() {
    if (closed) {
      throw new IllegalStateException("virtual ModelJar runtime is closed");
    }
  }
}
