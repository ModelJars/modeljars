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
import com.integrallis.models.api.ResumableInferenceBackend;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.LetterLogitScorer;
import com.integrallis.models.decisions.Verdict;
import java.util.Arrays;
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

  /** The evidence tokens the current resumption point was captured after, if any. */
  private int[] evidenceTokens;

  /** The position to return to before reading a new criterion over the same evidence. */
  private ResumableInferenceBackend.Resumption evidencePoint;

  /**
   * The part of the prompt that precedes the lettered options: the evidence, then the criterion.
   *
   * <p>The criterion has to be here. Without {@code space.question()} every question about one
   * piece of evidence produced a byte-identical prompt, so the same probability came back for all
   * of them -- measured 0.233783 for five different questions about one contract, with nothing
   * failing, because a runtime that ignores the question still returns a well-formed distribution.
   *
   * @param space the declared answer space, whose question is the criterion
   * @param state the evidence the decision is made against
   * @return the prompt text up to but excluding the lettered options
   */
  static String evidencePrefix(AnswerSpace space, String state) {
    return state + "\n" + space.question() + "\n";
  }

  /**
   * Answers one decision in a single forward pass.
   *
   * <p>The options are rendered as lettered choices, the prompt is prefilled, and the letters'
   * logits at the final position are normalised over themselves. Every answer slot is verified to
   * be one exact round-trip token first; a slot that merges with neighbouring text would read the
   * wrong logit and fail silently rather than loudly.
   *
   * @param space the declared answer space, whose question is the criterion
   * @param state the evidence the decision is made against
   * @return a probability over exactly the declared options
   */
  public Verdict decide(AnswerSpace space, String state) {
    Objects.requireNonNull(space, "space");
    Objects.requireNonNull(state, "state");
    List<String> labels = space.labels();
    Tokenizer tokenizer = backend.tokenizer();

    String evidenceText = evidencePrefix(space, state);
    int[] prompt = tokenizer.encode(evidenceText + LetterLogitScorer.renderOptions(labels));
    int[] letterTokens = new int[labels.size()];
    for (int index = 0; index < labels.size(); index++) {
      int[] encoded = tokenizer.encode(" " + (char) ('A' + index));
      letterTokens[index] = encoded[encoded.length - 1];
    }

    // Each decision is independent: one state, one closed answer space, one forward pass. The
    // backend carries sequence state from whatever ran before it, so without returning it to a
    // known position a second decision prefills at position 0 against a session already past it
    // and fails with "position must be sequential". A runtime callable once is not a runtime.
    //
    // The evidence is usually the same across a batch of questions and the options are not, so the
    // evidence is read once and resumed, and only the options are read per decision. On a backend
    // that cannot resume this falls back to reading the whole prompt every time, which is correct
    // and slower.
    int[] evidence = tokenizer.encode(state);
    int[] optionTokens = Arrays.copyOfRange(prompt, evidence.length, prompt.length);
    boolean resumable =
        backend instanceof ResumableInferenceBackend resumableBackend
            && resumableBackend.supportsResumption()
            && optionTokens.length > 0
            && Arrays.equals(evidence, Arrays.copyOf(prompt, evidence.length));

    if (!resumable) {
      backend.reset();
      int last = prompt.length - 1;
      if (last > 0) {
        backend.prefill(Arrays.copyOf(prompt, last), 0);
      }
      return scorer.score(space, backend.forward(prompt[last], last), letterTokens);
    }

    ResumableInferenceBackend resumableBackend = (ResumableInferenceBackend) backend;
    if (evidencePoint == null || !Arrays.equals(evidence, evidenceTokens)) {
      backend.reset();
      backend.prefill(evidence, 0);
      evidenceTokens = evidence;
      evidencePoint = resumableBackend.capture();
    } else {
      resumableBackend.resume(evidencePoint);
    }

    int last = prompt.length - 1;
    if (optionTokens.length > 1) {
      backend.prefill(Arrays.copyOf(optionTokens, optionTokens.length - 1), evidence.length);
    }
    return scorer.score(space, backend.forward(prompt[last], last), letterTokens);
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
