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
package org.modeljars.composite.qwen3;

import com.integrallis.models.runtime.chat.VirtualChatModel;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarVirtualRuntime;
import org.modeljars.ModelJars;
import org.modeljars.ModelLoadOptions;

/** The latency-qualified Qwen3 0.6B chat and Qwen3 1.7B tool-selection virtual model. */
public final class Qwen3ChatTools {
  /** Exact qualified chat-member marker. */
  public static final ModelJar CHAT =
      ModelJar.of("org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1");

  /** Exact qualified tool-member marker. */
  public static final ModelJar TOOLS =
      ModelJar.of("org.modeljars.huggingface:qwen.qwen3-1.7b-gguf.q8_0:3.0.0-q8_0.2");

  /** Controlled clean-host qualification evidence for this exact topology and artifact pair. */
  public static final Qualification QUALIFICATION =
      new Qualification(
          "e4d130dd8c5986e6cef6d7ff5cb7d3533a5ceb6b", 3, 53_166L, 35_151L, 2_404_032L, 3_270_444L);

  private Qwen3ChatTools() {}

  /**
   * Downloads, verifies, and opens both members with the qualified context policy.
   *
   * @return lifecycle-owning virtual runtime
   */
  public static ModelJarVirtualRuntime open() {
    return ModelJars.openChatToolHybrid(CHAT, TOOLS);
  }

  /**
   * Opens both members and binds an application-supplied tool decoding constraint.
   *
   * @param toolConstraintFactory per-turn constrained-decoding policy for the tool member
   * @return lifecycle-owning virtual runtime
   */
  public static ModelJarVirtualRuntime open(
      VirtualChatModel.ConstraintFactory toolConstraintFactory) {
    ModelLoadOptions javaOptions =
        ModelLoadOptions.builder().backend(org.modeljars.ModelBackend.JAVA).build();
    return ModelJars.openChatToolHybrid(
        CHAT, TOOLS, javaOptions, javaOptions, toolConstraintFactory);
  }

  /**
   * Immutable clean-host measurements retained with the qualified recipe.
   *
   * @param modelsRevision exact Models source revision used for qualification
   * @param freshProcessesPerArm number of fresh JVMs run for each benchmark arm
   * @param controlMedianMillis median end-to-end latency for the control arm
   * @param hybridMedianMillis median end-to-end latency for the hybrid arm
   * @param controlMedianPeakRssKib median peak resident memory for the control arm
   * @param hybridMedianPeakRssKib median peak resident memory for the hybrid arm
   */
  public record Qualification(
      String modelsRevision,
      int freshProcessesPerArm,
      long controlMedianMillis,
      long hybridMedianMillis,
      long controlMedianPeakRssKib,
      long hybridMedianPeakRssKib) {

    /**
     * Returns the measured end-to-end latency improvement as a fraction.
     *
     * @return fractional latency improvement over the control arm
     */
    public double improvement() {
      return (controlMedianMillis - hybridMedianMillis) / (double) controlMedianMillis;
    }

    /**
     * Returns the measured peak-RSS increase as a fraction.
     *
     * @return fractional peak resident-memory increase over the control arm
     */
    public double peakRssIncrease() {
      return (hybridMedianPeakRssKib - controlMedianPeakRssKib) / (double) controlMedianPeakRssKib;
    }
  }
}
