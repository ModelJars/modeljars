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

import static org.junit.jupiter.api.Assertions.*;

import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.ImageInferenceBackend;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.OptimizationDecision;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.Tokenizer;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelJarsVisionTest {
  private static final String SHA = "a".repeat(64);
  private static final List<ModelArtifactFile> FILES =
      List.of(
          new ModelArtifactFile(
              "text.gguf", "weights", URI.create("https://example.invalid/text"), SHA, 12),
          new ModelArtifactFile(
              "mmproj.gguf",
              "vision-projector",
              URI.create("https://example.invalid/vision"),
              "b".repeat(64),
              13));

  static ModelJarDescriptor descriptor(List<ModelArtifactFile> files) {
    return new ModelJarDescriptor(
        "fixture",
        "hf://fixture/vision",
        ModelJarCoordinate.parse("org.modeljars.test:vision:0.0.0"),
        ModelVersion.parse("0.0.0"),
        "q4_k_m",
        "gguf",
        "llama",
        "Q4_K_M",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of("immutable-test"),
        Optional.of(SHA),
        Optional.of(12L),
        Optional.of("llama2"),
        Set.of("image-understanding"),
        Set.of("multi-file-artifact", "llava-1.5"),
        files,
        Map.of("rust-ffm", true),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }

  static ModelVisionQualification evidence(
      double ratio, double agreement, double loss, boolean qualified) {
    var datasets =
        Map.of(
            "vqav2",
            new ModelVisionQualification.Dataset(32, .75 - loss, .75, 8, .2),
            "textvqa",
            new ModelVisionQualification.Dataset(32, .4, .4, 8, .2));
    var timings = new ArrayList<ModelVisionQualification.Performance>();
    for (String dataset : List.of("vqav2", "textvqa"))
      for (int i = 0; i < 2; i++)
        timings.add(
            new ModelVisionQualification.Performance(
                dataset, dataset + "-" + i, 1, 3, ratio, 1, ratio));
    return new ModelVisionQualification(
        "fixture",
        "models@branch+" + "c".repeat(40),
        URI.create("https://example.invalid/report"),
        SHA,
        SHA,
        FILES,
        datasets,
        agreement,
        timings,
        true,
        qualified);
  }

  private ModelJars loader(ModelJarDescriptor descriptor, AtomicBoolean installed) {
    return new ModelJars(
        ModelJarRegistry.of(List.of(descriptor)),
        ModelRagQualificationRegistry.fromClasspath(),
        ModelPerformanceProfileRegistry.fromClasspath(),
        (d, o) -> {
          installed.set(true);
          return Path.of("snapshot");
        },
        (b, p, c) -> {
          throw new AssertionError("text-only loader invoked");
        },
        Map::of,
        () -> List.of("--enable-native-access=ALL-UNNAMED"));
  }

  @Test
  void verifiesPairAndOwnsBackend() {
    var installed = new AtomicBoolean();
    var descriptor = descriptor(FILES);
    var backend = new FakeBackend();
    var registry = new ModelVisionQualificationRegistry(List.of(evidence(1, 1, 0, true)));
    try (var runtime =
        loader(descriptor, installed)
            .loadVisionRuntime(
                ModelJar.of(descriptor.markerCoordinate().toString()),
                ModelLoadOptions.defaults(),
                registry,
                (text, projector) -> {
                  assertEquals(Path.of("snapshot/text.gguf"), text);
                  assertEquals(Path.of("snapshot/mmproj.gguf"), projector);
                  return backend;
                })) {
      assertTrue(installed.get());
      assertSame(backend, runtime.backend());
      assertEquals("yes", runtime.answer(new byte[] {1}, "Is it visible?"));
      assertEquals(
          List.of("reset", "text:0:2", "image:2", "text:578:1", "token:579"), backend.calls);
      assertEquals("yes", runtime.answer(new byte[] {1}, "Again?"));
      assertEquals(2, Collections.frequency(backend.calls, "reset"));
    }
    assertTrue(backend.closed);
  }

  @Test
  void rejectsAndClosesAnIncompleteNativeExecutionPlan() {
    for (String missing :
        List.of("encoder", "decoder", "attention", "disabled", "reference-arithmetic")) {
      var backend = nativeBackend(missing);
      assertThrows(ModelJarException.class, () -> ModelJars.requireQualifiedVisionKernels(backend));
      assertTrue(backend.closed);
    }
    var supported = nativeBackend("");
    assertSame(supported, ModelJars.requireQualifiedVisionKernels(supported));
    assertFalse(supported.closed);
    supported.close();
  }

  private static FakeBackend nativeBackend(String missing) {
    return new FakeBackend() {
      @Override
      public BackendDiagnostics diagnostics() {
        return new BackendDiagnostics(
            "fixture",
            "fixture",
            Map.of(
                "vision-projections",
                missing.equals("encoder") ? "java" : "rust-matrixmultiply",
                "decoder-attention",
                missing.equals("attention") ? "Java" : "NativeCausalAttention"),
            List.of(
                new OptimizationDecision(
                    "grouped-projections",
                    missing.equals("disabled")
                        ? OptimizationStatus.DISABLED
                        : OptimizationStatus.ENABLED,
                    "fixture",
                    Map.of(
                        "implementation",
                        switch (missing) {
                          case "decoder" -> "java";
                          case "reference-arithmetic" -> "rust-ffm-quantized-v14";
                          default -> "rust-ffm-quantized-v16-packed-q4-k";
                        }))));
      }
    };
  }

  @Test
  void changedProjectorAndJavaSelectionFailBeforeInstallation() {
    var changed = new ArrayList<>(FILES);
    changed.set(
        1,
        new ModelArtifactFile(
            "mmproj.gguf", "vision-projector", FILES.get(1).downloadUri(), "c".repeat(64), 13));
    for (var files : List.of(FILES, changed)) {
      var d = descriptor(files);
      var installed = new AtomicBoolean();
      var options =
          files == FILES
              ? ModelLoadOptions.builder().backend(ModelBackend.JAVA).build()
              : ModelLoadOptions.defaults();
      assertThrows(
          ModelJarException.class,
          () ->
              loader(d, installed)
                  .loadVisionRuntime(
                      ModelJar.of(d.markerCoordinate().toString()),
                      options,
                      new ModelVisionQualificationRegistry(List.of(evidence(1, 1, 0, true))),
                      (t, p) -> {
                        throw new AssertionError("must not load");
                      }));
      assertFalse(installed.get());
    }
  }

  @Test
  void failedOrMissingEvidenceCannotLoad() {
    var d = descriptor(FILES);
    for (var entries :
        List.of(List.<ModelVisionQualification>of(), List.of(evidence(1.2, 1, 0, false)))) {
      var installed = new AtomicBoolean();
      assertThrows(
          ModelJarException.class,
          () ->
              loader(d, installed)
                  .loadVisionRuntime(
                      ModelJar.of(d.markerCoordinate().toString()),
                      ModelLoadOptions.defaults(),
                      new ModelVisionQualificationRegistry(entries),
                      (t, p) -> {
                        throw new AssertionError("must not load");
                      }));
      assertFalse(installed.get());
    }
  }

  @Test
  void cannotForgePassingEvidenceOrUseNonfiniteMetrics() {
    assertThrows(IllegalArgumentException.class, () -> evidence(1.11, 1, 0, true));
    assertThrows(IllegalArgumentException.class, () -> evidence(1, .98, 0, true));
    assertThrows(IllegalArgumentException.class, () -> evidence(1, 1, .006, true));
    assertThrows(IllegalArgumentException.class, () -> evidence(Double.NaN, 1, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelVisionQualification.Dataset(31, .7, .7, 8, .2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelVisionQualification.Dataset(32, .7, .7, 8, Double.NaN));
    assertThrows(
        ModelJarException.class,
        () ->
            new ModelVisionQualificationRegistry(
                List.of(evidence(1, 1, 0, true), evidence(1, 1, 0, true))));
  }

  @Test
  void rejectsOversizedQuestionsAndNonfiniteLogits() {
    var backend = new FakeBackend();
    try (var runtime =
        new ModelJarVisionRuntime(backend, descriptor(FILES), evidence(1, 1, 0, true))) {
      backend.capacity = 600;
      assertThrows(
          IllegalArgumentException.class, () -> runtime.answer(new byte[] {1}, "Question"));
      assertTrue(backend.calls.isEmpty());
      backend.capacity = 1024;
      backend.nonfinite = true;
      assertThrows(IllegalStateException.class, () -> runtime.answer(new byte[] {1}, "Question"));
    }
  }

  private static class FakeBackend implements ImageInferenceBackend {
    final List<String> calls = new ArrayList<>();
    boolean closed;
    boolean nonfinite;
    int capacity = 1024;

    public String name() {
      return "fixture";
    }

    public ModelMetadata metadata() {
      throw new UnsupportedOperationException();
    }

    public int imageTokenCount() {
      return 576;
    }

    public int contextCapacity() {
      return capacity;
    }

    public Tokenizer tokenizer() {
      return new Tokenizer() {
        public int[] encode(String text) {
          return new int[] {1, 3};
        }

        public String decode(int[] tokens) {
          return tokens.length == 0 ? "" : "yes";
        }

        public String decode(int token) {
          return "yes";
        }

        public int vocabSize() {
          return 4;
        }

        public int bosToken() {
          return 1;
        }

        public int eosToken() {
          return 2;
        }
      };
    }

    public float[] forward(int token, int position) {
      calls.add("token:" + position);
      return new float[] {0, 0, 1, 0};
    }

    public float[] prefill(int[] tokens, int position) {
      calls.add("text:" + position + ":" + tokens.length);
      return new float[] {0, 0, 0, nonfinite ? Float.NaN : 1};
    }

    public float[] prefillImage(byte[] image, int position) {
      calls.add("image:" + position);
      return new float[] {0, 0, 0, 1};
    }

    public void reset() {
      calls.add("reset");
    }

    public void close() {
      closed = true;
    }
  }
}
