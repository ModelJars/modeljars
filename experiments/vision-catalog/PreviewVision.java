import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJars;

/** Remote consumer smoke; no qualification bypass or production inference implementation. */
class PreviewVision {
  public static void main(String[] args) throws Exception {
    if (!System.getProperty("os.name").equals("Linux"))
      throw new IllegalStateException("Run model inference on the remote Linux worker");
    if (args.length != 3) throw new IllegalArgumentException("MARKER CASES.tsv OUTPUT.json");
    Path output = Path.of(args[2]);
    if (Files.exists(output)) throw new IllegalArgumentException("Preserve the existing evidence");
    try (JsonGenerator json = new JsonFactory().createGenerator(output.toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
      json.useDefaultPrettyPrinter();
      json.writeStartObject();
      json.writeStringField("schema", "modeljars-vision-consumer-v1");
      json.writeStringField("host", InetAddress.getLocalHost().getHostName());
      json.writeStringField("java", Runtime.version().toString());
      json.writeStringField("marker", args[0]);
      json.writeStringField("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
      json.writeObjectFieldStart("runtimeProperties");
      System.getProperties().stringPropertyNames().stream().sorted()
          .filter(k -> k.startsWith("models.") || k.startsWith("vectors.") || k.equals("modeljars.cache"))
          .forEach(k -> {
            try { json.writeStringField(k, System.getProperty(k)); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
          });
      json.writeEndObject();
      long started = System.nanoTime();
      try (var runtime = ModelJars.openVisionRuntime(args[0])) {
        json.writeNumberField("loadMillis", (System.nanoTime() - started) / 1e6);
        json.writeNumberField("context", runtime.backend().contextCapacity());
        json.writeStringField("modelsEvidenceVersion", runtime.qualification().backendVersion());
        json.writeObjectFieldStart("diagnostics");
        for (var entry : runtime.backend().diagnostics().environment().entrySet())
          json.writeStringField(entry.getKey(), entry.getValue());
        json.writeEndObject();
        json.writeArrayFieldStart("verifiedFiles");
        for (var file : runtime.descriptor().files()) {
          Path path = ModelJarCache.bundlePath(runtime.descriptor(), ModelJarCache.defaultDirectory()).resolve(file.path());
          String digest;
          try (var input = Files.newInputStream(path)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
            digest = HexFormat.of().formatHex(hash.digest());
          }
          if (!digest.equals(file.sha256()) || Files.size(path) != file.sizeBytes())
            throw new IllegalStateException("Installed artifact differs: " + file.path());
          json.writeStartObject();
          json.writeStringField("path", file.path());
          json.writeStringField("role", file.role());
          json.writeStringField("sha256", digest);
          json.writeNumberField("sizeBytes", Files.size(path));
          json.writeEndObject();
        }
        json.writeEndArray();
        boolean passed = true;
        json.writeArrayFieldStart("cases");
        for (String line : Files.readAllLines(Path.of(args[1]))) {
          String[] fields = line.split("\\t", -1);
          if (fields.length != 4) throw new IllegalArgumentException("Malformed case row");
          String question = decode(fields[2]);
          String expected = decode(fields[3]);
          byte[] image = Files.readAllBytes(Path.of(fields[1]));
          long before = System.nanoTime();
          String answer = runtime.answer(image, question);
          double millis = (System.nanoTime() - before) / 1e6;
          boolean match = answer.equals(expected);
          passed &= match;
          json.writeStartObject();
          json.writeStringField("id", fields[0]);
          json.writeStringField("question", question);
          json.writeStringField("answer", answer);
          json.writeStringField("expected", expected);
          json.writeStringField("imageSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)));
          json.writeBooleanField("matchesMeasuredOutput", match);
          json.writeNumberField("executionMillis", millis);
          json.writeEndObject();
          json.flush();
        }
        json.writeEndArray();
        json.writeBooleanField("passed", passed);
        json.writeEndObject();
        if (!passed) throw new IllegalStateException("Public API changed a measured answer");
      }
    }
  }

  private static String decode(String text) {
    return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
  }
}
