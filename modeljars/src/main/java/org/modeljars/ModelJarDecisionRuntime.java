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

  /**
   * Allows grouping on a backend where it does not give bit-identical answers.
   *
   * <p>Grouping is meant to be a scheduling choice. On a backend whose single-row and multi-row
   * kernels are separate code, it is not: MEASURED 2026-09-24 on Harriet's native backend, a
   * grouped answer and the same question asked alone differ by 0.03 to 0.10 of probability, because
   * one reads its answer out of a batch of one row and the other out of a batch of many, and those
   * round differently in a way a 32-layer model amplifies about a hundred thousand times.
   *
   * <p>So by default a group is only taken when the backend states the two agree exactly. A caller
   * who wants the throughput and accepts a second decimal place that moves can set this, but it has
   * to be said out loud -- the failure is silent, well-formed, and looks exactly like an answer.
   */
  static final String ALLOW_INEXACT_GROUPING_PROPERTY = "modeljars.decisions.allowInexactGrouping";

  /** The evidence tokens the current resumption point was captured after, if any. */
  private int[] evidenceTokens;

  /** The position to return to before reading a new criterion over the same evidence. */
  private ResumableInferenceBackend.Resumption evidencePoint;

  /**
   * The part of the prompt that does not vary between questions: the evidence, then the rubric.
   *
   * <p>This is prefilled once and resumed for every question about the same evidence and answer
   * space, so what is in it is paid for once and what follows it is paid for per question. MEASURED
   * 2026-09-24 the tokens after it cost 18.5 ms each, so the split is the whole of the per-question
   * latency.
   *
   * <p>The rubric belongs here and the lettered options do not, and that is measured rather than
   * reasoned. Over 120 JevBench items: rubric and letters both after the criterion scored
   * Intelligence 86.1, both before it 79.6, and the rubric before with the letters after
   * <b>88.9</b> -- the best of the four and also the cheapest, because the rubric is most of the
   * added tokens. A model wants the letter-to-label mapping after the question it answers, and is
   * content to have read what the labels mean beforehand.
   *
   * @param space the declared answer space, whose rubric is shared across criteria
   * @param state the evidence the decision is made against
   * @return the prompt text every question about this evidence and space begins with
   */
  static String sharedPrefix(AnswerSpace space, String state) {
    String criteria = LetterLogitScorer.renderCriteria(space.labels(), space.criteria());
    return criteria.isEmpty() ? state : state + "\n" + criteria;
  }

  /**
   * The part of the prompt that varies: the criterion, then the lettered options and the cue.
   *
   * <p>The criterion has to be here. Without {@code space.question()} every question about one
   * piece of evidence produced a byte-identical prompt, so the same probability came back for all
   * of them -- measured 0.233783 for five different questions about one contract, with nothing
   * failing, because a runtime that ignores the question still returns a well-formed distribution.
   *
   * @param space the declared answer space, whose question is the criterion
   * @return the prompt text that follows the shared prefix
   */
  static String questionSuffix(AnswerSpace space) {
    return "\n" + space.question() + "\n" + LetterLogitScorer.renderOptions(space.labels());
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
   * Answers the group in lockstep, or returns null if that is not worth it on this backend.
   *
   * <p>MEASURED 2026-09-24, Harriet on a Hetzner CCX33: twenty questions cost 8.95 s one at a time
   * and 8.63 s together, which is inside the run-to-run band. Batched prefill on that box is
   * compute bound -- 18.5 ms per token, linear, saturating at four threads -- so the group does the
   * same arithmetic either way. What it saves is the bandwidth-bound single-token step that ends
   * each question, one per group rather than one per question, and with the native decode kernel
   * that step is already cheap. Without it the same grouping is worth 1.69x, which is why the
   * backend and not this method decides whether to take this path.
   *
   * <p>Grouping must not change an answer, and on some backends it does. A group reads its answer
   * out of a batch of rows and a lone question reads its answer out of a batch of one; where those
   * are separate kernels they round differently, and MEASURED 2026-09-24 on Harriet's native
   * backend that comes to 0.03 to 0.10 of probability. So this path is only taken when the backend
   * states the two agree exactly, unless {@link #ALLOW_INEXACT_GROUPING_PROPERTY} says otherwise.
   */
  private List<Verdict> decideGroupedIfSupported(List<AnswerSpace> spaces, String state) {
    if (!(backend instanceof GroupedDecisionBackend groupedBackend)
        || !groupedBackend.supportsGroupedDecisions()
        || !groupingIsAnswerPreserving(groupedBackend)
        || spaces.size() < minimumGroupSize(groupedBackend)
        || spaces.size() > groupedBackend.maximumGroupSize()) {
      return null;
    }
    Tokenizer tokenizer = backend.tokenizer();
    // Every space in the group must share one prefix, so a group of mixed rubrics cannot be
    // grouped. Falling back is correct and the caller sees the same answers either way.
    String shared = sharedPrefix(spaces.get(0), state);
    for (AnswerSpace space : spaces) {
      if (!shared.equals(sharedPrefix(space, state))) {
        return null;
      }
    }
    int[] evidence = tokenizer.encode(shared);
    int[][] suffixes = new int[spaces.size()][];
    List<int[]> letterTokens = new ArrayList<>(spaces.size());
    for (int index = 0; index < spaces.size(); index++) {
      AnswerSpace space = spaces.get(index);
      List<String> labels = space.labels();
      int[] prompt = tokenizer.encode(shared + questionSuffix(space));
      if (prompt.length <= evidence.length
          || !Arrays.equals(evidence, Arrays.copyOf(prompt, evidence.length))) {
        // Tokenising the shared prefix alone did not reproduce the prompt's leading tokens, so the
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

  /** Whether grouping on this backend is guaranteed to return the same answers. */
  private static boolean groupingIsAnswerPreserving(GroupedDecisionBackend backend) {
    return backend.groupedDecisionsMatchSingleDecisions()
        || Boolean.getBoolean(ALLOW_INEXACT_GROUPING_PROPERTY);
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

    String shared = sharedPrefix(space, state);
    int[] prompt = tokenizer.encode(shared + questionSuffix(space));
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
    // The evidence and the rubric are the same across a batch of questions about one space and the
    // criterion is not, so the shared part is read once and resumed and only the criterion and the
    // letters are read per decision. On a backend that cannot resume this falls back to reading the
    // whole prompt every time, which is correct and slower.
    int[] evidence = tokenizer.encode(shared);
    int[] optionTokens = Arrays.copyOfRange(prompt, evidence.length, prompt.length);
    boolean resumable =
        backend instanceof ResumableInferenceBackend resumableBackend
            && resumableBackend.supportsResumption()
            && optionTokens.length > 0
            && Arrays.equals(evidence, Arrays.copyOf(prompt, evidence.length));

    if (!resumable) {
      backend.reset();
      return scorer.score(space, backend.prefill(prompt, 0), letterTokens);
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

    // The whole suffix in one prefill, reading the answer off its final position, rather than
    // prefilling all but the last token and then stepping the last one on its own.
    //
    // The step that was removed is a single token read through every one of the 2.55 GiB of
    // weights:
    // MEASURED 2026-09-24 at 67 ms, and flat in thread count past two because it is bandwidth and
    // not arithmetic. As one more row of a batch that is already compute bound it costs 18.5 ms.
    // The
    // answer is read from the same position either way.
    return scorer.score(space, backend.prefill(optionTokens, evidence.length), letterTokens);
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
