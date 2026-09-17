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
package org.modeljars.composite.granite;

import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.runtime.ActivatedToolCallingModel;
import com.integrallis.models.runtime.ActivatedToolTurn;
import com.integrallis.models.runtime.TokenConstraint;
import com.integrallis.models.runtime.chat.GraniteDocumentsPrompt;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.modeljars.ModelBackend;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarActivatedRuntime;
import org.modeljars.ModelJars;
import org.modeljars.ModelLoadOptions;

/**
 * The qualified Granite 4.1 3B base and Integrallis answerability activated-LoRA specialist as one
 * hybrid that loads the base weights once and forks both branches from the same physical KV prefix.
 *
 * <p>This recipe exists because the runtime's public API renders conversations, not the retrieval
 * envelope this specialist was trained on. The specialist decides whether the last user question is
 * answerable from the supplied documents, so every request carries a {@code <documents>} system
 * turn; {@link #render(Request)} is the single place that envelope is built, exactly as the frozen
 * qualification window built it, and {@link #classify} is the single place its output is read.
 *
 * <p>Both members are ordinary ModelJars markers resolved from Maven Central. The specialist is a
 * composition component: it is not a standalone model and cannot be opened without this base.
 *
 * <p>The hybrid runs on the Models Rust/FFM native backend, which is the only backend the base is
 * qualified on. Callers must therefore launch the JVM with {@code
 * --enable-native-access=ALL-UNNAMED}; {@link #open()} fails closed without it.
 */
public final class GraniteAnswerability {

  /** Exact qualified base-member marker. */
  public static final ModelJar BASE =
      ModelJar.of(
          "org.modeljars.huggingface:ibm-granite.granite-4.1-3b-gguf.q4_k_m:4.1.0-q4_k_m.2");

  /** Exact verified specialist-component marker. */
  public static final ModelJar SPECIALIST =
      ModelJar.of(
          "org.modeljars.github:modeljars.activated-adapters."
              + "granite-4.1-3b-answerability-alora-integrallis.f32:1.0.0-f32.1");

  /**
   * Completion budget the frozen answerability window used: the contract is one JSON string label,
   * so six greedy tokens are enough for either label plus its quotes.
   */
  public static final int MAX_COMPLETION_TOKENS = 6;

  /** SHA-256 of the frozen qualification window (v2) both arms are measured on. */
  public static final String WINDOW_SHA256 =
      "dfb8cd7203418c79bae27306ffc4857f0ab02be7f9f8ddcae793af556e9cab37";

  /** Greedy decoding with the frozen window's completion budget. */
  public static final SamplingOptions OPTIONS =
      SamplingOptions.builder().temperature(0).maxTokens(MAX_COMPLETION_TOKENS).build();

  /**
   * Controlled clean-host qualification evidence for this exact topology and artifact pair.
   *
   * <p>TODO-COMPOSITION-RUN: empty until the composition clean host has run. Fill it from {@code
   * composition-clean-host-run.json} with exactly the numbers the catalog entry and the evidence
   * report bind, and flip {@code retainsTheCleanHostMeasurements} in the module's tests from
   * asserting absence to asserting those values.
   */
  public static final Optional<Qualification> QUALIFICATION = Optional.empty();

  private GraniteAnswerability() {}

  /**
   * Downloads, verifies, and opens both members as one activated hybrid on the native backend.
   *
   * <p>The base is qualified on {@code rust-ffm} and not on pure Java, so this recipe requests
   * {@link ModelBackend#NATIVE} explicitly rather than leaving the choice to automatic selection.
   * The runtime fails closed if the base has no qualified native execution, if the component's
   * evidence binds a different backend, or if either member is not the verified artifact the
   * component evidence bound.
   *
   * <p>The native backend is a Foreign Function &amp; Memory downcall, so callers must launch the
   * JVM with {@code --enable-native-access=ALL-UNNAMED}; without it the runtime refuses to open
   * rather than warning.
   *
   * @return lifecycle-owning activated runtime
   */
  public static ModelJarActivatedRuntime open() {
    return ModelJars.openActivatedToolRuntime(
        BASE, SPECIALIST, ModelLoadOptions.builder().backend(ModelBackend.NATIVE).build());
  }

