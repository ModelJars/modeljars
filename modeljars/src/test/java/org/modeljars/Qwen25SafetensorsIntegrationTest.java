package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Qwen25SafetensorsIntegrationTest {
  private static final String DIRECTORY_PROPERTY =
      "modeljars.fixtures.qwen25SafetensorsDirectory";
  private static final String ALIAS = "qwen_qwen2_5_0_5b_instruct_bf16";
  private static final String MODEL_SHA =
      "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe";
  private static final long MODEL_SIZE = 988_097_824L;

  @Test
  void verifiesOpensAndGeneratesThroughTheModelJarsRuntime() {
    String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
    Assumptions.assumeFalse(configured.isEmpty(), () -> "Set -D" + DIRECTORY_PROPERTY);
    Path directory = Path.of(configured).toAbsolutePath().normalize();
    ModelJarRegistry registry = descriptorRegistry(directory);
    ModelJarDescriptor descriptor = registry.descriptors().getFirst();
    ModelJarInstaller installer = new ModelJarInstaller(registry);
    ModelJars modelJars =
        new ModelJars(
            registry,
            qualificationRegistry(),
            emptyProfiles(),
            (candidate, options) ->
                installer.verifyCached(candidate, directory.resolve("model.safetensors")),
            ModelJars::loadBackend,
            Map::of,
            List::of);

    try (ModelJarRuntime runtime =
        modelJars.loadRuntime(
            ModelJar.of("hf://Qwen/Qwen2.5-0.5B-Instruct").variant("bf16"),
            ModelLoadOptions.builder().backend(ModelBackend.JAVA).offline(true).build())) {
      assertEquals(descriptor, runtime.descriptor());
      assertEquals("qwen2", runtime.metadata().modelFamily());
      var prompt =
          runtime.chatTemplate().render(List.of(ChatMessage.user("Name one JVM language.")));
      String answer =
          runtime.model().generate(
              prompt, SamplingOptions.builder().temperature(0).maxTokens(8).build());
      assertFalse(answer.isBlank());
    }
  }

  private static ModelJarRegistry descriptorRegistry(Path directory) {
    Properties properties = new Properties();
    String prefix = "model." + ALIAS + ".";
    properties.setProperty(prefix + "sourceId", "hf://Qwen/Qwen2.5-0.5B-Instruct");
    properties.setProperty(
        prefix + "markerCoordinate",
        "org.modeljars.huggingface:qwen.qwen2.5-0.5b-instruct.bf16:2.5.0-bf16.1");
    properties.setProperty(prefix + "modelVersion", "2.5.0");
    properties.setProperty(prefix + "variant", "bf16");
    properties.setProperty(prefix + "format", "safetensors");
    properties.setProperty(prefix + "architecture", "qwen2");
    properties.setProperty(prefix + "quantization", "BF16");
    properties.setProperty(prefix + "path", directory.resolve("model.safetensors").toString());
    properties.setProperty(
        prefix + "sourceUri", "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct");
    properties.setProperty(
        prefix + "downloadUri",
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct/resolve/"
            + "7ae557604adf67be50417f59c2c2f167def9a775/model.safetensors");
    properties.setProperty(prefix + "revision", "7ae557604adf67be50417f59c2c2f167def9a775");
    properties.setProperty(prefix + "sha256", MODEL_SHA);
    properties.setProperty(prefix + "sizeBytes", Long.toString(MODEL_SIZE));
    properties.setProperty(prefix + "license", "Apache-2.0");
    properties.setProperty(prefix + "capabilities", "text-generation,chat");
    properties.setProperty(prefix + "features", "multi-file-artifact");
    properties.setProperty(prefix + "backend.pure-java", "true");
    addFile(
        properties,
        prefix,
        0,
        "config.json",
        "model-configuration",
        "18e18afcaccafade98daf13a54092927904649e1dd4eba8299ab717d5d94ff45",
        659);
    addFile(properties, prefix, 1, "model.safetensors", "model-weights", MODEL_SHA, MODEL_SIZE);
    addFile(
        properties,
        prefix,
        2,
        "tokenizer.json",
        "tokenizer",
        "c0382117ea329cdf097041132f6d735924b697924d6f6fc3945713e96ce87539",
        7_031_645);
    addFile(
        properties,
        prefix,
        3,
        "tokenizer_config.json",
        "tokenizer-configuration",
        "5b5d4f65d0acd3b2d56a35b56d374a36cbc1c8fa5cf3b3febbbfabf22f359583",
        7_305);
    properties.setProperty(prefix + "file.count", "4");
    return PropertiesModelJarRegistry.fromProperties(properties);
  }

  private static void addFile(
      Properties properties,
      String modelPrefix,
      int index,
      String path,
      String role,
      String sha256,
      long sizeBytes) {
    String prefix = modelPrefix + "file." + "%03d".formatted(index) + ".";
    properties.setProperty(prefix + "path", path);
    properties.setProperty(prefix + "role", role);
    properties.setProperty(prefix + "sha256", sha256);
    properties.setProperty(prefix + "sizeBytes", Long.toString(sizeBytes));
  }

  private static ModelRagQualificationRegistry qualificationRegistry() {
    Properties properties = new Properties();
    properties.setProperty("modeljars.qualifications.schemaVersion", "1");
    properties.setProperty("modeljars.qualifications.generatedAt", "2026-08-24T00:00:00Z");
    properties.setProperty("modeljars.qualifications.policyVersion", "integration-test");
    properties.setProperty("modeljars.qualifications.modelsRevision", "a".repeat(40));
    properties.setProperty("modeljars.qualifications.targetQualifiedModels", "1");
    properties.setProperty("modeljars.qualifications.qualifiedModels", "1");
    properties.setProperty("modeljars.qualifications.rejectedModels", "0");
    String prefix = "qualification." + ALIAS + ".";
    properties.setProperty(prefix + "model", "Qwen2.5-0.5B-Instruct BF16");
    properties.setProperty(prefix + "backend", "pure-java");
    properties.setProperty(prefix + "backendVersion", "integration-test");
    properties.setProperty(prefix + "workload", "general");
    properties.setProperty(prefix + "corpusSha256", "b".repeat(64));
    properties.setProperty(prefix + "promptTemplate", "chatml");
    properties.setProperty(prefix + "groundingPolicy", "integration-test");
    properties.setProperty(prefix + "artifactSha256", MODEL_SHA);
    properties.setProperty(prefix + "artifactSizeBytes", Long.toString(MODEL_SIZE));
    properties.setProperty(prefix + "reportPath", "integration-test/report.json");
    properties.setProperty(prefix + "reportUri", "https://example.invalid/report.json");
    properties.setProperty(prefix + "reportSha256", "c".repeat(64));
    properties.setProperty(prefix + "performanceTier", "PRODUCTION_READY");
    properties.setProperty(prefix + "verdict", "QUALIFIED");
    properties.setProperty(prefix + "qualified", "true");
    properties.setProperty(prefix + "attempts", "1");
    for (String metric :
        List.of(
            "p95RetrievalMillis",
            "p95TtftMillis",
            "p95TpotMillis",
            "p95EndToEndMillis",
            "p50PrefillTokensPerSecond",
            "p50DecodeTokensPerSecond")) {
      properties.setProperty(prefix + metric, "1.0");
    }
    properties.setProperty(prefix + "peakRssBytes", "1");
    for (String rate :
        List.of(
            "correctAnswerRate",
            "rawCorrectAnswerRate",
            "abstentionAccuracy",
            "modelAnswerRate",
            "modelAnswerCorrectRate")) {
      properties.setProperty(prefix + rate, "1.0");
    }
    properties.setProperty(prefix + "extractiveFallbackRate", "0.0");
    String environment = prefix + "environment.";
    properties.setProperty(environment + "hostname", "integration-test");
    properties.setProperty(environment + "osName", "test");
    properties.setProperty(environment + "osVersion", "1");
    properties.setProperty(environment + "architecture", "test");
    properties.setProperty(environment + "cpuModel", "test");
    properties.setProperty(environment + "availableProcessors", "1");
    properties.setProperty(environment + "totalMemoryBytes", "1");
    properties.setProperty(environment + "maxHeapBytes", "1");
    properties.setProperty(environment + "javaVersion", "25");
    properties.setProperty(environment + "javaVendor", "test");
    properties.setProperty(environment + "vmName", "test");
    return ModelRagQualificationRegistry.fromProperties(properties);
  }

  private static ModelPerformanceProfileRegistry emptyProfiles() {
    Properties properties = new Properties();
    properties.setProperty("modeljars.performance.schemaVersion", "1");
    return ModelPerformanceProfileRegistry.fromProperties(properties);
  }
}
