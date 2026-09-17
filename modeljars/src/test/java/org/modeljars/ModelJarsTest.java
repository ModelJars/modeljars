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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.ActivatedAdapterMetadata;
import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.BatchInferenceBackend;
import com.integrallis.models.api.EmbeddingBackend;
import com.integrallis.models.api.InferenceSession;
import com.integrallis.models.api.LogitBatch;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.PcmAudio;
import com.integrallis.models.api.RerankingModel;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.SharedInferencePrefix;
import com.integrallis.models.api.SharedPrefixInferenceBackend;
import com.integrallis.models.api.SpeechSynthesisOptions;
import com.integrallis.models.api.TextToSpeechModel;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.ContinuousBatchingOptions;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import com.integrallis.models.runtime.chat.VirtualChatModel;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.modeljars.catalog.Cactus_Compute_Needle2_Cact_Cq2_Mixed;
import org.modeljars.catalog.Cstr_Ms_Marco_Minilm_L6_V2_Gguf_Q4_K_Imatrix_G7c_F7;
import org.modeljars.catalog.Ibm_Granite_Granite_4_1_3b_Gguf_Q4_K_M;
import org.modeljars.catalog.Qwen3_0_6b_Q4_0;
import org.modeljars.catalog.Qwen3_1_7b_Q8_0;
import org.modeljars.catalog.Qwen_Qwen3_Embedding_0_6b_Gguf_Q8_0;
import org.modeljars.catalog.Smollm2_360m_Instruct_Q8_0;

class ModelJarsTest {
  private static final ModelJar QWEN = Qwen3_0_6b_Q4_0.MODEL;
  private static final ModelJar QWEN_TOOLS = Qwen3_1_7b_Q8_0.MODEL;
  private static final ModelJar NEEDLE2 = Cactus_Compute_Needle2_Cact_Cq2_Mixed.MODEL;
  private static final ModelJar QWEN_EMBEDDING = Qwen_Qwen3_Embedding_0_6b_Gguf_Q8_0.MODEL;
  private static final ModelJar MINILM_RERANKER =
      Cstr_Ms_Marco_Minilm_L6_V2_Gguf_Q4_K_Imatrix_G7c_F7.MODEL;
  private static final ModelJar SMOLLM = Smollm2_360m_Instruct_Q8_0.MODEL;
  private static final ModelJar GRANITE = Ibm_Granite_Granite_4_1_3b_Gguf_Q4_K_M.MODEL;

  @Test
  void opensAQualifiedModelWithoutExposingItsInstalledPath() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(QWEN).orElseThrow();
    ModelRagQualificationRegistry qualifications = ModelRagQualificationRegistry.fromClasspath();
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
  void opensAndOwnsBothMembersOfAChatToolHybrid() {
    var loadedBackends = new java.util.ArrayList<StubBackend>();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of(descriptor.alias() + ".gguf"),
            (backend, path, configuration) -> {
              var loaded = new StubBackend();
              loadedBackends.add(loaded);
              return loaded;
            },
            Map::of);

    try (var hybrid =
        loader.loadChatToolHybrid(
            QWEN,
            QWEN_TOOLS,
            ModelLoadOptions.builder().backend(ModelBackend.JAVA).build(),
            ModelLoadOptions.builder().backend(ModelBackend.JAVA).build(),
            VirtualChatModel.ConstraintFactory.none())) {
      assertEquals("qwen3_0_6b_q4_0", hybrid.chatRuntime().descriptor().alias());
      assertEquals("qwen3_1_7b_q8_0", hybrid.toolRuntime().descriptor().alias());
      assertEquals(2, loadedBackends.size());
      assertTrue(loadedBackends.stream().noneMatch(StubBackend::closed));
    }

