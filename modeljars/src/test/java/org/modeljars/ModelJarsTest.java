package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.Tokenizer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.modeljars.catalog.Qwen3_0_6b_Q4_0;
import org.modeljars.catalog.Smollm2_360m_Instruct_Q8_0;

class ModelJarsTest {
  private static final ModelJar QWEN = Qwen3_0_6b_Q4_0.MODEL;
  private static final ModelJar SMOLLM = Smollm2_360m_Instruct_Q8_0.MODEL;

  @Test
  void opensAQualifiedModelWithoutExposingItsInstalledPath() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(QWEN).orElseThrow();
    ModelRagQualificationRegistry qualifications =
        ModelRagQualificationRegistry.fromClasspath();
    ModelPerformanceProfileRegistry profiles = ModelPerformanceProfileRegistry.fromClasspath();
    ModelPerformanceProfile profile =
        profiles.profilesFor(descriptor).stream()
            .filter(ModelPerformanceProfile::safeForAutomaticSelection)
            .findFirst()
            .orElseThrow();
    AtomicReference<ModelJarDescriptor> installed = new AtomicReference<>();
    AtomicReference<String> selectedBackend = new AtomicReference<>();
    AtomicReference<BackendConfiguration> selectedConfiguration = new AtomicReference<>();
    StubBackend backend = new StubBackend();
    ModelJars loader =
        new ModelJars(
            models,
            qualifications,
            profiles,
            (candidate, options) -> {
              installed.set(candidate);
              return Path.of("verified-model.gguf");
            },
            (backendName, path, configuration) -> {
              selectedBackend.set(backendName);
              selectedConfiguration.set(configuration);
              return backend;
            },
            () -> profile.runtimeSelector(),
            () -> profile.javaLaunch().map(JavaLaunchProfile::jvmArguments).orElseGet(List::of));

    var model = loader.load(QWEN, ModelLoadOptions.defaults());

