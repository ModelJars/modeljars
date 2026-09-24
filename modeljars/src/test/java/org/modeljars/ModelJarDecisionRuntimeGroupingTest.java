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
    private int position;
    private int groupedCalls;
    private int evidencePrefills;
    private int resumes;

    private StubBackend(int breakEven) {
      this.breakEven = breakEven;
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
      return () -> captured;
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
