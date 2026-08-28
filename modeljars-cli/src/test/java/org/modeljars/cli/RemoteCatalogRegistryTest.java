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
package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelJarRegistry;
import org.modeljars.PropertiesModelJarRegistry;

class RemoteCatalogRegistryTest {
  private static final URI CATALOG_URI =
      URI.create("https://modeljars.org/catalog/registry.properties");
  private static final URI HASH_URI =
      URI.create("https://modeljars.org/catalog/registry.properties.sha256");

  @TempDir Path temporaryDirectory;

  @Test
  void downloadsAndActivatesCatalogWhenThePublishedHashChanges() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] published = catalog("published");
    AtomicInteger catalogDownloads = new AtomicInteger();
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              if (uri.equals(HASH_URI)) {
                return (sha256(published) + "\n").getBytes(StandardCharsets.US_ASCII);
              }
              catalogDownloads.incrementAndGet();
              return published;
            });

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("published", selected.descriptors().getFirst().alias());
    assertEquals(1, catalogDownloads.get());
    assertArrayEquals(published, Files.readAllBytes(updater.catalogFile()));
  }

  @Test
  void usesMatchingCachedCatalogWithoutDownloadingItAgain() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] cached = catalog("cached");
    AtomicInteger catalogDownloads = new AtomicInteger();
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              if (uri.equals(HASH_URI)) {
                return sha256(cached).getBytes(StandardCharsets.US_ASCII);
              }
              catalogDownloads.incrementAndGet();
              return catalog("unexpected");
            });
    Files.createDirectories(updater.catalogFile().getParent());
    Files.write(updater.catalogFile(), cached);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("cached", selected.descriptors().getFirst().alias());
    assertEquals(0, catalogDownloads.get());
  }

  @Test
  void usesLastVerifiedCatalogWhenTheNetworkIsUnavailable() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] cached = catalog("cached");
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              throw new IOException("offline");
            });
    Files.createDirectories(updater.catalogFile().getParent());
    Files.write(updater.catalogFile(), cached);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("cached", selected.descriptors().getFirst().alias());
  }

  @Test
  void usesShippedCatalogWhenOfflineBeforeTheFirstRefresh() {
    byte[] shipped = catalog("shipped");
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              throw new IOException("offline");
            });

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("shipped", selected.descriptors().getFirst().alias());
    assertFalse(Files.exists(updater.catalogFile()));
  }

  @Test
  void skipsCatalogDownloadWhenTheShippedCatalogAlreadyMatches() {
    byte[] shipped = catalog("shipped");
    AtomicInteger catalogDownloads = new AtomicInteger();
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              if (uri.equals(HASH_URI)) {
                return sha256(shipped).getBytes(StandardCharsets.US_ASCII);
              }
              catalogDownloads.incrementAndGet();
              return catalog("unexpected");
            });

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("shipped", selected.descriptors().getFirst().alias());
    assertEquals(0, catalogDownloads.get());
  }

  @Test
  void rejectsHashMismatchAndPreservesThePreviousCatalog() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] cached = catalog("cached");
    byte[] expected = catalog("expected");
    RemoteCatalogRegistry updater =
        updater(
            uri ->
                uri.equals(HASH_URI)
                    ? sha256(expected).getBytes(StandardCharsets.US_ASCII)
                    : catalog("tampered"));
    Files.createDirectories(updater.catalogFile().getParent());
    Files.write(updater.catalogFile(), cached);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("cached", selected.descriptors().getFirst().alias());
    assertArrayEquals(cached, Files.readAllBytes(updater.catalogFile()));
  }

  @Test
  void rejectsMalformedCatalogAndPreservesThePreviousCatalog() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] cached = catalog("cached");
    byte[] malformed = "not-a-model=true\n".getBytes(StandardCharsets.ISO_8859_1);
    RemoteCatalogRegistry updater =
        updater(
            uri ->
                uri.equals(HASH_URI)
                    ? sha256(malformed).getBytes(StandardCharsets.US_ASCII)
                    : malformed);
    Files.createDirectories(updater.catalogFile().getParent());
    Files.write(updater.catalogFile(), cached);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("cached", selected.descriptors().getFirst().alias());
    assertArrayEquals(cached, Files.readAllBytes(updater.catalogFile()));
  }

  @Test
  void replacesStaleCacheWithShippedCatalogWhenThePublishedHashRollsBack() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] stale = catalog("stale");
    AtomicInteger catalogDownloads = new AtomicInteger();
    RemoteCatalogRegistry updater =
        updater(
            uri -> {
              if (uri.equals(HASH_URI)) {
                return sha256(shipped).getBytes(StandardCharsets.US_ASCII);
              }
              catalogDownloads.incrementAndGet();
              return catalog("unexpected");
            });
    Files.createDirectories(updater.catalogFile().getParent());
    Files.write(updater.catalogFile(), stale);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("shipped", selected.descriptors().getFirst().alias());
    assertEquals(0, catalogDownloads.get());
    assertArrayEquals(shipped, Files.readAllBytes(updater.catalogFile()));
  }

  @Test
  void usesVerifiedDownloadEvenWhenTheCacheCannotBeWritten() throws IOException {
    byte[] shipped = catalog("shipped");
    byte[] published = catalog("published");
    Path blockedDirectory = temporaryDirectory.resolve("blocked");
    Files.writeString(blockedDirectory, "not a directory");
    RemoteCatalogRegistry updater =
        new RemoteCatalogRegistry(
            blockedDirectory.resolve("registry.properties"),
            HASH_URI,
            CATALOG_URI,
            uri ->
                uri.equals(HASH_URI)
                    ? sha256(published).getBytes(StandardCharsets.US_ASCII)
                    : published);

    ModelJarRegistry selected = updater.load(registry(shipped), shipped);

    assertEquals("published", selected.descriptors().getFirst().alias());
  }

  private RemoteCatalogRegistry updater(RemoteCatalogRegistry.Fetcher fetcher) {
    return new RemoteCatalogRegistry(
        temporaryDirectory.resolve("registry.properties"), HASH_URI, CATALOG_URI, fetcher);
  }

  private static ModelJarRegistry registry(byte[] catalog) {
    try {
      Properties properties = new Properties();
      properties.load(new ByteArrayInputStream(catalog));
      return PropertiesModelJarRegistry.fromProperties(properties);
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static byte[] catalog(String alias) {
    return ("""
            model.%1$s.sourceId=hf://example/%1$s
            model.%1$s.markerCoordinate=org.modeljars.huggingface:example.%1$s.q4_0:1.0.0-q4_0.1
            model.%1$s.modelVersion=1.0.0
            model.%1$s.variant=q4_0
            model.%1$s.format=gguf
            model.%1$s.architecture=llama
            model.%1$s.quantization=Q4_0
            model.%1$s.downloadUri=https://huggingface.co/example/%1$s/model.gguf
            model.%1$s.sha256=%2$s
            model.%1$s.sizeBytes=1024
            model.%1$s.capabilities=chat,text-generation
            model.%1$s.backend.pure-java=true
            """)
        .formatted(alias, "a".repeat(64))
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
