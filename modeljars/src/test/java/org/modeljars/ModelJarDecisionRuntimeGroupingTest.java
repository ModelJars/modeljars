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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.GroupedDecisionBackend;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.ResumableInferenceBackend;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.decisions.AnswerSpace;
import com.integrallis.models.decisions.Noul;
import com.integrallis.models.decisions.Verdict;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A GROUP MUST READ ITS EVIDENCE ONCE, AND ONLY WHEN GROUPING IS WORTH IT.
 *
 * <p>MEASURED 2026-09-24 against the shipped grouped path: it called {@code reset()} and re-read
 * the whole evidence on every grouped call, then discarded the resumption point so the next
 * one-at-a-time decision re-read it again. On a Hetzner CCX33 that evidence read cost 2.68 s
 * against 0.45 s for a question, so a group paid more for its evidence than for every question in
 * it, and nothing failed -- the answers were right and the runtime was slow. Call counts are
 * asserted here because wall time is not a test.
 */
final class ModelJarDecisionRuntimeGroupingTest {

  @Test
  void aGroupReadsTheEvidenceOnceAndLeavesItResumable() {
    StubBackend backend = new StubBackend(2);
    ModelJarDecisionRuntime runtime = runtime(backend);

    List<Verdict> verdicts = runtime.decideAll(spaces(10), EVIDENCE);

    assertEquals(10, verdicts.size());
    assertEquals(1, backend.groupedCalls, "a group of ten takes the grouped path");
    assertEquals(1, backend.evidencePrefills, "the evidence is read once, not once per question");

    // The resumption point must survive the group, or the next question pays for the evidence
    // again.
    runtime.decide(new Noul("Anything else?"), EVIDENCE);
    assertEquals(1, backend.evidencePrefills, "the group must leave the evidence resumable");
    assertTrue(backend.resumes >= 1, "the next question must resume, not re-read");
  }

  /**
   * QUESTIONS WITH DIFFERENT RUBRICS MUST STILL READ THE EVIDENCE ONCE.
   *
   * <p>MEASURED 2026-10-19 on an isolated 8-vCPU EPYC-Milan host over a 250-token ticket: three
   * questions carrying three rubrics took 16,497 ms against 6,343 ms for the same three sharing one
   * rubric, because the resumption point was captured after the evidence AND the rubric, so a
   * question whose rubric differed matched nothing and re-read the ticket. An extra question cost
   * 5,539 ms instead of 432 ms -- and a boolean, a choice and a score, each with its own written
   * rules, is exactly the shape a batch of real decisions has.
   */
  @Test
  void aBatchOfDifferentRubricsReadsTheEvidenceOnce() {
    StubBackend backend = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime runtime = runtime(backend);

    runtime.decideAll(mixedRubrics(), EVIDENCE);

    assertEquals(
        1,
        backend.coldPrefills,
        "three rubrics over one piece of evidence must read the evidence once");
    assertTrue(backend.resumes >= 2, "the second and third questions must resume, not re-read");
  }

  /**
   * A CALLER ASKING ONE QUESTION AT A TIME CANNOT BE ASKED TO KNOW ITS OWN FUTURE.
   *
   * <p>{@code decideAll} sees every rubric before it answers anything, so it can put the point in
   * the right place. {@code decide} cannot, so it starts where a single question is cheapest and
   * moves once a second rubric over the same evidence proves the questions are unlike. The move
   * costs one more read of the evidence, once, instead of one per question forever.
   */
  @Test
  void repeatedSingleQuestionsStopReReadingTheEvidenceAfterTheFirstRubricChange() {
    StubBackend backend = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime runtime = runtime(backend);

    for (AnswerSpace space : mixedRubrics()) {
      runtime.decide(space, EVIDENCE);
    }

    assertEquals(
        2,
        backend.coldPrefills,
        "the second question pays to move the point; the third must not pay again");
  }

  /** Only one resumption point is held, because each one retains 98.3 MiB of linear state. */
  @Test
  void onlyOneResumptionPointIsHeldAtATime() {
    StubBackend backend = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime runtime = runtime(backend);

    runtime.decideAll(mixedRubrics(), EVIDENCE);

    assertEquals(
        1, backend.liveCaptures(), "a second point would cost another copy of the linear state");
  }