    assertTrue(loadedBackends.stream().allMatch(StubBackend::closed));
  }

  @Test
  void closesTheChatMemberWhenTheToolMemberCannotOpen() {
    var chatBackend = new StubBackend();
    var loads = new java.util.concurrent.atomic.AtomicInteger();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of(descriptor.alias() + ".gguf"),
            (backend, path, configuration) -> {
              if (loads.getAndIncrement() == 0) {
                return chatBackend;
              }
              throw new IllegalStateException("tool load failed");
            },
            Map::of);

    assertThrows(
        IllegalStateException.class,
        () ->
            loader.loadChatToolHybrid(
                QWEN,
                QWEN_TOOLS,
                ModelLoadOptions.builder().backend(ModelBackend.JAVA).build(),
                ModelLoadOptions.builder().backend(ModelBackend.JAVA).build(),
                VirtualChatModel.ConstraintFactory.none()));

    assertTrue(chatBackend.closed());
  }

  @Test
  void opensOneBaseAndItsActivatedAdapterAsOnePhysicallyShareableRuntime() {
    ModelJarRegistry classpath = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor base = classpath.resolve(QWEN).orElseThrow();
    ModelJarDescriptor adapter = activatedAdapterDescriptor(base);
    ModelJar adapterModel = ModelJar.of(adapter.markerCoordinate().toString());
    var installed = new java.util.ArrayList<ModelJarDescriptor>();
    var loadedBase = new AtomicReference<Path>();
    var loadedAdapter = new AtomicReference<Path>();
    var backend =
        new StubSharedBackend(base.sha256().orElseThrow(), adapter.sha256().orElseThrow());
    ModelJars loader =
        new ModelJars(
            new InMemoryModelJarRegistry(List.of(base, adapter)),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> {
              installed.add(descriptor);
              return descriptor == base ? Path.of("verified-base.gguf") : Path.of("adapter-root");
            },
            (backendName, path, configuration) -> new StubBackend(),
            (basePath, adapterPath, configuration) -> {
              loadedBase.set(basePath);
              loadedAdapter.set(adapterPath);
              return backend;
            },
            componentQualificationRegistry(adapter, base, true),
            Map::of);

    try (var runtime =
        loader.loadActivatedToolRuntime(QWEN, adapterModel, ModelLoadOptions.defaults())) {
      assertEquals(base.sha256().orElseThrow(), runtime.model().adapter().baseArtifactSha256());
      assertEquals(256, runtime.model().minimumSharedPrefixTokens());
      assertEquals(base, runtime.baseDescriptor());
      assertEquals(adapter, runtime.adapterDescriptor());
      assertEquals(ChatTemplate.CHATML_NO_THINK, runtime.chatTemplate());
      assertEquals("verified-base.gguf", loadedBase.get().toString());
      assertEquals("adapter-root", loadedAdapter.get().toString());
      assertEquals(List.of(base, adapter), installed);
      assertFalse(backend.closed());
    }

    assertTrue(backend.closed());
  }

  @Test
  void refusesAnActivatedDescriptorWithoutEvidenceBoundToItsExactBase() {
    ModelJarDescriptor base = ModelJarRegistry.fromClasspath().resolve(QWEN).orElseThrow();
    ModelJarDescriptor adapter = activatedAdapterDescriptor(base);
    var installs = new java.util.concurrent.atomic.AtomicInteger();
    ModelJars loader =
        new ModelJars(
            new InMemoryModelJarRegistry(List.of(base, adapter)),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> {
              installs.incrementAndGet();
              return Path.of("unexpected");
            },
            (backendName, path, configuration) -> new StubBackend(),
            (basePath, adapterPath, configuration) ->
                new StubSharedBackend(base.sha256().orElseThrow(), adapter.sha256().orElseThrow()),
            componentQualificationRegistry(adapter, base, false),
            Map::of);

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () ->
                loader.loadActivatedToolRuntime(
                    QWEN,
                    ModelJar.of(adapter.markerCoordinate().toString()),
                    ModelLoadOptions.defaults()));

    assertTrue(failure.getMessage().contains("no qualified component evidence"));
    assertEquals(0, installs.get());
  }

  @Test
  void refusesToTreatAStandaloneModelAsAnActivatedAdapter() {
    var installs = new java.util.concurrent.atomic.AtomicInteger();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> {
              installs.incrementAndGet();
              return Path.of("unexpected");
            },
            (backendName, path, configuration) -> new StubBackend(),
            (basePath, adapterPath, configuration) ->
                new StubSharedBackend("0".repeat(64), "1".repeat(64)),
            Map::of);

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () -> loader.loadActivatedToolRuntime(QWEN, QWEN_TOOLS, ModelLoadOptions.defaults()));

    assertTrue(failure.getMessage().contains("activated-lora-adapter"));
    assertEquals(0, installs.get());
  }

  @Test
  void closesABackendWhoseAdapterMetadataDoesNotMatchTheVerifiedComponent() {
    ModelJarDescriptor base = ModelJarRegistry.fromClasspath().resolve(QWEN).orElseThrow();
    ModelJarDescriptor adapter = activatedAdapterDescriptor(base);
    var backend = new StubSharedBackend(base.sha256().orElseThrow(), "9".repeat(64));
    ModelJars loader =
        new ModelJars(
            new InMemoryModelJarRegistry(List.of(base, adapter)),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) ->
                descriptor == base ? Path.of("verified-base.gguf") : Path.of("adapter-root"),
            (backendName, path, configuration) -> new StubBackend(),
            (basePath, adapterPath, configuration) -> backend,
            componentQualificationRegistry(adapter, base, true),
            Map::of);

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () ->
                loader.loadActivatedToolRuntime(
                    QWEN,
                    ModelJar.of(adapter.markerCoordinate().toString()),
                    ModelLoadOptions.defaults()));

    assertTrue(failure.getMessage().contains("adapter SHA-256"));
    assertTrue(backend.closed());
  }

  @Test
  void opensToolQualifiedCactWithItsExactJavaEvidence() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(NEEDLE2).orElseThrow();
    AtomicReference<String> selectedBackend = new AtomicReference<>();
    AtomicReference<BackendConfiguration> selectedConfiguration = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            models,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> Path.of("verified-model.cact"),
            (backendName, path, configuration) -> {
              selectedBackend.set(backendName);
              selectedConfiguration.set(configuration);
              return new StubBackend();
            },
            Map::of);

    try (var runtime = loader.loadRuntime(NEEDLE2, ModelLoadOptions.defaults())) {
      var qualification = runtime.toolQualification().orElseThrow();

      assertEquals("cact", descriptor.format());
      assertEquals("needle2", descriptor.architecture());
      assertEquals("pure-java", selectedBackend.get());
      assertEquals("needle2-upstream-playground-v1", qualification.workload());
      assertEquals(0.918918918918919, qualification.expectedArgumentAccuracy());
      assertTrue(qualification.productionUsable());
      assertSame(qualification, runtime.executionQualification());
      assertTrue(runtime.ragQualification().isEmpty());
      assertEquals(ChatTemplate.NEEDLE2, runtime.chatTemplate());
      assertThrows(ModelJarException.class, runtime::qualification);
      assertEquals(
          descriptor.sha256().orElseThrow(),
          selectedConfiguration.get().environment().get("modeljars-artifact-sha256"));
    }
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
                        .equals(profile.recommendations().get("models.purejava.q4Kernel")))
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
        loader.load(model, ModelLoadOptions.builder().backend(ModelBackend.JAVA).build())) {
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
                    "24".equals(profile.recommendations().get("models.purejava.prefillBatchSize")))
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
  void opensThePublishedGraniteQualificationWithTheRuntimeGraniteTemplate() {
    // Regression: the bundled Granite 4.1 3B qualification records the RAG harness's
    // granite-documents envelope, which is not a runtime chat template id. ModelJars 0.1.40 to
    // 0.1.42 passed it straight to ChatTemplate.parse, so opening the published model failed after
    // the backend had already loaded.
    StubBackend backend = new StubBackend();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backendName, path, configuration) -> backend,
            Map::of,
            () -> List.of("--enable-native-access=ALL-UNNAMED"));

    try (var runtime = loader.loadRuntime(GRANITE, ModelLoadOptions.defaults())) {
      assertEquals("granite-documents", runtime.qualification().promptTemplate());
      assertEquals(ChatTemplate.GRANITE, runtime.chatTemplate());
      assertFalse(backend.closed());
    }
    assertTrue(backend.closed());
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
                loader.load(QWEN, ModelLoadOptions.builder().backend(ModelBackend.NATIVE).build()));

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
            ModelJarException.class, () -> loader.loadRuntime(SMOLLM, ModelLoadOptions.defaults()));

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
      ModelPrompt prompt = runtime.chatTemplate().render(List.of(ChatMessage.user("hello")));

      assertSame(runtime.pipeline(), runtime.model());
      assertSame(runtime.pipeline().tokenizer(), runtime.tokenizer());
      assertEquals("fixture", runtime.metadata().modelName());
      assertEquals(32, runtime.contextWindow().capacity());
      assertTrue(runtime.contextWindow().position().isEmpty());
      assertEquals(
          "", runtime.model().generate(prompt, SamplingOptions.builder().maxTokens(1).build()));
      assertEquals(1, backend.structuredEncodes);
      assertEquals(0, backend.plainEncodes);
    }
  }

  @Test
  void exposesConversationScopedGenerationSessionsFromTheQualifiedRuntime() {
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
      var first = runtime.openGenerationSession();
      try (var second = runtime.openGenerationSession()) {
        assertFalse(first.isClosed());
        assertFalse(second.isClosed());
        assertEquals(0, first.contextWindow().position().orElseThrow());
        assertEquals(0, second.contextWindow().position().orElseThrow());

        first.close();

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        assertFalse(backend.closed());
      }
    }

    assertTrue(backend.closed());
  }

  @Test
  void configuresContinuousBatchingWhenOpeningAQualifiedTextRuntime() {
    StubBackend backend = new StubBackend();
    AtomicReference<String> selectedBackend = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backendName, path, configuration) -> {
              selectedBackend.set(backendName);
              return backend;
            },
            Map::of,
            () -> List.of("--enable-native-access=ALL-UNNAMED"));
    var batching =
        ContinuousBatchingOptions.builder()
            .maximumBatchSize(2)
            .batchPrefillAcrossSessions(true)
            .batchFormationDelay(Duration.ofMillis(25))
            .build();

    try (var runtime = loader.loadRuntime(SMOLLM, ModelLoadOptions.defaults(), batching);
        var first = runtime.openGenerationSession();
        var second = runtime.openGenerationSession()) {
      assertEquals("rust-ffm", selectedBackend.get());
      assertTrue(runtime.continuousBatchingMetrics().isPresent());
      var options = SamplingOptions.builder().maxTokens(1).build();
      var firstResult = CompletableFuture.supplyAsync(() -> first.generate("first", options));
      var secondResult = CompletableFuture.supplyAsync(() -> second.generate("second", options));
      assertEquals("", firstResult.join());
      assertEquals("", secondResult.join());
      assertEquals(2, runtime.continuousBatchingMetrics().orElseThrow().completedRequests());
      assertEquals(1, backend.raggedPrefillCalls);
    }

    assertTrue(backend.closed());
  }

  @Test
  void closesTheLoadedBackendWhenBatchingConfigurationExceedsItsCapacity() {
    StubBackend backend = new StubBackend();
    ModelJars loader =
        new ModelJars(
            ModelJarRegistry.fromClasspath(),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (descriptor, options) -> Path.of("verified-model.gguf"),
            (backendName, path, configuration) -> backend,
            Map::of);
    var batching = ContinuousBatchingOptions.builder().maximumBatchSize(5).build();

    assertThrows(
        IllegalArgumentException.class,
        () -> loader.loadRuntime(QWEN, ModelLoadOptions.defaults(), batching));
    assertTrue(backend.closed());
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

  @Test
  void opensARerankerOnlyWhenEvidenceMatchesTheExactArtifact() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = models.resolve(MINILM_RERANKER).orElseThrow();
    StubRerankingModel reranker = new StubRerankingModel();
    AtomicReference<ModelJarDescriptor> selectedDescriptor = new AtomicReference<>();
    AtomicReference<ModelRerankingQualificationRegistry.Entry> selectedQualification =
        new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            models,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelToolQualificationRegistry.fromClasspath(),
            ModelEmbeddingQualificationRegistry.fromClasspath(),
            ModelRerankingQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> Path.of("verified-reranker.gguf"),
            (backendName, path, configuration) -> new StubBackend(),
            (path, qualification, configuration) -> new StubEmbeddingBackend(),
            (path, candidate, qualification) -> {
              selectedDescriptor.set(candidate);
              selectedQualification.set(qualification);
              return reranker;
            },
            Map::of,
            List::of);

    try (var runtime = loader.loadRerankingRuntime(MINILM_RERANKER, ModelLoadOptions.defaults())) {
      assertSame(reranker, runtime.model());
      assertEquals(descriptor, runtime.descriptor());
      assertEquals(descriptor, selectedDescriptor.get());
      assertEquals(descriptor.alias(), runtime.qualification().modelId());
      assertEquals(descriptor.sha256().orElseThrow(), selectedQualification.get().artifactSha256());
      assertEquals(1.0, runtime.model().score("query", "longer"));
      assertFalse(reranker.closed);
    }
    assertTrue(reranker.closed);
  }

  @Test
  void opensSpeechOnlyThroughArtifactBoundQualifiedEvidence() throws Exception {
    String sha = "4758ad908395dc73a1b973d9a29ce96941f4328594d1c6c1223e7b7710a6a131";
    ModelJarDescriptor descriptor = speechDescriptor(sha);
    ModelJar model = ModelJar.of(descriptor.markerCoordinate().toString());
    StubSpeechModel speech = new StubSpeechModel();
    AtomicReference<ModelSpeechQualificationRegistry.Entry> selected = new AtomicReference<>();
    ModelJars loader =
        new ModelJars(
            new InMemoryModelJarRegistry(List.of(descriptor)),
            ModelRagQualificationRegistry.fromClasspath(),
            ModelToolQualificationRegistry.fromClasspath(),
            ModelEmbeddingQualificationRegistry.fromClasspath(),
            ModelRerankingQualificationRegistry.fromClasspath(),
            speechQualifications(sha),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> Path.of("verified-soprano.gguf"),
            (backendName, path, configuration) -> new StubBackend(),
            (path, qualification, configuration) -> new StubEmbeddingBackend(),
            (path, candidate, qualification) -> new StubRerankingModel(),
            (path, candidate, qualification) -> {
              selected.set(qualification);
              return speech;
            },
            Map::of,
            () -> List.of("--enable-native-access=ALL-UNNAMED"));

    try (var runtime = loader.loadSpeechRuntime(model, ModelLoadOptions.defaults())) {
      assertSame(speech, runtime.model());
      assertEquals(descriptor, runtime.descriptor());
      assertEquals(sha, runtime.qualification().artifactSha256());
      assertEquals("rust-ffm", selected.get().backend());
      assertEquals(32_000, runtime.model().synthesize("hello").sampleRate());
      assertFalse(speech.closed);
    }
    assertTrue(speech.closed);
  }

  private static ModelSpeechQualificationRegistry speechQualifications(String sha)
      throws Exception {
    String properties =
        """
        modeljars.speechQualifications.schemaVersion=1
        speechQualification.soprano-q8.model=Soprano 1.1 80M Q8_0
        speechQualification.soprano-q8.backend=rust-ffm
        speechQualification.soprano-q8.backendVersion=models-0.3.29
        speechQualification.soprano-q8.workload=speech-oracle-streaming-latency-v1
        speechQualification.soprano-q8.artifactSha256=%s
        speechQualification.soprano-q8.artifactSizeBytes=123162336
        speechQualification.soprano-q8.report=benchmark-results/audio/soprano/report.json
        speechQualification.soprano-q8.reportSha256=1111111111111111111111111111111111111111111111111111111111111111
        speechQualification.soprano-q8.qualified=true
        speechQualification.soprano-q8.oracleBackend=official-soprano-pytorch
        speechQualification.soprano-q8.oracleVersion=12fac06eb8fa53bad8b3941d3cb11e9c869477c4
        speechQualification.soprano-q8.probes=3
        speechQualification.soprano-q8.minimumPcmCosine=0.998
        speechQualification.soprano-q8.minimumSignalToDifferenceDb=24.9
        speechQualification.soprano-q8.sampleRate=32000
        speechQualification.soprano-q8.channels=1
        speechQualification.soprano-q8.streaming=true
        speechQualification.soprano-q8.firstAudioBeforeCompletion=true
        speechQualification.soprano-q8.trials=5
        speechQualification.soprano-q8.p95RealTimeFactor=1.5
        speechQualification.soprano-q8.p95TimeToFirstAudioMillis=500
        speechQualification.soprano-q8.peakRssBytes=536870912
        """
            .formatted(sha);
    return ModelSpeechQualificationRegistry.parse(
        new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1)));
  }

  private static ModelJarDescriptor speechDescriptor(String sha) {
    return new ModelJarDescriptor(
        "soprano-q8",
        "hf://WalkingCat/Soprano-1.1-80M-GGUF",
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:walkingcat.soprano-1.1-80m-gguf.q8_0:1.1.0-q8_0.1"),
        ModelVersion.parse("1.1.0"),
        "q8_0",
        "gguf",
        "soprano",
        "Q8_0",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/WalkingCat/Soprano-1.1-80M-GGUF")),
        Optional.of(
            URI.create(
                "https://huggingface.co/WalkingCat/Soprano-1.1-80M-GGUF/resolve/revision/soprano-1.1-80m-q8_0.gguf")),
        Optional.of("36c6f47cf91421b7f0cf3d862d28ae2e41aab3f2"),
        Optional.of(sha),
        Optional.of(123_162_336L),
        Optional.of("Apache-2.0"),
        Set.of("text-to-speech"),
        Set.of("streaming-audio"),
        List.of(),
        Map.of("rust-ffm", true, "pure-java", true),
        Optional.of("Soprano 1.1 80M Q8_0"),
        Optional.empty(),
        Optional.empty(),
        Set.of("audio"),
        ModelDimensions.unknown());
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

  private static final class StubRerankingModel implements RerankingModel {
    private boolean closed;

    @Override
    public double score(String query, String document) {
      return document.length() - query.length();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class StubSpeechModel implements TextToSpeechModel {
    private boolean closed;

    @Override
    public String modelName() {
      return "soprano-fixture";
    }

    @Override
    public BackendDiagnostics diagnostics() {
      return BackendDiagnostics.unavailable("stub-speech");
    }

    @Override
    public PcmAudio synthesize(String text, SpeechSynthesisOptions options) {
      return new PcmAudio(32_000, 1, new float[] {0.1f, -0.1f});
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static ModelJarDescriptor activatedAdapterDescriptor(ModelJarDescriptor base) {
    String weightsSha = "a".repeat(64);
    String manifestSha = "b".repeat(64);
    return new ModelJarDescriptor(
        "qwen3-1.7b-tools-r32",
        "integrallis/qwen3-1.7b-tools-r32",
        ModelJarCoordinate.parse(
            "org.modeljars.integrallis:qwen3-1.7b-tools-r32.safetensors:1.0.0-r32.1"),
        ModelVersion.parse("1.0.0"),
        "r32",
        "safetensors",
        "qwen3-activated-lora",
        "F32",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://github.com/integrallis/models")),
        Optional.empty(),
        Optional.of("c".repeat(40)),
        Optional.of(weightsSha),
        Optional.of(1024L),
        Optional.of("Apache-2.0"),
        Set.of("composition-component"),
        Set.of("multi-file-artifact", "activated-lora-adapter"),
        List.of(
            new ModelArtifactFile(
                "adapter.safetensors",
                "weights",
                URI.create("https://example.invalid/adapter.safetensors"),
                weightsSha,
                1024L),
            new ModelArtifactFile(
                "adapter-manifest.json",
                "config",
                URI.create("https://example.invalid/adapter-manifest.json"),
                manifestSha,
                512L)),
        Map.of("pure-java", true),
        Optional.of("Qwen3 1.7B tool adapter r32"),
        Optional.of("Activated-LoRA composition component for " + base.alias()),
        Optional.empty(),
        Set.of("tool-use"),
        ModelDimensions.unknown());
  }

  private static ModelComponentQualificationRegistry componentQualificationRegistry(
      ModelJarDescriptor adapter, ModelJarDescriptor base, boolean qualified) {
    Properties properties = new Properties();
    properties.setProperty("modeljars.componentQualifications.schemaVersion", "1");
    properties.setProperty("modeljars.componentQualifications.generatedAt", "2026-09-13T16:00:00Z");
    properties.setProperty(
        "modeljars.componentQualifications.policyVersion", "activated-adapter-component-v1");
    properties.setProperty("modeljars.componentQualifications.modelsRevision", "1".repeat(40));
    properties.setProperty("modeljars.componentQualifications.evidenceRevision", "2".repeat(40));
    properties.setProperty(
        "modeljars.componentQualifications.qualifiedModels", qualified ? "1" : "0");
    properties.setProperty(
        "modeljars.componentQualifications.rejectedModels", qualified ? "0" : "1");
    String prefix = "componentQualification." + adapter.alias() + ".";
    properties.setProperty(prefix + "baseModelId", base.alias());
    properties.setProperty(prefix + "baseArtifactSha256", base.sha256().orElseThrow());
    properties.setProperty(
        prefix + "baseArtifactSizeBytes", Long.toString(base.sizeBytes().orElseThrow()));
    properties.setProperty(prefix + "artifactSha256", adapter.sha256().orElseThrow());
    properties.setProperty(
        prefix + "artifactSizeBytes", Long.toString(adapter.sizeBytes().orElseThrow()));
    properties.setProperty(
        prefix + "artifactBundleSizeBytes",
        Long.toString(adapter.files().stream().mapToLong(ModelArtifactFile::sizeBytes).sum()));
    properties.setProperty(prefix + "artifactBundleSha256", artifactBundleSha256(adapter.files()));
    properties.setProperty(prefix + "minimumSharedPrefixTokens", "256");
    properties.setProperty(
        prefix + "reportUri",
        "https://raw.githubusercontent.com/integrallis/models/" + "2".repeat(40) + "/report.json");
    properties.setProperty(prefix + "reportSha256", "3".repeat(64));
    properties.setProperty(prefix + "qualified", Boolean.toString(qualified));
    properties.setProperty(prefix + "artifactFile.count", Integer.toString(adapter.files().size()));
    for (int index = 0; index < adapter.files().size(); index++) {
      ModelArtifactFile file = adapter.files().get(index);
      String filePrefix = prefix + "artifactFile." + "%03d".formatted(index) + ".";
      properties.setProperty(filePrefix + "path", file.path());
      properties.setProperty(filePrefix + "role", file.role());
      properties.setProperty(filePrefix + "sha256", file.sha256());
      properties.setProperty(filePrefix + "sizeBytes", Long.toString(file.sizeBytes()));
    }
    return ModelComponentQualificationRegistry.fromProperties(properties);
  }

  private static String artifactBundleSha256(List<ModelArtifactFile> files) {
    String identity =
        files.stream()
            .sorted(Comparator.comparing(ModelArtifactFile::path))
            .map(file -> file.path() + "\t" + file.sizeBytes() + "\t" + file.sha256() + "\n")
            .collect(java.util.stream.Collectors.joining());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static class StubBackend implements BatchInferenceBackend {
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
    private int raggedPrefillCalls;

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
    public int maxBatchSize() {
      return 4;
    }

    @Override
    public InferenceSession openSession() {
      return new StubSession();
    }

    @Override
    public boolean supportsRaggedPrefillBatch() {
      return true;
    }

    @Override
    public LogitBatch prefillBatch(InferenceSession[] sessions, int[][] tokenBatches) {
      raggedPrefillCalls++;
      return BatchInferenceBackend.super.prefillBatch(sessions, tokenBatches);
    }

    @Override
    public float[] forward(InferenceSession session, int token, int position) {
      StubSession state = requireStubSession(session);
      state.position = position + 1;
      return forward(token, position);
    }

    @Override
    public LogitBatch forwardBatch(InferenceSession[] sessions, int[] tokens) {
      float[] logits = new float[sessions.length * 2];
      for (int index = 0; index < sessions.length; index++) {
        float[] row = forward(sessions[index], tokens[index], sessions[index].checkpoint());
        System.arraycopy(row, 0, logits, index * 2, row.length);
      }
      return new LogitBatch(sessions.length, 2, logits);
    }

    @Override
    public void rewind(InferenceSession session, int checkpoint) {
      requireStubSession(session).position = checkpoint;
    }

    @Override
    public void reset(InferenceSession session) {
      requireStubSession(session).position = 0;
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

    private static StubSession requireStubSession(InferenceSession session) {
      if (!(session instanceof StubSession state)) {
        throw new IllegalArgumentException("foreign session");
      }
      return state;
    }
  }

  private static final class StubSharedBackend extends StubBackend
      implements SharedPrefixInferenceBackend {
    private final ActivatedAdapterMetadata adapter;

    private StubSharedBackend(String baseArtifactSha256, String adapterSha256) {
      String hash = "d".repeat(64);
      ActivatedAdapterMetadata.TrainingSource source =
          new ActivatedAdapterMetadata.TrainingSource(
              "primary", "fixture", "e".repeat(40), "train.jsonl", hash);
      this.adapter =
          new ActivatedAdapterMetadata(
              "fixture/base",
              "f".repeat(40),
              baseArtifactSha256,
              Map.of("tokenizer.json", hash),
              adapterSha256,
              32,
              64,
              List.of(1),
              new ActivatedAdapterMetadata.TrainingProvenance(
                  List.of(source), 1, hash, hash, hash, "formatter.py", hash));
    }

    @Override
    public boolean supportsActivatedBranch() {
      return true;
    }

    @Override
    public Optional<ActivatedAdapterMetadata> activatedAdapter() {
      return Optional.of(adapter);
    }

    @Override
    public SharedInferencePrefix freezePrefix(InferenceSession source) {
      throw new UnsupportedOperationException("not needed by this lifecycle test");
    }

    @Override
    public InferenceSession fork(SharedInferencePrefix prefix, Branch branch) {
      throw new UnsupportedOperationException("not needed by this lifecycle test");
    }

    @Override
    public boolean sharesPrefixStorage(InferenceSession first, InferenceSession second) {
      return false;
    }
  }

  private static final class StubSession implements InferenceSession {
    private int position;
    private boolean closed;

    @Override
    public int checkpoint() {
      return position;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
