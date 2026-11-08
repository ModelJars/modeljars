/*
 * Copyright 2025-2026 Integrallis Software, LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarInstaller;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelJars;
import org.modeljars.ModelLoadOptions;

/** Runs the published public API on Linux; never substitutes a locally built runtime JAR. */
public final class ReleasedEmbeddingConsumer {
  public static void main(String[] args) throws Exception {
    if (!System.getProperty("os.name").equals("Linux") || args.length != 5) {
      throw new IllegalArgumentException("MANIFEST CASE CACHE OUTPUT install|measure");
    }
    var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    Path manifest = Path.of(args[0]), cache = Path.of(args[2]), output = Path.of(args[3]);
    if (Files.exists(output)) throw new IllegalArgumentException("Preserve existing evidence");
    var config = mapper.readTree(manifest.toFile()).required(args[1]);
    var model = ModelJar.of(config.required("marker").asText());
    var registry = ModelJarRegistry.fromClasspath();
    var descriptor = registry.resolve(model).orElseThrow();
    var artifact = ModelJarCache.artifactPath(descriptor, cache);
    if (args[4].equals("install")) {
      new ModelJarInstaller(registry).install(descriptor, artifact);
      return;
    }
    if (!args[4].equals("measure")) throw new IllegalArgumentException("Unknown mode");
    var report = new LinkedHashMap<String, Object>();
    report.put("schema", "released-embedding-consumer-v1");
    report.put("startedUtc", Instant.now().toString());
    report.put("case", args[1]);
    report.put("manifestSha256", digest(manifest));
    report.put("markerCoordinate", descriptor.markerCoordinate().toString());
    report.put("artifactSha256", digest(artifact));
    if (!report.get("artifactSha256").equals(config.required("artifactSha256").asText())) {
      throw new IllegalStateException("Workload artifact hash mismatch");
    }
    report.put("artifactHashReadBeforeLoad", true);
    report.put("javaVersion", System.getProperty("java.runtime.version"));
    report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    Map<String, String> jars = new TreeMap<>();
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      Path path = Path.of(entry);
      if (Files.isRegularFile(path)) jars.put(path.toAbsolutePath().toString(), digest(path));
    }
    report.put("classpathSha256", jars);
    var options = ModelLoadOptions.builder().cacheDirectory(cache).offline(true).build();
    long start = System.nanoTime();
    try (var runtime = ModelJars.openEmbeddingRuntime(model, options)) {
      report.put("verifiedCacheLoadMs", (System.nanoTime() - start) / 1e6);
      var embedding = runtime.model();
      String text = config.required("text").asText();
      int warmups = config.required("warmups").asInt();
      int repetitions = config.required("repetitions").asInt();
      if (warmups < 1 || repetitions < 1) throw new IllegalArgumentException("Invalid counts");
      report.put("configuration", config);
      report.put("dimension", embedding.dimension());
      var runs = new ArrayList<Map<String, Object>>();
      float[] expected = null;
      for (int i = -warmups; i < repetitions; i++) {
        start = System.nanoTime();
        float[] vector = embedding.embed(text);
        double elapsed = (System.nanoTime() - start) / 1e6;
        if (expected == null) expected = vector.clone();
        if (!Arrays.equals(expected, vector)) throw new IllegalStateException("Unstable vectors");
        double squaredNorm = 0;
        for (float value : vector) {
          if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite vector");
          squaredNorm += (double) value * value;
        }
        if (Math.abs(Math.sqrt(squaredNorm) - 1.0) > 1e-5) {
          throw new IllegalStateException("Embedding is not normalized");
        }
        runs.add(Map.of("run", i, "phase", i < 0 ? "warmup" : "measured", "milliseconds", elapsed));
        System.out.printf("run=%d ms=%.3f%n", i, elapsed);
      }
      report.put("runs", runs);
      report.put("vector", expected);
      if (config.has("oracleVector")) {
        float[] reference = mapper.treeToValue(config.required("oracleVector"), float[].class);
        if (reference.length != expected.length)
          throw new IllegalStateException("Oracle dimension");
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < reference.length; i++) {
          dot += (double) reference[i] * expected[i];
          a += (double) reference[i] * reference[i];
          b += (double) expected[i] * expected[i];
        }
        double cosine = dot / Math.sqrt(a * b);
        report.put("oracleCosine", cosine);
        report.put("catalogFidelityPassed", Double.isFinite(cosine) && cosine >= 0.999);
        report.put("memoryProposedFidelityPassed", Double.isFinite(cosine) && cosine >= 0.99999);
      }
      ByteBuffer bits = ByteBuffer.allocate(expected.length * Float.BYTES);
      for (float value : expected) bits.putInt(Float.floatToRawIntBits(value));
      report.put(
          "vectorBitsSha256",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bits.array())));
    }
    report.put("completedUtc", Instant.now().toString());
    Files.createDirectories(output.toAbsolutePath().getParent());
    mapper.writeValue(output.toFile(), report);
    if (Boolean.FALSE.equals(report.get("catalogFidelityPassed"))) {
      throw new IllegalStateException("Catalog oracle fidelity failed; report retained");
    }
  }

  private static String digest(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[1024 * 1024];
      for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