  /**
   * RESUMING MUST NOT MOVE AN ANSWER.
   *
   * <p>The stub's logits are a function of the position the answer is read at, so a prefix that was
   * resumed at the wrong boundary -- or a criteria segment prefilled at the wrong offset -- changes
   * the distribution rather than failing. That is the failure this guards: the whole point of
   * resuming is that the backend ends up having read the same tokens at the same positions.
   */
  @Test
  void resumingTheEvidenceGivesTheSameAnswerAsReadingItWhole() {
    AnswerSpace first = new Noul("Is the amount stated?", "an amount appears", "none appears");
    AnswerSpace second = new Noul("Is it monthly?", "a period appears", "no period appears");

    StubBackend resumed = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime resumedRuntime = runtime(resumed);
    Verdict afterResuming = resumedRuntime.decideAll(List.of(first, second), EVIDENCE).get(1);

    StubBackend cold = new StubBackend(Integer.MAX_VALUE);
    Verdict fromCold = runtime(cold).decide(second, EVIDENCE);

    assertArrayEquals(
        fromCold.probabilities(),
        afterResuming.probabilities(),
        "a question answered after resuming must match the same question asked cold");
    assertEquals(1, resumed.coldPrefills, "and it must not have re-read the evidence to do it");
  }

  /**
   * The three questions the support demo asks: a boolean, a choice and a score, each with rules.
   */
  private static List<AnswerSpace> mixedRubrics() {
    return List.of(
        new Noul("Is the amount stated?", "an amount appears", "none appears"),
        new Noul("Is it monthly?", "a period appears", "no period appears"),
        new Noul("Is the payer named?", "a payer appears", "no payer"));
  }

  /**
   * A GROUP MUST NOT LEAVE A RESUMPTION POINT THAT NO LONGER EXISTS.
   *
   * <p>The grouped path resets the session, which clears the token history every capture is
   * validated against. A point held across that reset is a handle to nothing: the backend rejects
   * it on the next question whose evidence happens to match, which would turn a working runtime
   * into one that throws on its second batch.
   */
  @Test
  void aGroupedCallDoesNotLeaveAStaleStatePoint() {
    StubBackend backend = new StubBackend(2);
    ModelJarDecisionRuntime runtime = runtime(backend);

    runtime.decide(
        new Noul("Is the amount stated?", "an amount appears", "none appears"), EVIDENCE);
    runtime.decideAll(spaces(10), EVIDENCE);
    Verdict after =
        runtime.decide(
            new Noul("Is it monthly?", "a period appears", "no period appears"), EVIDENCE);

    assertEquals(2, after.probabilities().length, "the decision after a group still answers");
  }

  @Test
  void aGroupTooSmallToPayForItselfIsAnsweredOneAtATime() {
    StubBackend backend = new StubBackend(10);
    ModelJarDecisionRuntime runtime = runtime(backend);

    List<Verdict> verdicts = runtime.decideAll(spaces(2), EVIDENCE);

    assertEquals(2, verdicts.size());
    assertEquals(0, backend.groupedCalls, "two questions do not earn a lockstep walk");
    assertEquals(1, backend.evidencePrefills, "the evidence is still read only once");
  }

  /**
   * A backend that has not measured grouping must not be opted in for it.
   *
   * <p>MEASURED 2026-09-24: on Harriet's own native backend grouping is worth nothing once the
   * decode kernel is fast, so a default of "group whenever you can" would have shipped a path that
   * costs 2 GiB of branch state to buy run-to-run noise.
   */
  @Test
  void aBackendThatSaysGroupingNeverPaysIsNeverGrouped() {
    StubBackend backend = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime runtime = runtime(backend);

    assertEquals(20, runtime.decideAll(spaces(20), EVIDENCE).size());
    assertEquals(0, backend.groupedCalls, "the backend said grouping never pays");
  }