  /**
   * Renders the retrieval envelope the specialist was qualified on.
   *
   * <p>The documents become the Granite {@code <documents>} system turn, each conversation turn is
   * appended in order, and the prompt ends with the assistant marker that is the adapter's
   * activation boundary. The rendered text is byte-identical to the frozen window's specialist arm.
   *
   * @param request documents and conversation to classify
   * @return rendered prompt ending at the activation boundary
   */
  public static ModelPrompt render(Request request) {
    Objects.requireNonNull(request, "request");
    ModelPrompt.Builder prompt =
        GraniteDocumentsPrompt.appendSystem(ModelPrompt.builder(), request.documents(), null);
    for (Turn turn : request.conversation()) {
      GraniteDocumentsPrompt.appendTurn(prompt, turn.role(), turn.text());
    }
    return GraniteDocumentsPrompt.finish(prompt);
  }

  /**
   * Classifies one request on an open hybrid.
   *
   * @param runtime open hybrid
   * @param request documents and conversation to classify
   * @param prefix how the base context before the activation boundary is obtained
   * @return the specialist's verdict with the sharing facts of the turn that produced it
   */
  public static Verdict classify(ModelJarActivatedRuntime runtime, Request request, Prefix prefix) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(prefix, "prefix");
    ModelPrompt prompt = render(request);
    long started = System.nanoTime();
    String output;
    boolean shared;
    int sharedTokens;
    try (ActivatedToolTurn turn = runtime.model().openToolTurn(prompt, prefix.strategy())) {
      output = turn.generateToolCall(OPTIONS, TokenConstraint.unrestricted());
      shared = turn.physicallySharesPrefix();
      sharedTokens = turn.sharedPrefixTokens();
    }
    long millis = (System.nanoTime() - started) / 1_000_000L;
    return new Verdict(Answerability.parse(output), output, shared, sharedTokens, millis);
  }

  /** How the base context before the activation boundary is obtained. */
  public enum Prefix {
    /** Evaluate the prefix once and fork both branches over the same physical KV storage. */
    SHARED(ActivatedToolCallingModel.PrefixStrategy.SHARED),
    /** Evaluate the same base prefix independently, without shared physical KV storage. */
    RECOMPUTED(ActivatedToolCallingModel.PrefixStrategy.RECOMPUTED),
    /** Share only once the prompt reaches the component's measured crossover. */
    AUTOMATIC(ActivatedToolCallingModel.PrefixStrategy.AUTO);

    private final ActivatedToolCallingModel.PrefixStrategy strategy;

    Prefix(ActivatedToolCallingModel.PrefixStrategy strategy) {
      this.strategy = strategy;
    }

    /**
     * Returns the runtime prefix strategy this policy selects.
     *
     * @return the runtime prefix strategy this policy selects
     */
    public ActivatedToolCallingModel.PrefixStrategy strategy() {
      return strategy;
    }
  }

  /** The specialist's contract: exactly one JSON string label, or nothing it is allowed to mean. */
  public enum Answerability {
    /** The last user question is answerable from the supplied documents. */
    ANSWERABLE,
    /** The last user question is not answerable from the supplied documents. */
    UNANSWERABLE,
    /** The completion was not one of the two contracted labels. */
    UNSTRUCTURED;

    /** The exact completion the qualification treats as the answerable label. */
    public static final String ANSWERABLE_OUTPUT = "\"answerable\"";

    /** The exact completion the qualification treats as the unanswerable label. */
    public static final String UNANSWERABLE_OUTPUT = "\"unanswerable\"";

    /**
     * Reads one completion under the contract, without guessing.
     *
     * @param output raw completion, possibly {@code null}
     * @return the contracted label, or {@link #UNSTRUCTURED}
     */
    public static Answerability parse(String output) {
      String trimmed = output == null ? "" : output.strip();
      if (ANSWERABLE_OUTPUT.equals(trimmed)) {
        return ANSWERABLE;
      }
      if (UNANSWERABLE_OUTPUT.equals(trimmed)) {
        return UNANSWERABLE;
      }
      return UNSTRUCTURED;
    }
  }

  /**
   * One conversation turn of an answerability request.
   *
   * @param role {@code user} or {@code assistant}
   * @param text turn text
   */
  public record Turn(String role, String text) {

    /** Validates the turn. */
    public Turn {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(text, "text");
      if (!"user".equals(role) && !"assistant".equals(role)) {
        throw new IllegalArgumentException("role must be user or assistant, not " + role);
      }
      if (text.isBlank()) {
        throw new IllegalArgumentException("turn text must not be blank");
      }
    }

    /**
     * Returns a user turn.
     *
     * @param text turn text
     * @return a user turn
     */
    public static Turn user(String text) {
      return new Turn("user", text);
    }

    /**
     * Returns an assistant turn.
     *
     * @param text turn text
     * @return an assistant turn
     */
    public static Turn assistant(String text) {
      return new Turn("assistant", text);
    }
  }

  /**
   * One answerability request: the retrieved evidence and the conversation it must be judged over.
   *
   * @param documents retrieved document texts, in retrieval order
   * @param conversation conversation turns, ending with the user question under judgement
   */
  public record Request(List<String> documents, List<Turn> conversation) {

    /** Validates and defensively copies the request. */
    public Request {
      Objects.requireNonNull(documents, "documents");
      Objects.requireNonNull(conversation, "conversation");
      documents = List.copyOf(documents);
      conversation = List.copyOf(conversation);
      if (documents.isEmpty()) {
        throw new IllegalArgumentException(
            "an answerability request carries at least one document");
      }
      if (documents.stream().anyMatch(String::isBlank)) {
        throw new IllegalArgumentException("document texts must not be blank");
      }
      if (conversation.isEmpty() || !"user".equals(conversation.getLast().role())) {
        throw new IllegalArgumentException("the conversation must end with a user turn");
      }
    }

    /**
     * Returns a single-turn request.
     *
     * @param documents retrieved document texts, in retrieval order
     * @param question the user question under judgement
     * @return a single-turn request
     */
    public static Request of(List<String> documents, String question) {
      return new Request(documents, List.of(Turn.user(question)));
    }
  }

  /**
   * Immutable clean-host measurements retained with the qualified recipe.
   *
   * <p>Both arms run the same frozen window prompts on one loaded hybrid. The control arm
   * recomputes the base prefix for every specialist call; the composite arm forks both branches
   * from one physical prefix. {@code sharedUniqueStateBytes} and {@code recomputedUniqueStateBytes}
   * are the inference-state bytes unique to a turn under each arm, which is what physical sharing
   * is supposed to reduce.
   *
   * @param modelsRevision exact Models source revision the published runtime was built from
   * @param modeljarsVersion exact ModelJars runtime version resolved from Maven Central
   * @param casesPerArm number of frozen-window cases measured in each arm
   * @param controlMedianMillis median end-to-end latency for the control arm
   * @param compositeMedianMillis median end-to-end latency for the composite arm
   * @param peakRssBytes peak resident memory of the measured process
   * @param sharedUniqueStateBytes median unique inference-state bytes under physical sharing
   * @param recomputedUniqueStateBytes median unique inference-state bytes when recomputed
   */
  public record Qualification(
      String modelsRevision,
      String modeljarsVersion,
      int casesPerArm,
      long controlMedianMillis,
      long compositeMedianMillis,
      long peakRssBytes,
      long sharedUniqueStateBytes,
      long recomputedUniqueStateBytes) {

    /** Validates the measurements. */
    public Qualification {
      Objects.requireNonNull(modelsRevision, "modelsRevision");
      Objects.requireNonNull(modeljarsVersion, "modeljarsVersion");
      if (casesPerArm <= 0) {
        throw new IllegalArgumentException("casesPerArm must be > 0");
      }
      if (controlMedianMillis <= 0 || compositeMedianMillis <= 0) {
        throw new IllegalArgumentException("medians must be > 0");
      }
      if (peakRssBytes <= 0 || sharedUniqueStateBytes <= 0 || recomputedUniqueStateBytes <= 0) {
        throw new IllegalArgumentException("memory accounting must be complete and positive");
      }
    }

    /**
     * Returns the measured end-to-end latency improvement as a fraction.
     *
     * @return fractional latency improvement over the control arm
     */
    public double latencyImprovement() {
      return (controlMedianMillis - compositeMedianMillis) / (double) controlMedianMillis;
    }

    /**
     * Returns whether physical sharing reduced the unique inference-state bytes.
     *
     * @return whether physical sharing reduced the unique inference-state bytes
     */
    public boolean reducesUniqueInferenceState() {
      return sharedUniqueStateBytes < recomputedUniqueStateBytes;
    }
  }

  /**
   * One classification and the sharing facts of the turn that produced it.
   *
   * @param answerability the contracted label, or {@link Answerability#UNSTRUCTURED}
   * @param output the exact raw completion
   * @param physicallySharesPrefix whether the turn forked from shared physical KV storage
   * @param sharedPrefixTokens tokens before the activation boundary
   * @param millis wall-clock milliseconds for the turn
   */
  public record Verdict(
      Answerability answerability,
      String output,
      boolean physicallySharesPrefix,
      int sharedPrefixTokens,
      long millis) {

    /** Validates the verdict. */
    public Verdict {
      Objects.requireNonNull(answerability, "answerability");
      Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether the completion honoured the specialist's output contract.
     *
     * @return whether the completion honoured the specialist's output contract
     */
    public boolean structured() {
      return answerability != Answerability.UNSTRUCTURED;
    }
  }
}
