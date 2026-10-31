//JAVA 25+
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS org.modeljars:modeljars:0.1.71
//DEPS org.modeljars.huggingface:ggml-org.flint-gguf.q4_k_m:1.0.0-q4_k_m.1

import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.GenerationUsage;
import com.integrallis.models.api.StopReason;
import com.integrallis.models.api.TokenStream;
import com.integrallis.models.runtime.GenerationMetrics;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.modeljars.ModelJars;

class ModelJarsDemo {
  private static final String MODEL = "org.modeljars.huggingface:ggml-org.flint-gguf.q4_k_m:1.0.0-q4_k_m.1";

  public static void main(String... args) {
    quietLibraries();
    var input = args.length == 0 ? "What is the capital of France? Reply with only the city name." : String.join(" ", args);
    long loadStarted = System.nanoTime();
    try (var runtime = ModelJars.openRuntime(MODEL)) {
      long loaded = System.nanoTime();
      var prompt = runtime.chatTemplate().render(List.of(ChatMessage.user(input)));
      int maxTokens = Integer.getInteger("modeljars.demo.maxTokens", 64);
      var options = SamplingOptions.builder().temperature(0).maxTokens(maxTokens).build();
      if (Boolean.getBoolean("modeljars.demo.diagnostics")) {
        System.err.println("Runtime: " + runtime.pipeline().diagnostics());
        System.err.println("Java: " + System.getProperty("java.runtime.version"));
        System.err.println("Available processors: " + Runtime.getRuntime().availableProcessors());
      }

      int runs = Integer.getInteger("modeljars.demo.runs", 1);
      if (runs < 1) throw new IllegalArgumentException("modeljars.demo.runs must be positive");
      for (int run = 1; run <= runs; run++) {
        if (runs > 1) System.out.println("Run " + run + (run == 1 ? " (cold prompt)" : " (repeated prompt; warm cache)"));
        System.out.println("Input:  " + input);
        System.out.print("Output: ");
        System.out.flush();
        var answer = new AnswerStream(
            text -> { System.out.print(text); System.out.flush(); },
            Boolean.getBoolean("modeljars.demo.showReasoning"), prompt.text());
        runtime.pipeline().generate(prompt, options, answer);
        System.out.println();
        if (!answer.hasAnswer()) {
          System.err.println("No final answer was produced (" + answer.stopReason + ").");
        } else if (answer.stopReason != StopReason.EOS && answer.stopReason != StopReason.STOP_SEQUENCE) {
          System.err.println("Answer may be incomplete (" + answer.stopReason + ").");
        }
        printMetrics(runtime.pipeline().lastGenerationMetrics(), run == 1 ? loaded - loadStarted : 0);
        System.out.printf("  First visible answer %s%n", answer.firstAnswerMillis());
        System.out.printf("  Stop reason          %s%n", answer.stopReason);
        if (answer.reasoningSeen) System.out.println("  Generated-token metrics include reasoning.");
      }
    }
  }

  private static void printMetrics(GenerationMetrics metrics, long loadNanos) {
    String ttft = metrics.timeToFirstToken()
        .map(value -> String.format("%,d ms", value.toMillis())).orElse("n/a");
    System.out.printf("%nMetrics:%n");
    System.out.printf("  Load                 %,d ms%n", loadNanos / 1_000_000);
    System.out.printf("  Tokenization         %,d ms%n", metrics.tokenization().toMillis());
    System.out.printf("  Prompt preparation   %,d ms%n", metrics.promptPreparation().toMillis());
    System.out.printf("  Prefill              %,d ms%n", metrics.prefill().toMillis());
    System.out.printf("  First generated token %s%n", ttft);
    System.out.printf("  Decode               %,d ms (%.1f tokens/s)%n",
        metrics.decode().toMillis(), metrics.decodeTokensPerSecond());
    System.out.printf("  Tokens               %,d prompt + %,d completion%n",
        metrics.usage().promptTokens(), metrics.usage().completionTokens());
  }

  // Only leading, protocol-delimited reasoning is hidden. Literal tags in answers stay text.
  static final class AnswerStream implements TokenStream {
    private static final String[] OPEN = {"<think>", "<thinking>", "<reasoning>"};
    private static final String[] CLOSE = {"</think>", "</thinking>", "</reasoning>"};
    private final java.util.function.Consumer<String> output;
    private final boolean showReasoning;
    private final StringBuilder pending = new StringBuilder();
    private final java.util.ArrayDeque<String> closers = new java.util.ArrayDeque<>();
    private final long started = System.nanoTime();
    private long firstAnswer = -1;
    private boolean leading = true;
    private boolean reasoningSeen;
    private StopReason stopReason;

    AnswerStream(java.util.function.Consumer<String> output) { this(output, false, ""); }
    AnswerStream(java.util.function.Consumer<String> output, boolean showReasoning) {
      this(output, showReasoning, "");
    }
    AnswerStream(java.util.function.Consumer<String> output, boolean showReasoning, String prompt) {
      this.output = output; this.showReasoning = showReasoning;
      String tail = prompt.stripTrailing();
      for (int i = 0; i < OPEN.length; i++) if (tail.endsWith(OPEN[i])) closers.push(CLOSE[i]);
      if (tail.endsWith("<|channel>thought\n<channel|>")) closers.push("<channel|>");
      reasoningSeen = !closers.isEmpty();
    }

    public void onToken(String token) {
      if (showReasoning) output.accept(token);
      pending.append(token); drain(false);
    }

    private void drain(boolean complete) {
      while (!pending.isEmpty()) {
        String text = pending.toString();
        if (closers.isEmpty() && !leading) {
          if (firstAnswer < 0) firstAnswer = System.nanoTime();
          if (!showReasoning) output.accept(text);
          pending.setLength(0); return;
        }
        String markerText = text.toLowerCase(java.util.Locale.ROOT);
        if (!closers.isEmpty() && markerText.startsWith(closers.peek())) {
          pending.delete(0, closers.pop().length()); continue;
        }
        int open = -1;
        for (int i = 0; i < OPEN.length; i++) if (markerText.startsWith(OPEN[i])) { open = i; break; }
        if (open >= 0) {
          reasoningSeen = true; closers.push(CLOSE[open]); pending.delete(0, OPEN[open].length()); continue;
        }
        boolean partial = !closers.isEmpty() && closers.peek().startsWith(markerText);
        for (String marker : OPEN) partial |= marker.startsWith(markerText);
        if (partial && !complete) return;
        if (!closers.isEmpty() || Character.isWhitespace(pending.charAt(0))) pending.deleteCharAt(0);
        else leading = false;
      }
    }

    boolean hasAnswer() { return firstAnswer >= 0; }
    String firstAnswerMillis() {
      return hasAnswer() ? ((firstAnswer - started) / 1_000_000) + " ms" : "n/a";
    }
    public void onComplete() { drain(true); }
    public void onComplete(GenerationUsage usage, StopReason reason) {
      stopReason = reason; drain(true);
    }
    public void onError(Throwable failure) { throw new IllegalStateException("Generation failed", failure); }
  }

  private static void quietLibraries() {
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
  }
}