  /**
   * GROUPING MUST NOT CHANGE AN ANSWER.
   *
   * <p>MEASURED 2026-09-24 on the shipped Qwen3.5-4B: on the native backend a grouped answer and
   * the same question asked alone differ by 0.03 to 0.10 of probability, and on one of four
   * questions the winner itself changed. The cause is not the grouping logic -- a lone question
   * reads its answer out of a batch of one row and a group reads its out of a batch of many, and on
   * that backend those are separate kernels. The pure Java decoder, whose batched projection
   * computes each row exactly as its single-row projection would, is bit-identical. So a backend
   * that cannot promise this must not be grouped, however profitable grouping would be.
   */
  @Test
  void aBackendWhoseGroupedAnswersDifferIsNotGrouped() {
    StubBackend backend = new StubBackend(2, false);
    ModelJarDecisionRuntime runtime = runtime(backend);

    assertEquals(20, runtime.decideAll(spaces(20), EVIDENCE).size());
    assertEquals(
        0,
        backend.groupedCalls,
        "grouping was profitable here and still must not be taken: it changes answers");
  }

  /** The caller may accept an answer that moves, but only by saying so. */
  @Test
  void inexactGroupingCanBeAskedForExplicitly() {
    StubBackend backend = new StubBackend(2, false);
    ModelJarDecisionRuntime runtime = runtime(backend);
    String previous = System.getProperty(ModelJarDecisionRuntime.ALLOW_INEXACT_GROUPING_PROPERTY);
    System.setProperty(ModelJarDecisionRuntime.ALLOW_INEXACT_GROUPING_PROPERTY, "true");
    try {
      assertEquals(4, runtime.decideAll(spaces(4), EVIDENCE).size());
      assertEquals(1, backend.groupedCalls);
    } finally {
      if (previous == null) {
        System.clearProperty(ModelJarDecisionRuntime.ALLOW_INEXACT_GROUPING_PROPERTY);
      } else {
        System.setProperty(ModelJarDecisionRuntime.ALLOW_INEXACT_GROUPING_PROPERTY, previous);
      }
    }
  }

  @Test
  void theThresholdIsConfigurableBecauseItIsAPropertyOfTheMachine() {
    StubBackend backend = new StubBackend(Integer.MAX_VALUE);
    ModelJarDecisionRuntime runtime = runtime(backend);
    String previous = System.getProperty(ModelJarDecisionRuntime.MINIMUM_GROUP_SIZE_PROPERTY);
    System.setProperty(ModelJarDecisionRuntime.MINIMUM_GROUP_SIZE_PROPERTY, "2");
    try {
      assertEquals(2, runtime.decideAll(spaces(2), EVIDENCE).size());
      assertEquals(1, backend.groupedCalls, "the property must override the backend's answer");
    } finally {
      if (previous == null) {
        System.clearProperty(ModelJarDecisionRuntime.MINIMUM_GROUP_SIZE_PROPERTY);
      } else {
        System.setProperty(ModelJarDecisionRuntime.MINIMUM_GROUP_SIZE_PROPERTY, previous);
      }
    }
  }

  @Test
  void everyQuestionInAGroupGetsItsOwnAnswer() {
    StubBackend backend = new StubBackend(2);
    ModelJarDecisionRuntime runtime = runtime(backend);

    List<Verdict> verdicts = runtime.decideAll(spaces(10), EVIDENCE);

    // The stub answers from the question's own length, so identical verdicts would mean the
    // branches
    // were fed the same suffix -- the defect that once returned one probability for five questions.
    assertTrue(
        verdicts.stream().map(Verdict::confidence).distinct().count() > 1,
        "every branch answered identically, so they were fed the same suffix");
  }

  private static final String EVIDENCE = "Northwind pays 42,500 dollars monthly.";

