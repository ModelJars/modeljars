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

import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.LetterLogitScorer;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Objects;

/**
 * A ModelJar opened for typed decisions rather than generation.
 *
 * <p>A decision is a whole question -- evidence, a criterion, and a closed set of outcomes --
 * answered with a probability distribution over exactly those outcomes. This runtime never
 * generates: it reads the last-position logits at the tokens standing for the declared options and
 * normalises over just those, so there is no JSON to repair, no parse to fail, and no decoding loop
 * to bound.
 *
 * <p>This is the fourth capability runtime alongside generation, embedding, reranking and speech,
 * and it exists for the same reason they do: the qualification a decision model needs, and the call
 * it answers, are not the ones a chat model needs.
 */
public final class ModelJarDecisionRuntime implements AutoCloseable {

  private final InferenceBackend backend;
  private final ModelJarDescriptor descriptor;
  private final ModelExecutionQualification qualification;
  private final LetterLogitScorer scorer;

  ModelJarDecisionRuntime(
      InferenceBackend backend,
      ModelJarDescriptor descriptor,
      ModelExecutionQualification qualification,
      double temperature) {
    this.backend = Objects.requireNonNull(backend, "backend");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.qualification = Objects.requireNonNull(qualification, "qualification");
    this.scorer = new LetterLogitScorer(temperature);
  }

  /**
   * Answers one decision in a single forward pass.
   *
   * <p>The options are rendered as lettered choices, the prompt is prefilled, and the letters'
   * logits at the final position are normalised over themselves. Every answer slot is verified to
   * be one exact round-trip token first; a slot that merges with neighbouring text would read the
   * wrong logit and fail silently rather than loudly.
   *
   * @param space the declared answer space
   * @param state the evidence and criterion the decision is made against
   * @return a probability over exactly the declared options
   */
  public Verdict decide(AnswerSpace space, String state) {
    Objects.requireNonNull(space, "space");
    Objects.requireNonNull(state, "state");
    List<String> labels = space.labels();
    Tokenizer tokenizer = backend.tokenizer();

    int[] prompt = tokenizer.encode(state + "\n" + LetterLogitScorer.renderOptions(labels));
    int[] letterTokens = new int[labels.size()];
    for (int index = 0; index < labels.size(); index++) {
      int[] encoded = tokenizer.encode(" " + (char) ('A' + index));
      letterTokens[index] = encoded[encoded.length - 1];
    }

    int last = prompt.length - 1;
    if (last > 0) {
      int[] head = new int[last];
      System.arraycopy(prompt, 0, head, 0, last);
      backend.prefill(head, 0);
    }
    float[] logits = backend.forward(prompt[last], last);
    return scorer.score(space, logits, letterTokens);
  }

  /**
   * The backend the decision ran on, for callers that need the raw forward pass.
   *
   * @return the open backend holding the frozen weights
   */
  public InferenceBackend backend() {
    return backend;
  }

  /**
   * The resolved, verified ModelJar this runtime opened.
   *
   * @return the descriptor naming the artifact and the coordinate it was resolved from
   */
  public ModelJarDescriptor descriptor() {
    return descriptor;
  }

  /**
   * The execution qualification that admitted this artifact on this backend.
   *
   * @return the qualification this runtime was opened under
   */
  public ModelExecutionQualification executionQualification() {
    return qualification;
  }

  @Override
  public void close() {
    backend.close();
  }
}
