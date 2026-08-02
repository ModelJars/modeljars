package org.modeljars;

import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.Objects;

/**
 * A loaded model together with the exact descriptor and prompt template that were qualified for
 * it.
 *
 * <p>The runtime owns the model backend and must be closed.
 */
public final class ModelJarRuntime implements AutoCloseable {
  private final TextGenerationModel model;
  private final ModelJarDescriptor descriptor;
  private final ModelRagQualification qualification;
  private final ChatTemplate chatTemplate;

  ModelJarRuntime(
      TextGenerationModel model,
      ModelJarDescriptor descriptor,
      ModelRagQualification qualification) {
    this.model = Objects.requireNonNull(model, "model");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
    try {
      chatTemplate = ChatTemplate.parse(qualification.promptTemplate());
    } catch (IllegalArgumentException failure) {
      try {
        model.close();
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
    return model;
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
    model.close();
  }
}
