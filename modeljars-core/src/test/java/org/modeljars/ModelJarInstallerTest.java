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
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelJarInstallerTest {
  @TempDir Path tempDir;

  @Test
  void downloadsAndVerifiesAnImmutableModelArtifact() throws IOException {
    byte[] modelBytes = "verified model bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upstream.gguf");
    Path destination = tempDir.resolve("cache/model.gguf");
    Files.write(source, modelBytes);

    ModelJarDescriptor descriptor = descriptor(source, destination, sha256(modelBytes));
    ModelJarInstaller installer =
        new ModelJarInstaller(new InMemoryModelJarRegistry(java.util.List.of(descriptor)));

    Path installed =
        installer.install(ModelJarRequirement.forSource("hf://example/model").build());

    assertEquals(destination, installed);
    assertArrayEquals(modelBytes, Files.readAllBytes(installed));
  }

  @Test
  void rejectsAnExistingArtifactThatDoesNotMatchTheMarker() throws IOException {
    byte[] modelBytes = "verified model bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upstream.gguf");
    Path destination = tempDir.resolve("cache/model.gguf");
    Files.write(source, modelBytes);
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, "corrupt");

    ModelJarInstaller installer =
        new ModelJarInstaller(
            new InMemoryModelJarRegistry(
                java.util.List.of(descriptor(source, destination, sha256(modelBytes)))));

    assertThrows(
        ModelJarException.class,
        () -> installer.install(ModelJarRequirement.forSource("hf://example/model").build()));
  }

  @Test
  void rejectsDownloadedBytesThatDoNotMatchTheMarker() throws IOException {
    byte[] modelBytes = "untrusted bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upstream.gguf");
    Path destination = tempDir.resolve("cache/model.gguf");
    Files.write(source, modelBytes);

    ModelJarInstaller installer =
        new ModelJarInstaller(
            new InMemoryModelJarRegistry(
                java.util.List.of(descriptor(source, destination, "0".repeat(64)))));

    assertThrows(
        ModelJarException.class,
        () -> installer.install(ModelJarRequirement.forSource("hf://example/model").build()));
    assertEquals(false, Files.exists(destination));
  }

  private static ModelJarDescriptor descriptor(Path source, Path destination, String sha256) {
    Properties properties = new Properties();
    properties.setProperty("model.example.sourceId", "hf://example/model");
    properties.setProperty(
        "model.example.markerCoordinate", "org.modeljars.huggingface:example.model.q8_0:1.0.0-q8_0.1");
    properties.setProperty("model.example.modelVersion", "1.0.0");
    properties.setProperty("model.example.variant", "q8_0");
    properties.setProperty("model.example.format", "gguf");
    properties.setProperty("model.example.architecture", "llama");
    properties.setProperty("model.example.quantization", "Q8_0");
    properties.setProperty("model.example.path", destination.toString());
    properties.setProperty("model.example.sourceUri", "https://example.invalid/model");
    properties.setProperty("model.example.downloadUri", source.toUri().toString());
    properties.setProperty("model.example.revision", "0123456789abcdef0123456789abcdef01234567");
    properties.setProperty("model.example.sha256", sha256);
    properties.setProperty("model.example.sizeBytes", Long.toString(fileSize(source)));
    properties.setProperty("model.example.license", "Apache-2.0");
    properties.setProperty("model.example.capabilities", "text-generation");
    properties.setProperty("model.example.backend.pure-java", "true");
    return PropertiesModelJarRegistry.fromProperties(properties).descriptors().getFirst();
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
