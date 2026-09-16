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

import com.integrallis.models.runtime.ActivatedToolCallingModel;
import com.integrallis.models.runtime.ActivatedToolConversation;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.Objects;

/**
 * One qualified base model and one verified Activated-LoRA component sharing physical KV storage.
 *
 * <p>The runtime owns the loaded base weights, adapter, inference pipeline, and every conversation
 * left open when it is closed.
 */
public final class ModelJarActivatedRuntime implements AutoCloseable {
  private final ActivatedToolCallingModel model;
  private final ModelJarDescriptor baseDescriptor;
  private final ModelJarDescriptor adapterDescriptor;
  private final ModelExecutionQualification baseQualification;
  private final ChatTemplate chatTemplate;

  ModelJarActivatedRuntime(
      ActivatedToolCallingModel model,
      ModelJarDescriptor baseDescriptor,
      ModelJarDescriptor adapterDescriptor,
      ModelExecutionQualification baseQualification) {
    this.model = Objects.requireNonNull(model, "model");
    this.baseDescriptor = Objects.requireNonNull(baseDescriptor, "baseDescriptor");
    this.adapterDescriptor = Objects.requireNonNull(adapterDescriptor, "adapterDescriptor");
    this.baseQualification = Objects.requireNonNull(baseQualification, "baseQualification");
    try {
      requireBoundArtifacts();
    } catch (RuntimeException failure) {
      try {
        model.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    try {
      chatTemplate = ChatTemplate.parse(baseQualification.promptTemplate());
    } catch (IllegalArgumentException failure) {
      try {
        model.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw new ModelJarException(
          "Qualification for "
              + baseDescriptor.markerCoordinate()
              + " uses an unsupported chat template: "
              + baseQualification.promptTemplate(),
          failure);
    }
  }

  /**
   * Returns the loaded base plus activated tool specialist as one Models runtime.
   *
   * @return the loaded base with its activated specialist as one Models runtime
   */
  public ActivatedToolCallingModel model() {
    return model;
  }

  /**
   * Opens one stateful conversation retaining a single physically shareable cache lineage.
   *
   * @return a stateful conversation that keeps one physically shareable cache lineage
   */
  public ActivatedToolConversation openConversation() {
    return model.openConversation();
  }

  /**
   * Returns the exact qualified base-model descriptor.
   *
   * @return the exact qualified base-model descriptor
   */
  public ModelJarDescriptor baseDescriptor() {
    return baseDescriptor;
  }

  /**
   * Returns the exact adapter-component descriptor.
   *
   * @return the exact adapter-component descriptor
   */
  public ModelJarDescriptor adapterDescriptor() {
    return adapterDescriptor;
  }

  /**
   * Returns the base model's exact execution qualification.
   *
   * @return the base model's exact execution qualification
   */
  public ModelExecutionQualification baseQualification() {
    return baseQualification;
  }

  /**
   * Returns the base model's qualified chat template used by both branches.
   *
   * @return the base model's qualified chat template, shared by both branches
   */
  public ChatTemplate chatTemplate() {
    return chatTemplate;
  }

  private void requireBoundArtifacts() {
    String expectedBaseSha256 =
        baseDescriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Activated base has no artifact SHA-256: "
                            + baseDescriptor.markerCoordinate()));
    String expectedAdapterSha256 =
        adapterDescriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Activated adapter has no artifact SHA-256: "
                            + adapterDescriptor.markerCoordinate()));
    var metadata = model.adapter();
    if (!metadata.baseArtifactSha256().equals(expectedBaseSha256)) {
      throw new ModelJarException(
          "Loaded activated adapter expects base SHA-256 "
              + metadata.baseArtifactSha256()
              + ", not verified artifact "
              + expectedBaseSha256);
    }
    if (!metadata.adapterSha256().equals(expectedAdapterSha256)) {
      throw new ModelJarException(
          "Loaded activated adapter SHA-256 "
              + metadata.adapterSha256()
              + " does not match verified component "
              + expectedAdapterSha256);
    }
  }

  /** Closes the adapter, base model, and all owned inference state. */
  @Override
  public void close() {
    model.close();
  }
}