  private static List<AnswerSpace> spaces(int count) {
    List<AnswerSpace> spaces = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      spaces.add(new Noul("Is item " + "x".repeat(index + 1) + " stated?"));
    }
    return List.copyOf(spaces);
  }

  /**
   * A CALLER ARRIVING FROM A HOSTED SYSTEM ONE MUST NOT HAVE TO RESTRUCTURE ITS CODE.
   *
   * <p>A System One call is a state plus named questions, answered back under the same names.
   * {@code decideAll} is positional, so porting such a caller meant flattening a map, tracking the
   * order and zipping the results back -- work that has nothing to do with the decision and that
   * the caller gets wrong silently when the two lists drift apart.
   *
   * <p>So {@code systemOne} has to answer every name, answer it with that name's own question, and
   * do no more work than the positional call it delegates to.
   */
  @Test
  void answersNamedQuestionsUnderTheirOwnNames() {
    Map<String, AnswerSpace> questions = new java.util.LinkedHashMap<>();
    questions.put("urgency", new Noul("Is this urgent?"));
    questions.put("billing", new Noul("Is this about an invoice?"));

    Map<String, Verdict> answers = runtime(new StubBackend(2)).systemOne(questions, EVIDENCE);

    assertEquals(questions.keySet(), answers.keySet(), "every name must be answered");

    // The map form is a shape, not a second implementation. Same questions in the same order
    // through the positional call must produce the same distributions, or one of the two paths is
    // doing something the other is not.
    List<Verdict> positional =
        runtime(new StubBackend(2)).decideAll(new ArrayList<>(questions.values()), EVIDENCE);
    int index = 0;
    for (String name : questions.keySet()) {
      Verdict mapped = answers.get(name);
      Verdict listed = positional.get(index++);
      assertEquals(
          listed.space().labels(), mapped.space().labels(), name + " must keep its own outcomes");
      for (String label : listed.space().labels()) {
        assertEquals(
            listed.probabilityOf(label),
            mapped.probabilityOf(label),
            name + " must be the same decision either way");
      }
    }
  }

  /** An empty map is a caller mistake, not a zero-answer success. */
  @Test
  void refusesAnEmptyQuestionMap() {
    ModelJarDecisionRuntime runtime = runtime(new StubBackend(Integer.MAX_VALUE));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> runtime.systemOne(Map.of(), "evidence"));
  }

  private static ModelJarDecisionRuntime runtime(StubBackend backend) {
    return new ModelJarDecisionRuntime(backend, descriptor(), qualification(), 1.0);
  }

  /**
   * A backend whose tokenizer is one token per character.
   *
   * <p>The runtime splits a prompt by tokenising the evidence alone and requiring it to be an exact
   * prefix of the whole prompt's tokens, and falls back when it is not. A character tokenizer makes
   * that hold by construction, so the test exercises the grouping decision rather than a tokenizer
   * quirk.
   */
  private static final class StubBackend
      implements ResumableInferenceBackend, GroupedDecisionBackend {
    private static final int VOCABULARY = 256;
    private final int breakEven;
    private final boolean exact;
    private int position;
    private int groupedCalls;
    private int evidencePrefills;
    private int coldPrefills;
    private int resumes;
    private final List<java.lang.ref.WeakReference<Resumption>> captures = new ArrayList<>();

    private StubBackend(int breakEven) {
      this(breakEven, true);
    }

    private StubBackend(int breakEven, boolean exact) {
      this.breakEven = breakEven;
      this.exact = exact;
    }

    @Override
    public String name() {
      return "stub";
    }

    @Override
    public ModelMetadata metadata() {
      return new ModelMetadata("stub", "stub", 4096, VOCABULARY, 8, 2, 2, 1);
    }

    @Override
    public Tokenizer tokenizer() {
      return new Tokenizer() {
        @Override
        public int[] encode(String text) {
          int[] tokens = new int[text.length()];
          for (int index = 0; index < text.length(); index++) {
            tokens[index] = text.charAt(index) % VOCABULARY;
          }
          return tokens;
        }

        @Override
        public String decode(int[] tokens) {
          StringBuilder text = new StringBuilder(tokens.length);
          for (int token : tokens) {
            text.append((char) token);
          }
          return text.toString();
        }

        @Override
        public String decode(int token) {
          return String.valueOf((char) token);
        }

        @Override
        public int vocabSize() {
          return VOCABULARY;
        }

        @Override
        public int bosToken() {
          return 1;
        }

        @Override
        public int eosToken() {
          return 2;
        }
      };
    }

    @Override
    public float[] prefill(int[] tokens, int startPosition) {
      if (startPosition == 0) {
        coldPrefills++;
      }
      if (startPosition == 0 && tokens.length == EVIDENCE.length()) {
        evidencePrefills++;
      }
      position = startPosition + tokens.length;
      return new float[VOCABULARY];
    }

    @Override
    public float[] forward(int token, int position) {
      this.position = position + 1;
      return logits(position);
    }

    @Override
    public void reset() {
      position = 0;
    }

    @Override
    public boolean supportsResumption() {
      return true;
    }

    @Override
    public Resumption capture() {
      int captured = position;
      Resumption point = () -> captured;
      captures.add(new java.lang.ref.WeakReference<>(point));
      return point;
    }

    /**
     * Captures this backend issued that are still reachable.
     *
     * <p>Each one retains a copy of the model's linear state on the real backend -- MEASURED
     * 2026-10-18 at 98.3 MiB on Harriet, flat in prefix length -- so how many are held at once is a
     * resource decision and belongs in a test.
     */
    private long liveCaptures() {
      System.gc();
      return captures.stream().filter(reference -> reference.get() != null).count();
    }

    @Override
    public void resume(Resumption resumption) {
      resumes++;
      position = resumption.position();
    }

    @Override
    public boolean supportsGroupedDecisions() {
      return true;
    }

    @Override
    public int maximumGroupSize() {
      return 32;
    }

    @Override
    public int groupedDecisionBreakEven() {
      return breakEven;
    }

    @Override
    public boolean groupedDecisionsMatchSingleDecisions() {
      return exact;
    }

    @Override
    public float[][] decideGrouped(int[][] suffixes) {
      groupedCalls++;
      float[][] logits = new float[suffixes.length][];
      for (int index = 0; index < suffixes.length; index++) {
        logits[index] = logits(position + suffixes[index].length);
      }
      return logits;
    }

    @Override
    public void close() {}

    /** Logits that depend on the prompt's length, so a wrong split shows up as a wrong answer. */
    private static float[] logits(int at) {
      float[] values = new float[VOCABULARY];
      Arrays.fill(values, -10.0f);
      values['A'] = at % 7;
      values['B'] = 1.0f;
      return values;
    }
  }

  private static ModelJarDescriptor descriptor() {
    return new ModelJarDescriptor(
        "stub",
        "stub://stub",
        ModelJarCoordinate.parse("org.modeljars.stub:stub:1.0.0-stub.1"),
        ModelVersion.parse("1.0.0"),
        "q4_k_m",
        "gguf",
        "qwen35",
        "Q4_K_M",
        Optional.of(Path.of("stub.gguf")),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of("Apache-2.0"),
        Set.of("decisions"),
        Set.of(),
        List.of(),
        Map.of("pure-java", true),
        Optional.of("Stub"),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        ModelDimensions.unknown());
  }

  private static ModelExecutionQualification qualification() {
    return new ModelExecutionQualification() {
      @Override
      public String modelId() {
        return "stub";
      }

      @Override
      public String model() {
        return "stub";
      }

      @Override
      public String backend() {
        return "pure-java";
      }

      @Override
      public String backendVersion() {
        return "0";
      }

      @Override
      public String workload() {
        return "decisions";
      }

      @Override
      public String promptTemplate() {
        return "none";
      }

      @Override
      public String artifactSha256() {
        return "a".repeat(64);
      }

      @Override
      public long artifactSizeBytes() {
        return 1L;
      }

      @Override
      public String reportPath() {
        return "report.json";
      }

      @Override
      public URI reportUri() {
        return URI.create("https://modeljars.org/report.json");
      }

      @Override
      public String reportSha256() {
        return "b".repeat(64);
      }

      @Override
      public String verdict() {
        return "QUALIFIED";
      }

      @Override
      public boolean qualified() {
        return true;
      }

      @Override
      public int attempts() {
        return 1;
      }

      @Override
      public double p95EndToEndMillis() {
        return 1.0;
      }

      @Override
      public boolean productionUsable() {
        return true;
      }

      @Override
      public boolean matches(ModelJarDescriptor candidate) {
        return true;
      }
    };
  }
}
