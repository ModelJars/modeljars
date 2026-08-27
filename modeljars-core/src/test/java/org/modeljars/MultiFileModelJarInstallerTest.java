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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiFileModelJarInstallerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void parsesEveryPinnedFileFromMarkerProperties() throws IOException {
    Fixture fixture = fixture();

    ModelJarDescriptor descriptor = fixture.descriptor();

    assertEquals(
        List.of(
            "config.json", "weights/model.safetensors", "tokenizer.json", "tokenizer_config.json"),
        descriptor.files().stream().map(ModelArtifactFile::path).toList());
    assertEquals("model-weights", descriptor.files().get(1).role());
    assertEquals(sha256(fixture.model()), descriptor.files().get(1).sha256());
    assertEquals(Files.size(fixture.model()), descriptor.files().get(1).sizeBytes());
    assertEquals(
        temporaryDirectory
            .resolve("shared-cache/sha256")
            .resolve(descriptor.sha256().orElseThrow().substring(0, 2))
            .resolve(descriptor.sha256().orElseThrow())
            .resolve("weights")
            .resolve("model.safetensors")
            .toAbsolutePath()
            .normalize(),
        ModelJarCache.artifactPath(descriptor, temporaryDirectory.resolve("shared-cache")));
  }

  @Test
  void installsAndVerifiesTheCompleteSafetensorsDirectory() throws IOException {
    Fixture fixture = fixture();
    ModelJarDescriptor descriptor = fixture.descriptor();
    ModelJarInstaller installer =
        new ModelJarInstaller(new InMemoryModelJarRegistry(List.of(descriptor)));

    Path installed = installer.install(descriptor, fixture.destination());

    assertEquals(fixture.destination().getParent().getParent(), installed);
    for (ModelArtifactFile file : descriptor.files()) {
      assertArrayEquals(
          Files.readAllBytes(fixture.upstream().resolve(file.path())),
          Files.readAllBytes(installed.resolve(file.path())));
    }
    assertEquals(installed, installer.verifyCached(descriptor, fixture.destination()));
  }

  @Test
  void offlineVerificationRejectsAMissingSupplementaryFile() throws IOException {
    Fixture fixture = fixture();
    ModelJarDescriptor descriptor = fixture.descriptor();
    ModelJarInstaller installer =
        new ModelJarInstaller(new InMemoryModelJarRegistry(List.of(descriptor)));
    installer.install(descriptor, fixture.destination());
    Files.delete(fixture.destination().getParent().getParent().resolve("tokenizer.json"));

    ModelJarException failure =
        assertThrows(
            ModelJarException.class,
            () -> installer.verifyCached(descriptor, fixture.destination()));

    assertEquals(
        "Model artifact is not available in the offline cache: "
            + fixture.destination().getParent().getParent().resolve("tokenizer.json"),
        failure.getMessage());
  }

  private Fixture fixture() throws IOException {
    Path upstream = Files.createDirectories(temporaryDirectory.resolve("upstream"));
    Path config = Files.writeString(upstream.resolve("config.json"), "{\"model_type\":\"qwen2\"}");
    Path weights = Files.createDirectories(upstream.resolve("weights"));
    Path model =
        Files.write(weights.resolve("model.safetensors"), new byte[] {4, 8, 15, 16, 23, 42});
    Path tokenizer = Files.writeString(upstream.resolve("tokenizer.json"), "{\"version\":\"1.0\"}");
    Path tokenizerConfig =
        Files.writeString(
            upstream.resolve("tokenizer_config.json"), "{\"eos_token\":\"<|im_end|>\"}");
    Path destination = temporaryDirectory.resolve("cache/weights/model.safetensors");

    Properties properties = new Properties();
    String prefix = "model.qwen25_hf.";
    properties.setProperty(prefix + "sourceId", "hf://Qwen/Qwen2.5-0.5B-Instruct");
    properties.setProperty(
        prefix + "markerCoordinate",
        "org.modeljars.huggingface:qwen.qwen2-5-0-5b-instruct.bf16:2.5.0-bf16.1");
    properties.setProperty(prefix + "modelVersion", "2.5.0");
    properties.setProperty(prefix + "variant", "bf16");
    properties.setProperty(prefix + "format", "safetensors");
    properties.setProperty(prefix + "architecture", "qwen2");
    properties.setProperty(prefix + "quantization", "BF16");
    properties.setProperty(prefix + "path", destination.toString());
    properties.setProperty(prefix + "sourceUri", upstream.toUri().toString());
    properties.setProperty(prefix + "downloadUri", model.toUri().toString());
    properties.setProperty(prefix + "revision", "7ae557604adf67be50417f59c2c2f167def9a775");
    properties.setProperty(prefix + "sha256", sha256(model));
    properties.setProperty(prefix + "sizeBytes", Long.toString(Files.size(model)));
    properties.setProperty(prefix + "license", "Apache-2.0");
    properties.setProperty(prefix + "capabilities", "text-generation");
    properties.setProperty(prefix + "features", "multi-file-artifact");
    properties.setProperty(prefix + "backend.pure-java", "true");
    List<Path> files = List.of(config, model, tokenizer, tokenizerConfig);
    properties.setProperty(prefix + "file.count", Integer.toString(files.size()));
    for (int index = 0; index < files.size(); index++) {
      Path file = files.get(index);
      String filePrefix = prefix + "file." + "%03d".formatted(index) + ".";
      properties.setProperty(
          filePrefix + "path", upstream.relativize(file).toString().replace('\\', '/'));
      properties.setProperty(
          filePrefix + "role", file.equals(model) ? "model-weights" : "runtime-metadata");
      properties.setProperty(filePrefix + "sha256", sha256(file));
      properties.setProperty(filePrefix + "sizeBytes", Long.toString(Files.size(file)));
    }
    ModelJarDescriptor descriptor =
        PropertiesModelJarRegistry.fromProperties(properties).descriptors().getFirst();
    return new Fixture(upstream, model, destination, descriptor);
  }

  private static String sha256(Path path) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private record Fixture(
      Path upstream, Path model, Path destination, ModelJarDescriptor descriptor) {}
}
