package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    Path installed = installer.install(ModelJar.of("hf://example/model"));

    assertEquals(destination, installed);
    assertArrayEquals(modelBytes, Files.readAllBytes(installed));
  }

  @Test
  void installsIntoAnExplicitContentAddressedCachePath() throws IOException {
    byte[] modelBytes = "content addressed model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upstream.gguf");
    Path markerDestination = tempDir.resolve("legacy/model.gguf");
    Path cacheDestination = tempDir.resolve("sha256/ab/abcdef/model.gguf");
    Files.write(source, modelBytes);
    ModelJarDescriptor descriptor =
        descriptor(source, markerDestination, sha256(modelBytes));
    ModelJarInstaller installer =
        new ModelJarInstaller(new InMemoryModelJarRegistry(List.of(descriptor)));

    Path installed = installer.install(descriptor, cacheDestination);

    assertEquals(cacheDestination, installed);
    assertArrayEquals(modelBytes, Files.readAllBytes(installed));
    assertFalse(Files.exists(markerDestination));
  }

  @Test
  void reportsDownloadVerificationAndCacheReadiness() throws IOException {
    byte[] modelBytes = "visible download".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("upstream.gguf");
    Path destination = tempDir.resolve("cache/model.gguf");
    Files.write(source, modelBytes);
    ModelJarDescriptor descriptor = descriptor(source, destination, sha256(modelBytes));
    List<String> progress = new ArrayList<>();
    ModelJarInstaller installer =
        new ModelJarInstaller(
            new InMemoryModelJarRegistry(List.of(descriptor)), ignored -> {}, progress::add);

    installer.install(descriptor, destination);

    assertTrue(progress.getFirst().contains("Downloading model example"));
    assertTrue(progress.stream().anyMatch(message -> message.contains("100%")));
    assertTrue(progress.stream().anyMatch(message -> message.contains("verifying SHA-256")));
    assertTrue(progress.getLast().contains("Model ready in cache"));

    progress.clear();
    installer.install(descriptor, destination);
    assertTrue(progress.getFirst().contains("Verifying cached model"));
    assertTrue(progress.getLast().contains("Model cache verified"));
  }

  @Test
  void verifiesAnOfflineArtifactWithoutAttemptingADownload() throws IOException {
    byte[] modelBytes = "offline model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path source = tempDir.resolve("missing-upstream.gguf");
    Path markerDestination = tempDir.resolve("legacy/model.gguf");
    Path cacheDestination = tempDir.resolve("sha256/ab/abcdef/model.gguf");
    Files.createDirectories(cacheDestination.getParent());
    Files.write(cacheDestination, modelBytes);
    ModelJarDescriptor descriptor =
        descriptor(source.toUri(), markerDestination, modelBytes.length, sha256(modelBytes));
    ModelJarInstaller installer =
        new ModelJarInstaller(new InMemoryModelJarRegistry(List.of(descriptor)));

    assertEquals(cacheDestination, installer.verifyCached(descriptor, cacheDestination));
    Files.delete(cacheDestination);
    assertThrows(
        ModelJarException.class,
        () -> installer.verifyCached(descriptor, cacheDestination));
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
        ModelJarException.class, () -> installer.install(ModelJar.of("hf://example/model")));
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
        ModelJarException.class, () -> installer.install(ModelJar.of("hf://example/model")));
    assertEquals(false, Files.exists(destination));
  }

  @Test
  void resumesAnInterruptedHttpDownload() throws IOException {
    byte[] modelBytes = modelBytes();
    int interruptionOffset = 97 * 1024;
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> resumeRange = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/model.gguf",
        exchange -> {
          int request = requests.incrementAndGet();
          if (request == 1) {
            exchange.sendResponseHeaders(200, modelBytes.length);
            try {
              exchange.getResponseBody().write(modelBytes, 0, interruptionOffset);
            } finally {
              exchange.close();
            }
            return;
          }

          String range = exchange.getRequestHeaders().getFirst("Range");
          resumeRange.set(range);
          int offset = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
          exchange
              .getResponseHeaders()
              .set(
                  "Content-Range",
                  "bytes " + offset + "-" + (modelBytes.length - 1) + "/" + modelBytes.length);
          exchange.sendResponseHeaders(206, modelBytes.length - offset);
          try {
            exchange.getResponseBody().write(modelBytes, offset, modelBytes.length - offset);
          } finally {
            exchange.close();
          }
        });
    server.start();

    try {
      Path destination = tempDir.resolve("cache/model.gguf");
      ModelJarDescriptor descriptor =
          descriptor(modelUri(server), destination, modelBytes.length, sha256(modelBytes));
      ModelJarInstaller installer =
          new ModelJarInstaller(new InMemoryModelJarRegistry(java.util.List.of(descriptor)));

      Path installed = installer.install(ModelJar.of("hf://example/model"));

      assertEquals(2, requests.get());
      assertEquals("bytes=" + interruptionOffset + "-", resumeRange.get());
      assertArrayEquals(modelBytes, Files.readAllBytes(installed));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restartsWhenAnHttpServerIgnoresTheResumeRange() throws IOException {
    byte[] modelBytes = modelBytes();
    int interruptionOffset = 97 * 1024;
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> resumeRange = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/model.gguf",
        exchange -> {
          if (requests.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(200, modelBytes.length);
            try {
              exchange.getResponseBody().write(modelBytes, 0, interruptionOffset);
            } finally {
              exchange.close();
            }
            return;
          }

          resumeRange.set(exchange.getRequestHeaders().getFirst("Range"));
          exchange.sendResponseHeaders(200, modelBytes.length);
          try {
            exchange.getResponseBody().write(modelBytes);
          } finally {
            exchange.close();
          }
        });
    server.start();

    try {
      Path destination = tempDir.resolve("cache/model.gguf");
      ModelJarDescriptor descriptor =
          descriptor(modelUri(server), destination, modelBytes.length, sha256(modelBytes));
      ModelJarInstaller installer =
          new ModelJarInstaller(new InMemoryModelJarRegistry(java.util.List.of(descriptor)));

      Path installed = installer.install(ModelJar.of("hf://example/model"));

      assertEquals(2, requests.get());
      assertEquals("bytes=" + interruptionOffset + "-", resumeRange.get());
      assertArrayEquals(modelBytes, Files.readAllBytes(installed));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void backsOffAndStopsAfterFiveInterruptedHttpResponses() throws IOException {
    byte[] modelBytes = modelBytes();
    AtomicInteger requests = new AtomicInteger();
    List<Integer> failedAttempts = new ArrayList<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/model.gguf",
        exchange -> {
          requests.incrementAndGet();
          String range = exchange.getRequestHeaders().getFirst("Range");
          int offset =
              range == null
                  ? 0
                  : Integer.parseInt(
                      range.substring("bytes=".length(), range.length() - 1));
          if (range == null) {
            exchange.sendResponseHeaders(200, modelBytes.length);
          } else {
            exchange
                .getResponseHeaders()
                .set(
                    "Content-Range",
                    "bytes "
                        + offset
                        + "-"
                        + (modelBytes.length - 1)
                        + "/"
                        + modelBytes.length);
            exchange.sendResponseHeaders(206, modelBytes.length - offset);
          }
          int bytesToWrite = Math.min(32 * 1024, modelBytes.length - offset);
          try {
            exchange.getResponseBody().write(modelBytes, offset, bytesToWrite);
          } finally {
            exchange.close();
          }
        });
    server.start();

    try {
      Path destination = tempDir.resolve("cache/model.gguf");
      ModelJarDescriptor descriptor =
          descriptor(modelUri(server), destination, modelBytes.length, sha256(modelBytes));
      ModelJarInstaller installer =
          new ModelJarInstaller(
              new InMemoryModelJarRegistry(java.util.List.of(descriptor)), failedAttempts::add);

      assertThrows(
          ModelJarException.class, () -> installer.install(ModelJar.of("hf://example/model")));

      assertEquals(5, requests.get());
      assertEquals(List.of(1, 2, 3, 4), failedAttempts);
      assertEquals(false, Files.exists(destination));
    } finally {
      server.stop(0);
    }
  }

  private static URI modelUri(HttpServer server) {
    String host = server.getAddress().getAddress().getHostAddress();
    if (host.contains(":")) {
      host = "[" + host + "]";
    }
    return URI.create("http://" + host + ":" + server.getAddress().getPort() + "/model.gguf");
  }

  private static byte[] modelBytes() {
    byte[] modelBytes = new byte[256 * 1024];
    for (int index = 0; index < modelBytes.length; index++) {
      modelBytes[index] = (byte) (index * 31);
    }
    return modelBytes;
  }

  private static ModelJarDescriptor descriptor(Path source, Path destination, String sha256) {
    return descriptor(source.toUri(), destination, fileSize(source), sha256);
  }

  private static ModelJarDescriptor descriptor(
      URI source, Path destination, long sizeBytes, String sha256) {
    Properties properties = new Properties();
    properties.setProperty("model.example.sourceId", "hf://example/model");
    properties.setProperty(
        "model.example.markerCoordinate",
        "org.modeljars.huggingface:example.model.q8_0:1.0.0-q8_0.1");
    properties.setProperty("model.example.modelVersion", "1.0.0");
    properties.setProperty("model.example.variant", "q8_0");
    properties.setProperty("model.example.format", "gguf");
    properties.setProperty("model.example.architecture", "llama");
    properties.setProperty("model.example.quantization", "Q8_0");
    properties.setProperty("model.example.path", destination.toString());
    properties.setProperty("model.example.sourceUri", "https://example.invalid/model");
    properties.setProperty("model.example.downloadUri", source.toString());
    properties.setProperty("model.example.revision", "0123456789abcdef0123456789abcdef01234567");
    properties.setProperty("model.example.sha256", sha256);
    properties.setProperty("model.example.sizeBytes", Long.toString(sizeBytes));
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
