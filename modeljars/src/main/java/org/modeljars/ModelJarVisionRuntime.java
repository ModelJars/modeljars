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

import com.integrallis.models.api.ImageInferenceBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Owned LLaVA image-question runtime and its exact two-file evidence. Not thread safe. */
public final class ModelJarVisionRuntime implements AutoCloseable {
  private final ImageInferenceBackend backend;
  private final ModelJarDescriptor descriptor;
  private final ModelVisionQualification qualification;

  ModelJarVisionRuntime(
      ImageInferenceBackend backend,
      ModelJarDescriptor descriptor,
      ModelVisionQualification qualification) {
    this.backend = Objects.requireNonNull(backend, "backend");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
  }

  /**
   * Returns the owned image backend.
   *
   * @return owned image backend for callers that prepare their own prompts
   */
  public ImageInferenceBackend backend() {
    return backend;
  }

  /**
   * Returns the selected model marker.
   *
   * @return exact model marker
   */
  public ModelJarDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns the evidence bound to both model files.
   *
   * @return paired task evidence for the installed text and projector
   */
  public ModelVisionQualification qualification() {
    return qualification;
  }

  /**
   * Answers an independent image question using the qualified short-answer template and greedy
   * decode.
   *
   * @param image PNG or JPEG bytes
   * @param question question about the image
   * @return generated short answer, with a maximum of 32 tokens
   */
  public String answer(byte[] image, String question) {
    Objects.requireNonNull(image, "image");
    if (image.length == 0 || question == null || question.isBlank())
      throw new IllegalArgumentException("Image and question are required");
    var tokenizer = backend.tokenizer();
    int[] prefix =
        tokenizer.encode(
            "A chat between a curious human and an artificial intelligence assistant. The assistant gives helpful, detailed, and polite answers to the human's questions. USER: ");
    if (prefix.length == 0 || prefix[0] != tokenizer.bosToken()) {
      int[] withBos = new int[prefix.length + 1];
      withBos[0] = tokenizer.bosToken();
      System.arraycopy(prefix, 0, withBos, 1, prefix.length);
      prefix = withBos;
    }
    int[] suffix =
        tokenizer.encode(
            "\n" + question + "\nAnswer the question using a single word or phrase. ASSISTANT:");
    if (suffix.length > 0 && suffix[0] == tokenizer.bosToken())
      suffix = Arrays.copyOfRange(suffix, 1, suffix.length);
    if ((long) prefix.length + backend.imageTokenCount() + suffix.length + 32
        > backend.contextCapacity())
      throw new IllegalArgumentException("Image question exceeds the model context");
    backend.reset();
    backend.prefill(prefix, 0);
    backend.prefillImage(image, prefix.length);
    int position = prefix.length + backend.imageTokenCount();
    float[] logits = backend.prefill(suffix, position);
    position += suffix.length;
    ArrayList<Integer> generated = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      int token = best(logits);
      if (tokenizer.isEndOfGeneration(token)) break;
      generated.add(token);
      if (i < 31) logits = backend.forward(token, position++);
    }
    return tokenizer.decode(generated.stream().mapToInt(Integer::intValue).toArray());
  }

  private static int best(float[] logits) {
    if (logits.length == 0) throw new IllegalStateException("No logits returned");
    int best = 0;
    for (int i = 0; i < logits.length; i++) {
      if (!Float.isFinite(logits[i])) throw new IllegalStateException("Nonfinite model output");
      if (logits[i] > logits[best]) best = i;
    }
    return best;
  }

  /** Closes the owned encoder, projector and decoder. */
  @Override
  public void close() {
    backend.close();
  }
}
