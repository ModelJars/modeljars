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

import com.integrallis.models.api.InferenceContextWindow;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.ContinuousBatchingMetrics;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.TextGenerationSession;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.Objects;
import java.util.Optional;

/**
 * A loaded model together with the exact descriptor and prompt template that were qualified for it.
 *
 * <p>The runtime owns the model backend and must be closed.
 */
public final class ModelJarRuntime implements AutoCloseable {
  private final InferencePipeline pipeline;
  private final ModelJarDescriptor descriptor;
  private final ModelExecutionQualification qualification;
  private final ChatTemplate chatTemplate;

  ModelJarRuntime(
      InferencePipeline pipeline,
      ModelJarDescriptor descriptor,
      ModelExecutionQualification qualification) {
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
    try {
      chatTemplate = ChatTemplate.parse(qualification.promptTemplate());
    } catch (IllegalArgumentException failure) {
      try {
        pipeline.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw new ModelJarException(
          "Qualification for "
              + descriptor.markerCoordinate()
              + " uses an unsupported chat template: "
              + qualification.promptTemplate(),
          failure);
    }
  }

  /**
   * Returns the ready-to-use text generation model.
   *
   * @return the owned model
   */
  public TextGenerationModel model() {
    return pipeline;
  }

  /**
   * Returns the complete Models inference pipeline for this qualified artifact.
   *
   * <p>The pipeline exposes structured tokenization, model metadata, active context state, prefill,
   * forward-pass logits, reset, checkpoint, rewind, and high-level generation. It is owned by this
   * runtime and must not be closed separately.
   *
   * @return the owned inference pipeline
   */
  public InferencePipeline pipeline() {
    return pipeline;
  }

  /**
   * Opens independent conversation state while sharing this runtime's loaded model weights.
   *
   * <p>Use one session per conversation. Each session owns its prompt-prefix and KV-cache lineage;
   * closing it does not close this runtime or another session. The runtime closes any sessions that
   * remain open when it is closed.
   *
   * @return a new conversation-scoped generation session
   * @throws UnsupportedOperationException when the selected backend cannot isolate sessions
   */
  public TextGenerationSession openGenerationSession() {
    return pipeline.openGenerationSession();
  }

  /**
   * Returns scheduler measurements when this runtime was opened with continuous batching.
   *
   * @return current scheduler measurements, or empty for an ordinary runtime
   */
  public Optional<ContinuousBatchingMetrics> continuousBatchingMetrics() {
    return pipeline.continuousBatchingMetrics();
  }

  /**
   * Returns immutable architecture metadata for the loaded model.
   *
   * @return loaded model metadata
   */
  public ModelMetadata metadata() {
    return pipeline.metadata();
  }

  /**
   * Returns the loaded model's read-only tokenizer.
   *
   * @return loaded model tokenizer
   */
  public Tokenizer tokenizer() {
    return pipeline.tokenizer();
  }

  /**
   * Returns the active context capacity and current position when available.
   *
   * @return current context-window snapshot
   */
  public InferenceContextWindow contextWindow() {
    return pipeline.contextWindow();
  }

  /**
   * Returns the immutable descriptor selected from the marker classpath.
   *
   * @return selected model descriptor
   */
  public ModelJarDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns the exact RAG qualification used for backend and prompt selection.
   *
   * <p>This compatibility accessor applies to RAG-qualified artifacts. Use {@link
   * #executionQualification()} or {@link #toolQualification()} for tool-qualified artifacts.
   *
   * @return selected RAG qualification
   * @throws ModelJarException when this artifact was qualified for a different workload
   */
  public ModelRagQualification qualification() {
    return ragQualification()
        .orElseThrow(
            () ->
                new ModelJarException(
                    "ModelJar "
                        + descriptor.markerCoordinate()
                        + " uses "
                        + qualification.workload()
                        + " evidence; use executionQualification()"));
  }

  /**
   * Returns the exact execution evidence used for backend and prompt selection.
   *
   * @return selected RAG or tool-calling qualification
   */
  public ModelExecutionQualification executionQualification() {
    return qualification;
  }

  /**
   * Returns the selected RAG evidence when this artifact was RAG-qualified.
   *
   * @return selected RAG qualification, if applicable
   */
  public Optional<ModelRagQualification> ragQualification() {
    return qualification instanceof ModelRagQualification rag ? Optional.of(rag) : Optional.empty();
  }

  /**
   * Returns the selected tool-calling evidence when this artifact was tool-qualified.
   *
   * @return selected tool-calling qualification, if applicable
   */
  public Optional<ModelToolQualification> toolQualification() {
    return qualification instanceof ModelToolQualification tool
        ? Optional.of(tool)
        : Optional.empty();
  }

  /**
   * Returns the Models chat template proven by the selected qualification.
   *
   * @return qualified chat template
   */
  public ChatTemplate chatTemplate() {
    return chatTemplate;
  }

  /** Closes the owned model backend. */
  @Override
  public void close() {
    pipeline.close();
  }
}
