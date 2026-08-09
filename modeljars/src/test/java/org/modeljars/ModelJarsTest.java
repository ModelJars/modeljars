package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.EmbeddingBackend;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.modeljars.catalog.Qwen_Qwen3_Embedding_0_6b_Gguf_Q8_0;
import org.modeljars.catalog.Qwen3_0_6b_Q4_0;
import org.modeljars.catalog.Smollm2_360m_Instruct_Q8_0;

class ModelJarsTest {
  private static final ModelJar QWEN = Qwen3_0_6b_Q4_0.MODEL;
  private static final ModelJar QWEN_EMBEDDING = Qwen_Qwen3_Embedding_0_6b_Gguf_Q8_0.MODEL;
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

    var runtime = loader.loadRuntime(QWEN, ModelLoadOptions.defaults());
    var model = runtime.model();

    assertEquals(descriptor, installed.get());
    assertEquals("pure-java", selectedBackend.get());
    assertTrue(
        selectedConfiguration
            .get()
            .recommendations()
            .entrySet()
            .containsAll(profile.recommendations().entrySet()));
    assertEquals("fixture", model.modelName());
    assertEquals(descriptor, runtime.descriptor());
    assertEquals("chatml-no-think", runtime.qualification().promptTemplate());
    assertEquals(ChatTemplate.CHATML_NO_THINK, runtime.chatTemplate());
    assertFalse(backend.closed());
    runtime.close();
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
            Map::of,
            () -> List.of("--enable-native-access=ALL-UNNAMED"));

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
  void explainsTheVectorModuleBeforeClasspathLoading() {
    ModelJarException failure =
        assertThrows(ModelJarException.class, () -> ModelJars.requireVectorModule(false));

    assertTrue(failure.getMessage().contains("--add-modules=jdk.incubator.vector"));
  }

  @Test
  void rejectsMissingNativeAccessBeforeInstallingTheArtifact() {
    AtomicReference<ModelJarDescriptor> installed = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> {
              installed.set(descriptor);
              return Path.of("must-not-install.gguf");
            },
            (backend, path, configuration) -> new StubBackend(),
            Map::of,
            List::of);

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () -> loader.loadRuntime(SMOLLM, ModelLoadOptions.defaults()));

    assertTrue(failure.getMessage().contains("--enable-native-access=ALL-UNNAMED"));
    assertNull(installed.get());
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

  @Test
  void exposesTheQualifiedPipelineWithoutFlatteningStructuredPrompts() {
    StubBackend backend = new StubBackend();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backendName, path, configuration) -> backend,
            Map::of);

    try (var runtime = loader.loadRuntime(QWEN, ModelLoadOptions.defaults())) {
      ModelPrompt prompt =
          runtime.chatTemplate().render(List.of(ChatMessage.user("hello")));

      assertSame(runtime.pipeline(), runtime.model());
      assertSame(runtime.pipeline().tokenizer(), runtime.tokenizer());
      assertEquals("fixture", runtime.metadata().modelName());
      assertEquals(32, runtime.contextWindow().capacity());
      assertTrue(runtime.contextWindow().position().isEmpty());
      assertEquals("", runtime.model().generate(prompt, SamplingOptions.builder().maxTokens(1).build()));
      assertEquals(1, backend.structuredEncodes);
      assertEquals(0, backend.plainEncodes);
    }
  }

  @Test
  void opensAnEmbeddingFromMarkerOwnedQualificationSettings() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(QWEN_EMBEDDING).orElseThrow();
    StubEmbeddingBackend embedding = new StubEmbeddingBackend();
    AtomicReference<ModelEmbeddingQualificationRegistry.Entry> selectedQualification =
        new AtomicReference<>();
    AtomicReference<BackendConfiguration> selectedConfiguration = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            models,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelEmbeddingQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> Path.of("verified-embedding.gguf"),
            (backendName, path, configuration) -> new StubBackend(),
            (path, qualification, configuration) -> {
              selectedQualification.set(qualification);
              selectedConfiguration.set(configuration);
              return embedding;
            },
            Map::of,
            List::of);

    try (var runtime = loader.loadEmbeddingRuntime(QWEN_EMBEDDING, ModelLoadOptions.defaults())) {
      assertSame(embedding, runtime.model());
      assertEquals(descriptor, runtime.descriptor());
      assertEquals(descriptor.alias(), runtime.qualification().modelId());
      assertEquals("last-token", selectedQualification.get().pooling());
      assertEquals(
          descriptor.markerCoordinate().toString(),
          selectedConfiguration.get().environment().get("modeljars-marker"));
      assertEquals(1024, runtime.model().embed("hello").length);
      assertFalse(embedding.closed);
    }
    assertTrue(embedding.closed);
  }

  private static final class StubEmbeddingBackend implements EmbeddingBackend {
    private boolean closed;

    @Override
    public int dimension() {
      return 1024;
    }

    @Override
    public float[] embed(String text) {
      return new float[dimension()];
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class StubBackend implements InferenceBackend {
    private int plainEncodes;
    private int structuredEncodes;
    private final Tokenizer tokenizer =
        new Tokenizer() {
          @Override
          public int[] encode(String text) {
            plainEncodes++;
            return new int[] {1};
          }

          @Override
          public int[] encode(ModelPrompt prompt) {
            structuredEncodes++;
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
      return tokenizer;
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
