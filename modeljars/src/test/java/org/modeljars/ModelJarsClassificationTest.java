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

import com.integrallis.models.api.SchemaClassificationModel;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelJarsClassificationTest {
  private static final String SHA = "a".repeat(64);
  private static final List<ModelArtifactFile> FILES =
      List.of(
          new ModelArtifactFile(
              "model.safetensors",
              "weights",
              URI.create("https://example.invalid/weights"),
              SHA,
              12),
          new ModelArtifactFile(
              "tokenizer.json",
              "tokenizer",
              URI.create("https://example.invalid/tokenizer"),
              SHA,
              12));

  private static ModelJarDescriptor descriptor(List<ModelArtifactFile> files) {
    return new ModelJarDescriptor(
        "fixture",
        "hf://fixture/classifier",
        ModelJarCoordinate.parse("org.modeljars.test:classifier:0.0.0"),
        ModelVersion.parse("0.0.0"),
        "f32",
        "safetensors",
        "gliner2",
        "F32",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of("immutable-test"),
        Optional.of(SHA),
        Optional.of(12L),
        Optional.of("Apache-2.0"),
        Set.of("schema-classification"),
        Set.of("multi-file-artifact"),
        files,
        Map.of("rust-ffm", true),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }

  private static ModelClassificationQualificationRegistry registry() {
    var performance = new ArrayList<ModelClassificationQualification.PerformanceBucket>();
    for (int pair = 1; pair <= 3; pair++)
      for (String shape : ModelClassificationQualification.performanceShapes())
        performance.add(
            new ModelClassificationQualification.PerformanceBucket(pair, shape, 30, 1, 1));
    return new ModelClassificationQualificationRegistry(
        List.of(
            new ModelClassificationQualification(
                "fixture",
                "rust-ffm",
                "models-0.0.0",
                URI.create("https://example.invalid/report"),
                SHA,
                FILES,
                ModelClassificationQualification.qualityFloors(),
                0,
                0,
                1529,
                0,
                true,
                performance,
                true,
                true)));
  }

  private static ModelJars loader(ModelJarDescriptor descriptor, AtomicBoolean installed) {
    return new ModelJars(
        ModelJarRegistry.of(List.of(descriptor)),
        ModelRagQualificationRegistry.fromClasspath(),
        ModelPerformanceProfileRegistry.fromClasspath(),
        (d, o) -> {
          installed.set(true);
          return Path.of("verified-snapshot");
        },
        (b, p, c) -> {
          throw new AssertionError("generative backend must not be invoked");
        },
        Map::of,
        () -> List.of("--enable-native-access=ALL-UNNAMED"));
  }

  @Test
  void ownsClassifierAndBindsEverySnapshotFile() {
    var installed = new AtomicBoolean();
    var closed = new AtomicBoolean();
    var d = descriptor(FILES);
    var model =
        new SchemaClassificationModel() {
          public List<Result> classify(Request request) {
            return List.of();
          }

          public void close() {
            closed.set(true);
          }
        };
    try (var runtime =
        loader(d, installed)
            .loadClassificationRuntime(
                ModelJar.of(d.markerCoordinate().toString()),
                ModelLoadOptions.defaults(),
                registry(),
                path -> {
                  assertEquals(Path.of("verified-snapshot"), path);
                  return model;
                })) {
      assertSame(model, runtime.model());
      assertTrue(installed.get());
      assertEquals(d, runtime.descriptor());
      assertTrue(runtime.qualification().qualified());
      assertFalse(runtime.qualification().confidenceQualified());
    }
    assertTrue(closed.get());
  }

  @Test
  void alteredTokenizerAndUnsupportedBackendFailBeforeDownload() {
    var altered = new ArrayList<>(FILES);
    var tokenizer = altered.getLast();
    altered.set(
        1,
        new ModelArtifactFile(
            tokenizer.path(), tokenizer.role(), tokenizer.downloadUri(), "b".repeat(64), 12));
    for (var files : List.of(altered, FILES)) {
      var installed = new AtomicBoolean();
      var d = descriptor(files);
      var options =
          files == FILES
              ? ModelLoadOptions.builder().backend(ModelBackend.JAVA).build()
              : ModelLoadOptions.defaults();
      assertThrows(
          ModelJarException.class,
          () ->
              loader(d, installed)
                  .loadClassificationRuntime(
                      ModelJar.of(d.markerCoordinate().toString()),
                      options,
                      registry(),
                      p -> {
                        throw new AssertionError("must not load");
                      }));
      assertFalse(installed.get());
    }
  }
}