    assertEquals(descriptor, installed.get());
    assertEquals("pure-java", selectedBackend.get());
    assertTrue(
        selectedConfiguration
            .get()
            .recommendations()
            .entrySet()
            .containsAll(profile.recommendations().entrySet()));
    assertEquals("fixture", model.modelName());
    assertFalse(backend.closed());
    model.close();
    assertTrue(backend.closed());
  }

  @Test
  void combinesEveryNonConflictingProfileForTheExactRuntime() {
    ModelJar model = QWEN;
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(model).orElseThrow();
    ModelPerformanceProfileRegistry profiles = ModelPerformanceProfileRegistry.fromClasspath();
    Map<String, String> runtime =
        profiles.profilesFor(descriptor).stream()
            .filter(
                profile ->
                    "unsigned-pairwise"
                        .equals(
                            profile
                                .recommendations()
                                .get("models.purejava.q4Kernel")))
            .findFirst()
            .orElseThrow()
            .runtimeSelector();
    AtomicReference<BackendConfiguration> selectedConfiguration = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            models,
            ModelRagQualificationRegistry.fromClasspath(),
            profiles,
            (candidate, options) -> Path.of("verified-model.gguf"),
            (backend, path, configuration) -> {
              selectedConfiguration.set(configuration);
              return new StubBackend();
            },
            () -> runtime,
            () -> List.of("-Djdk.graal.MaximumInliningSize=10000"));

    try (var loaded =
        loader.load(
            model, ModelLoadOptions.builder().backend(ModelBackend.JAVA).build())) {
      assertEquals("fixture", loaded.modelName());
      assertEquals(
          Map.of(
              "models.purejava.prefillBatchSize",
              "24",
              "models.purejava.q4Kernel",
              "unsigned-pairwise",
              "models.purejava.stagedQuantizedFfn",
              "true",
              "models.purejava.stagedQuantizedLayer",
              "true"),
          selectedConfiguration.get().recommendations());
    }
  }

  @Test
  void skipsProfilesWhoseRequiredJvmArgumentsAreMissing() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(QWEN).orElseThrow();
    ModelPerformanceProfileRegistry profiles = ModelPerformanceProfileRegistry.fromClasspath();
    Map<String, String> runtime =
        profiles.profilesFor(descriptor).stream()
            .filter(
                profile ->
                    "24"
                        .equals(
                            profile
                                .recommendations()
                                .get("models.purejava.prefillBatchSize")))
            .findFirst()
            .orElseThrow()
            .runtimeSelector();
    AtomicReference<BackendConfiguration> selectedConfiguration = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            models,
            ModelRagQualificationRegistry.fromClasspath(),
            profiles,
            (candidate, options) -> Path.of("verified-model.gguf"),
            (backend, path, configuration) -> {
              selectedConfiguration.set(configuration);
              return new StubBackend();
            },
            () -> runtime,
            List::of);

    try (var loaded =
        loader.load(QWEN, ModelLoadOptions.builder().backend(ModelBackend.JAVA).build())) {
      assertEquals("fixture", loaded.modelName());
      assertEquals(
          Map.of("models.purejava.prefillBatchSize", "24"),
          selectedConfiguration.get().recommendations());
      var launchDecision =
          selectedConfiguration.get().optimizations().stream()
              .filter(decision -> decision.id().equals("modeljars.performance-profile-launch"))
              .findFirst()
              .orElseThrow();
      assertEquals(OptimizationStatus.DISABLED, launchDecision.status());
      assertEquals(
          "-Djdk.graal.MaximumInliningSize=10000",
          launchDecision.settings().get("missing-jvm-arguments"));
    }
  }

  @Test
  void selectsTheQualifiedNativeBackendAutomatically() {
    AtomicReference<String> selectedBackend = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backend, path, configuration) -> {
              selectedBackend.set(backend);
              return new StubBackend();
            },
            Map::of);

    try (var model = loader.load(SMOLLM, ModelLoadOptions.defaults())) {
      assertEquals("rust-ffm", selectedBackend.get());
      assertEquals("fixture", model.modelName());
    }
  }

  @Test
  void rejectsAnExplicitBackendThatHasNotQualifiedForTheArtifact() {
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("must-not-install.gguf"),
            (backend, path, configuration) -> new StubBackend(),
            Map::of);

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () ->
                loader.load(
                    QWEN,
                    ModelLoadOptions.builder().backend(ModelBackend.NATIVE).build()));

    assertTrue(failure.getMessage().contains("qualified"));
    assertTrue(failure.getMessage().contains("rust-ffm"));
  }

  @Test
  void delegatesGenerationAndClosesTheBackendOnlyOnce() {
    StubBackend backend = new StubBackend();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backendName, path, configuration) -> backend,
            Map::of);

    var model = loader.load(QWEN, ModelLoadOptions.defaults());

    assertEquals("", model.generate("prompt", SamplingOptions.builder().maxTokens(1).build()));
    model.close();
    model.close();
    assertEquals(1, backend.closeCount());
  }

  private static final class StubBackend implements InferenceBackend {
    private static final Tokenizer TOKENIZER =
        new Tokenizer() {
          @Override
          public int[] encode(String text) {
            return new int[] {1};
          }

          @Override
          public String decode(int[] tokens) {
            return "";
          }

          @Override
          public String decode(int token) {
            return "";
          }

          @Override
          public int vocabSize() {
            return 2;
          }

          @Override
          public int bosToken() {
            return 0;
          }

          @Override
          public int eosToken() {
            return 1;
          }
        };

    private int closeCount;

    @Override
    public String name() {
      return "stub";
    }

    @Override
    public ModelMetadata metadata() {
      return new ModelMetadata("fixture", "fixture", 32, 2, 2, 1, 1, 1);
    }

    @Override
    public BackendDiagnostics diagnostics() {
      return BackendDiagnostics.unavailable("stub");
    }

    @Override
    public Tokenizer tokenizer() {
      return TOKENIZER;
    }

    @Override
    public float[] forward(int token, int position) {
      return new float[] {0.0f, 1.0f};
    }

    @Override
    public void close() {
      closeCount++;
    }

    boolean closed() {
      return closeCount > 0;
    }

    int closeCount() {
      return closeCount;
    }
  }
}
