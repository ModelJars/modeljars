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

import com.integrallis.models.api.SchemaClassificationModel;
import java.util.Objects;

/** A loaded classifier plus the exact descriptor and evidence that qualified it. */
public final class ModelJarClassificationRuntime implements AutoCloseable {
  private final SchemaClassificationModel model;
  private final ModelJarDescriptor descriptor;
  private final ModelClassificationQualification qualification;

  ModelJarClassificationRuntime(
      SchemaClassificationModel model,
      ModelJarDescriptor descriptor,
      ModelClassificationQualification qualification) {
    this.model = Objects.requireNonNull(model, "model");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
  }

  /**
   * Returns the owned, ready-to-use schema classification model.
   *
   * @return loaded schema classification model
   */
  public SchemaClassificationModel model() {
    return model;
  }

  /**
   * Returns the immutable marker descriptor.
   *
   * @return selected ModelJar descriptor
   */
  public ModelJarDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns the evidence bound to the selected artifact bytes.
   *
   * @return exact qualification evidence
   */
  public ModelClassificationQualification qualification() {
    return qualification;
  }

  /** Closes the owned classifier. */
  @Override
  public void close() {
    model.close();
  }
}
