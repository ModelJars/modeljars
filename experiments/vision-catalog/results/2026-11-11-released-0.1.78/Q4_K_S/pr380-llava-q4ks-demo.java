//JAVA 25+
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS org.modeljars:modeljars:0.1.78
//DEPS org.modeljars.huggingface:second-state.llava-v1.5-7b-gguf.q4_k_s:1.0.0-q4_k_s.1

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.modeljars.ModelJars;

class ModelJarsDemo {
  private static final String MODEL = "org.modeljars.huggingface:second-state.llava-v1.5-7b-gguf.q4_k_s:1.0.0-q4_k_s.1";

  public static void main(String... args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("Usage: jbang this-demo.java IMAGE.png [question]");
    }
    byte[] image = Files.readAllBytes(Path.of(args[0]));
    String question = args.length == 1 ? "What is shown in this image?"
        : String.join(" ", Arrays.copyOfRange(args, 1, args.length));
    long started = System.nanoTime();
    try (var runtime = ModelJars.openVisionRuntime(MODEL)) {
      long loaded = System.nanoTime();
      String answer = runtime.answer(image, question);
      long completed = System.nanoTime();
      System.out.println("Image: " + args[0]);
      System.out.println("Question: " + question);
      System.out.println("Answer: " + answer);
      System.out.printf("Load:      %,d ms%n", (loaded - started) / 1_000_000);
      System.out.printf("Execution: %,d ms%n", (completed - loaded) / 1_000_000);
    }
  }
}
