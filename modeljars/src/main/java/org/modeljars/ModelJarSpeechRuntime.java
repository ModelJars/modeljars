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

import com.integrallis.models.api.TextToSpeechModel;
import java.util.Objects;

/** A loaded text-to-speech model plus the exact evidence that qualified its bytes. */
public final class ModelJarSpeechRuntime implements AutoCloseable {
  private final TextToSpeechModel model;
  private final ModelJarDescriptor descriptor;
  private final ModelSpeechQualificationRegistry.Entry qualification;

  ModelJarSpeechRuntime(
      TextToSpeechModel model,
      ModelJarDescriptor descriptor,
      ModelSpeechQualificationRegistry.Entry qualification) {
    this.model = Objects.requireNonNull(model, "model");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
  }

  /**
   * Returns the owned, ready-to-use speech model.
   *
   * @return loaded speech model
   */
  public TextToSpeechModel model() {
    return model;
  }

  /**
   * Returns the exact marker descriptor.
   *
   * @return selected model descriptor
   */
  public ModelJarDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns the correctness, streaming, and latency evidence used to admit the artifact.
   *
   * @return selected speech qualification
   */
  public ModelSpeechQualificationRegistry.Entry qualification() {
    return qualification;
  }

  /** Closes the owned speech model. */
  @Override
  public void close() {
    model.close();
  }
}
