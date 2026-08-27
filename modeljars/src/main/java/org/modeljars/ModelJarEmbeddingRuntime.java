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

import com.integrallis.models.api.EmbeddingBackend;
import java.util.Objects;

/**
 * A loaded embedding model together with the exact descriptor and equivalence evidence that
 * qualified it.
 *
 * <p>The runtime owns the embedding backend and must be closed.
 */
public final class ModelJarEmbeddingRuntime implements AutoCloseable {
  private final EmbeddingBackend model;
  private final ModelJarDescriptor descriptor;
  private final ModelEmbeddingQualificationRegistry.Entry qualification;

  ModelJarEmbeddingRuntime(
      EmbeddingBackend model,
      ModelJarDescriptor descriptor,
      ModelEmbeddingQualificationRegistry.Entry qualification) {
    this.model = Objects.requireNonNull(model, "model");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
  }

  /**
   * Returns the ready-to-use embedding backend.
   *
   * @return the owned embedding backend
   */
  public EmbeddingBackend model() {
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
   * Returns the exact reference-equivalence evidence used to open this artifact.
   *
   * @return selected embedding qualification
   */
  public ModelEmbeddingQualificationRegistry.Entry qualification() {
    return qualification;
  }

  /** Closes the owned embedding backend. */
  @Override
  public void close() {
    model.close();
  }
}
