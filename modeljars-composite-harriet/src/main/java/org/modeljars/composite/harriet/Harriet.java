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
package org.modeljars.composite.harriet;

import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Choice;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Score;
import com.integrallis.models.decisions.Verdict;
import java.util.List;
import java.util.Objects;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarDecisionRuntime;
import org.modeljars.ModelJars;

/**
 * Harriet: typed, calibrated decisions from open weights, in one forward pass.
 *
 * <p>A decision is a whole question -- evidence, a criterion, and a closed set of outcomes --
 * answered with a probability distribution over exactly those outcomes. Harriet never generates, so
 * there is no JSON to repair, no parse to fail, and no decoding loop to bound.
 *
 * <p>The weights are {@link #BASE}, frozen and Apache-2.0. Harriet trains nothing: it is a prompt,
 * a readout and a calibration over someone else's model. The readout reads the last-position logits
 * at the tokens standing for the declared options and normalises over just those.
 *
 * <p>Named for Harriet Ann Jevons, who edited and published her husband's letters and journal in
 * 1886 after his death. He built the Logic Piano, the first machine to solve logical inference
 * mechanically; she is the reason the work reached anyone.
 *
 * <h2>Measured</h2>
 *
 * <p>JevBench v1.2 public cohort, 231 decisions, on a dedicated 8-vCPU EPYC-Milan host, with <b>no
 * temperature fitted on the benchmark</b>: Intelligence 73.3, Calibration 74.1, Speed 63.0, Cost
 * 61.7, composite 67.8. Per tier: easy 1.0000, standard 0.8056, hard 0.5405; hard-tier ECE 0.1295.
 * Evidence and reproduction: <a
 * href="https://github.com/integrallis/harriet">integrallis/harriet</a>.
 */
public final class Harriet {

  /** Exact qualified base marker. The weights are frozen and are not ours. */
  public static final ModelJar BASE =
      ModelJar.of("org.modeljars.huggingface:unsloth.qwen3.5-4b-gguf.q4_k_m:3.5.0-q4_k_m.2");

  private Harriet() {}

  /**
   * Opens Harriet on the qualified base, with no calibration temperature.
   *
   * <p>The base is measured as natively calibrated on the hard tier, so the published number uses
   * no fitted temperature. A temperature fitted on an evaluation set is not a measurement of
   * anything, and one fitted elsewhere does not transfer; if you have your own corpus, fit on that
   * and pass it to {@link #open(double)}.
   *
   * @return a lifecycle-owning decision runtime
   */
  public static ModelJarDecisionRuntime open() {
    return ModelJars.openDecisionRuntime(BASE);
  }

  /**
   * Opens Harriet with a calibration temperature fitted on your own held-out corpus.
   *
   * @param temperature calibration temperature, 1.0 for none
   * @return a lifecycle-owning decision runtime
   */
  public static ModelJarDecisionRuntime open(double temperature) {
    return ModelJars.openDecisionRuntime(
        BASE, org.modeljars.ModelLoadOptions.defaults(), temperature);
  }

  /**
   * Answers a yes-or-no proposition.
   *
   * @param runtime an open Harriet runtime
   * @param question the proposition being judged
   * @param state the evidence it is judged against
   * @return a probability over yes and no
   */
  public static Verdict noul(ModelJarDecisionRuntime runtime, String question, String state) {
    Objects.requireNonNull(runtime, "runtime");
    return runtime.decide(new Noul(question), state);
  }

  /**
   * Answers a categorical decision over unordered options.
   *
   * @param runtime an open Harriet runtime
   * @param question the question being answered
   * @param options between two and 255 distinct outcomes, in declaration order
   * @param state the evidence the decision is made against
   * @return a probability over exactly those options
   */
  public static Verdict choice(
      ModelJarDecisionRuntime runtime, String question, List<String> options, String state) {
    Objects.requireNonNull(runtime, "runtime");
    return runtime.decide(new Choice(question, options), state);
  }

  /**
   * Answers an ordered rating.
   *
   * @param runtime an open Harriet runtime
   * @param question the question being answered
   * @param levels between two and ten ordered levels, lowest first
   * @param state the evidence the rating is made against
   * @return a probability over exactly those levels
   */
  public static Verdict score(
      ModelJarDecisionRuntime runtime, String question, List<String> levels, String state) {
    Objects.requireNonNull(runtime, "runtime");
    return runtime.decide(new Score(question, levels), state);
  }

  /**
   * Answers several questions about one piece of evidence, reading the evidence once.
   *
   * <p>One state, many criteria, which is the shape a batch of decisions actually has. The evidence
   * is prefilled once and resumed per question, so its cost is paid once rather than once per
   * question.
   *
   * @param runtime the decision runtime holding the frozen base
   * @param spaces the declared answer spaces, answered in order
   * @param state the evidence every question is asked against
   * @return one verdict per space, in the order given
   */
  public static List<Verdict> decideAll(
      ModelJarDecisionRuntime runtime, List<AnswerSpace> spaces, String state) {
    Objects.requireNonNull(runtime, "runtime");
    return runtime.decideAll(spaces, state);
  }

  /**
   * Scores an answer space built by the caller, for spaces the named helpers do not cover.
   *
   * @param runtime the decision runtime holding the frozen base
   * @param space the closed set of outcomes the verdict is a distribution over
   * @param state the evidence the decision is made against
   * @return a probability over exactly the outcomes {@code space} names
   */
  public static Verdict decide(ModelJarDecisionRuntime runtime, AnswerSpace space, String state) {
    Objects.requireNonNull(runtime, "runtime");
    return runtime.decide(space, state);
  }
}
