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

import com.integrallis.models.api.GroupedDecisionBackend;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.ResumableInferenceBackend;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.LetterLogitScorer;
import com.integrallis.models.decisions.Verdict;
import java.util.ArrayList;
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

  /**
   * Overrides the smallest group worth answering together rather than one question at a time.
   *
   * <p>Unset, the backend is asked, because the answer is a property of the backend and not of this
   * runtime: grouping replaces the single-token step that ends each question with one step for the
   * whole group, so it is worth exactly what that step costs. See {@link
   * GroupedDecisionBackend#groupedDecisionBreakEven()} for the measurements.
   *
   * <p>MEASURED 2026-09-24, Harriet on a Hetzner CCX33 (8 vCPU, EPYC Milan, 4 physical cores) over
   * a 143-token contract, twenty questions: 8.95 s one at a time against 8.63 s grouped with the
   * native decode kernel on -- inside the +-5% run-to-run band at every group size from ten to
   * thirty. With that kernel off the same grouping is worth 1.69x. Set this to force either way and
   * measure; a box with more cores, a smaller model or a slower decode moves the answer.
   */
  static final String MINIMUM_GROUP_SIZE_PROPERTY = "modeljars.decisions.minimumGroupSize";

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
   * Answers several questions about one piece of evidence, reading the evidence once.
   *
   * <p>This is the shape a batch of decisions actually has: one state, many criteria. The evidence
   * is prefilled once and resumed for each question, so the cost of the evidence is paid once
   * rather than once per question, and only the criterion and its options are read per answer.
   *
   * <p>MEASURED 2026-09-24 on a Hetzner CCX33 over a 143-token contract: the first question costs a
   * full read of the evidence, 2.68 s, and each one after it costs 0.45 s. Answers are
   * bit-identical to asking each question against a cold prefill.
   *
   * <p>A backend that says grouping pays off at this size answers the group in lockstep instead;
   * see {@link #MINIMUM_GROUP_SIZE_PROPERTY} for the measurements and how to force either path.
   *
   * @param spaces the declared answer spaces, answered in order
   * @param state the evidence every question is asked against
   * @return one verdict per space, in the order given
   */
  public List<Verdict> decideAll(List<AnswerSpace> spaces, String state) {
    Objects.requireNonNull(spaces, "spaces");
    Objects.requireNonNull(state, "state");
    if (spaces.isEmpty()) {
      throw new IllegalArgumentException("spaces must not be empty");
    }
    List<Verdict> grouped = decideGroupedIfSupported(spaces, state);
    if (grouped != null) {
      return grouped;
    }
    List<Verdict> verdicts = new ArrayList<>(spaces.size());
    for (AnswerSpace space : spaces) {
      verdicts.add(decide(space, state));
    }
    return List.copyOf(verdicts);
  }

  /**
   * Answers the group in one weight sweep, or returns null if this backend cannot.
   *
   * <p>MEASURED 2026-09-24, Harriet on a Hetzner CCX33: twenty questions cost 8.95 s one at a time
   * and 8.63 s together, which is inside the run-to-run band. Batched prefill on that box is
   * compute bound -- 18.5 ms per token, linear, saturating at four threads -- so the group does the
   * same arithmetic either way. What it saves is the bandwidth-bound single-token step that ends
   * each question, one per group rather than one per question, and with the native decode kernel
   * that step is already cheap. Without it the same grouping is worth 1.69x, which is why the
   * backend and not this method decides whether to take this path.
   *
   * <p>Answers also differ slightly between the two paths, by up to 0.04 of probability. That is
   * not this method's doing: a question prefilled as one batch already disagrees with the same
   * question fed a token at a time by as much, because the two take different matrix kernels. A
   * caller that needs bit-identical answers must pick one path and stay on it.
   */
  private List<Verdict> decideGroupedIfSupported(List<AnswerSpace> spaces, String state) {
    if (!(backend instanceof GroupedDecisionBackend groupedBackend)
        || !groupedBackend.supportsGroupedDecisions()
        || spaces.size() < minimumGroupSize(groupedBackend)
        || spaces.size() > groupedBackend.maximumGroupSize()) {
      return null;
    }
    Tokenizer tokenizer = backend.tokenizer();
    int[] evidence = tokenizer.encode(state);
    int[][] suffixes = new int[spaces.size()][];
    List<int[]> letterTokens = new ArrayList<>(spaces.size());
    for (int index = 0; index < spaces.size(); index++) {
      AnswerSpace space = spaces.get(index);
      List<String> labels = space.labels();
      int[] prompt =
          tokenizer.encode(evidencePrefix(space, state) + LetterLogitScorer.renderOptions(labels));
      if (prompt.length <= evidence.length
          || !Arrays.equals(evidence, Arrays.copyOf(prompt, evidence.length))) {
        // Tokenising the evidence alone did not reproduce the prompt's leading tokens, so the
        // split is not safe to make. Fall back rather than answer a prompt nobody asked for.
        return null;
      }
      suffixes[index] = Arrays.copyOfRange(prompt, evidence.length, prompt.length);
      int[] letters = new int[labels.size()];
      for (int slot = 0; slot < labels.size(); slot++) {
        int[] encoded = tokenizer.encode(" " + (char) ('A' + slot));
        letters[slot] = encoded[encoded.length - 1];
      }
      letterTokens.add(letters);
    }

    // The same evidence capture the one-at-a-time path uses. MEASURED 2026-09-24 on an 8-vCPU
    // EPYC-Milan box: re-reading 143 tokens of evidence costs 2.68 s, against 0.32 s for a
    // 17-token question, so a group that re-read its evidence paid more for the evidence than for
    // every question in it -- and discarding the capture made the next one-at-a-time decision pay
    // it again.
    ResumableInferenceBackend resumable =
        backend instanceof ResumableInferenceBackend candidate && candidate.supportsResumption()
            ? candidate
            : null;
    if (resumable == null) {
      backend.reset();
      backend.prefill(evidence, 0);
      evidenceTokens = null;
      evidencePoint = null;
    } else if (evidencePoint == null || !Arrays.equals(evidence, evidenceTokens)) {
      backend.reset();
      backend.prefill(evidence, 0);
      evidenceTokens = evidence;
      evidencePoint = resumable.capture();
    } else {
      resumable.resume(evidencePoint);
    }
    float[][] logits = groupedBackend.decideGrouped(suffixes);

    List<Verdict> verdicts = new ArrayList<>(spaces.size());
    for (int index = 0; index < spaces.size(); index++) {
      verdicts.add(scorer.score(spaces.get(index), logits[index], letterTokens.get(index)));
    }
    return List.copyOf(verdicts);
  }

  /** The smallest group worth answering together, from the backend unless overridden. */
  private static int minimumGroupSize(GroupedDecisionBackend backend) {
    String configured = System.getProperty(MINIMUM_GROUP_SIZE_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return Math.max(2, backend.groupedDecisionBreakEven());
    }
    try {
      return Math.max(2, Integer.parseInt(configured.trim()));
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(
          MINIMUM_GROUP_SIZE_PROPERTY + " must be an integer: " + configured, failure);
    }
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
