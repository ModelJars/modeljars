package org.modeljars;

import com.integrallis.models.api.InferenceContextWindow;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.Objects;

/**
 * A loaded model together with the exact descriptor and prompt template that were qualified for
 * it.
 *
 * <p>The runtime owns the model backend and must be closed.
 */
public final class ModelJarRuntime implements AutoCloseable {
  private final InferencePipeline pipeline;
  private final ModelJarDescriptor descriptor;
  private final ModelRagQualification qualification;
  private final ChatTemplate chatTemplate;

  ModelJarRuntime(
      InferencePipeline pipeline,
      ModelJarDescriptor descriptor,
      ModelRagQualification qualification) {
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
   * Returns the exact production qualification used for backend and prompt selection.
   *
   * @return selected production qualification
   */
  public ModelRagQualification qualification() {
    return qualification;
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
