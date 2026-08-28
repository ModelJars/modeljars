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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.modeljars.ClasspathModelJarRegistry;
import org.modeljars.ModelJarRegistry;
import org.modeljars.PropertiesModelJarRegistry;

/** Refreshes the CLI's qualified catalog while retaining deterministic offline behavior. */
final class RemoteCatalogRegistry {
  static final URI DEFAULT_CATALOG_URI =
      URI.create("https://modeljars.org/catalog/registry.properties");
  static final String CATALOG_URI_PROPERTY = "modeljars.catalog.url";
  static final String CATALOG_URI_ENV = "MODELJARS_CATALOG_URL";
  static final String CATALOG_DIRECTORY_PROPERTY = "modeljars.catalog.cache";
  static final String CATALOG_DIRECTORY_ENV = "MODELJARS_CATALOG_CACHE";
  static final String OFFLINE_PROPERTY = "modeljars.catalog.offline";
  static final String OFFLINE_ENV = "MODELJARS_CATALOG_OFFLINE";

  private static final int MAX_HASH_BYTES = 256;
  private static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;
  private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");

  @FunctionalInterface
  interface Fetcher {
    byte[] fetch(URI uri) throws IOException, InterruptedException;
  }

  private final Path catalogFile;
  private final URI hashUri;
  private final URI catalogUri;
  private final Fetcher fetcher;

  RemoteCatalogRegistry(Path catalogFile, URI hashUri, URI catalogUri, Fetcher fetcher) {
    this.catalogFile =
        Objects.requireNonNull(catalogFile, "catalogFile").toAbsolutePath().normalize();
    this.hashUri = Objects.requireNonNull(hashUri, "hashUri");
    this.catalogUri = Objects.requireNonNull(catalogUri, "catalogUri");
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
  }

  /** Loads the freshest verified catalog available to the CLI. */
  static ModelJarRegistry loadDefault() {
    ModelJarRegistry shipped = ModelJarRegistry.fromClasspath();
    byte[] shippedBytes = shippedCatalog();
    if (offline()) {
      return createDefault().offlineFallback(shipped, shippedBytes).registry();
    }
    return createDefault().load(shipped, shippedBytes);
  }

  ModelJarRegistry load(ModelJarRegistry shipped, byte[] shippedBytes) {
    Objects.requireNonNull(shipped, "shipped");
    byte[] safeShippedBytes = Objects.requireNonNullElseGet(shippedBytes, () -> new byte[0]);
    Candidate fallback = offlineFallback(shipped, safeShippedBytes);
    try {
      String expectedHash = publishedHash(fetcher.fetch(hashUri));
      Candidate cached = cachedCandidate();
      if (cached != null && expectedHash.equals(sha256(cached.content()))) {
        return cached.registry();
      }
      if (safeShippedBytes.length > 0 && expectedHash.equals(sha256(safeShippedBytes))) {
        if (cached != null) {
          try {
            persist(safeShippedBytes);
          } catch (IOException ignored) {
            // The published catalog is still authoritative for this process.
          }
        }
        return shipped;
      }

      byte[] downloaded = fetcher.fetch(catalogUri);
      if (downloaded.length == 0 || downloaded.length > MAX_CATALOG_BYTES) {
        return fallback.registry();
      }
      if (!expectedHash.equals(sha256(downloaded))) {
        return fallback.registry();
      }
      Candidate published = parse(downloaded);
      try {
        persist(downloaded);
      } catch (IOException ignored) {
        // Use the verified download for this process even if the local cache is read-only.
      }
      return published.registry();
    } catch (IOException | IllegalArgumentException exception) {
      return fallback.registry();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return fallback.registry();
    }
  }

  Path catalogFile() {
    return catalogFile;
  }

  private Candidate offlineFallback(ModelJarRegistry shipped, byte[] shippedBytes) {
    Candidate cached = cachedCandidate();
    return cached == null ? new Candidate(shippedBytes, shipped) : cached;
  }

  private Candidate cachedCandidate() {
    try {
      if (!Files.isRegularFile(catalogFile, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      long size = Files.size(catalogFile);
      if (size <= 0 || size > MAX_CATALOG_BYTES) {
        return null;
      }
      return parse(Files.readAllBytes(catalogFile));
    } catch (IOException | IllegalArgumentException exception) {
      return null;
    }
  }

  private static Candidate parse(byte[] content) throws IOException {
    Properties properties = new Properties();
    try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
      properties.load(input);
    }
    ModelJarRegistry registry = PropertiesModelJarRegistry.fromProperties(properties);
    if (registry.descriptors().isEmpty()) {
      throw new IllegalArgumentException("Remote ModelJars catalog contains no models");
    }
    return new Candidate(content, registry);
  }

  private void persist(byte[] content) throws IOException {
    Path directory = catalogFile.getParent();
    if (directory == null) {
      throw new IOException("Catalog cache has no parent directory: " + catalogFile);
    }
    Files.createDirectories(directory);
    Path staging = Files.createTempFile(directory, "registry-", ".tmp");
    try {
      Files.write(staging, content);
      try {
        Files.move(
            staging,
            catalogFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(staging, catalogFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  private static String publishedHash(byte[] content) {
    if (content.length == 0 || content.length > MAX_HASH_BYTES) {
      throw new IllegalArgumentException("Invalid ModelJars catalog hash response");
    }
    String firstToken = new String(content, java.nio.charset.StandardCharsets.US_ASCII).strip();
    int separator = firstToken.indexOf(' ');
    if (separator >= 0) {
      firstToken = firstToken.substring(0, separator);
    }
    if (!SHA_256.matcher(firstToken).matches()) {
      throw new IllegalArgumentException("Invalid ModelJars catalog SHA-256");
    }
    return firstToken.toLowerCase(Locale.ROOT);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static RemoteCatalogRegistry createDefault() {
    URI catalogUri = configuredCatalogUri();
    URI hashUri = URI.create(catalogUri + ".sha256");
    return new RemoteCatalogRegistry(catalogFilePath(), hashUri, catalogUri, httpFetcher());
  }

  private static Fetcher httpFetcher() {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    return uri -> {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(3))
              .header("Accept", "text/plain")
              .header("User-Agent", "modeljars-cli")
              .GET()
              .build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IOException(
              "Catalog request returned HTTP " + response.statusCode() + ": " + uri);
        }
        int limit = uri.toString().endsWith(".sha256") ? MAX_HASH_BYTES : MAX_CATALOG_BYTES;
        byte[] content = body.readNBytes(limit + 1);
        if (content.length > limit) {
          throw new IOException("Catalog response exceeds " + limit + " bytes: " + uri);
        }
        return content;
      }
    };
  }

  private static URI configuredCatalogUri() {
    String configured = System.getProperty(CATALOG_URI_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(CATALOG_URI_ENV);
    }
    return configured == null || configured.isBlank()
        ? DEFAULT_CATALOG_URI
        : URI.create(configured.strip());
  }

  private static Path catalogFilePath() {
    String configured = System.getProperty(CATALOG_DIRECTORY_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(CATALOG_DIRECTORY_ENV);
    }
    Path directory =
        configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".modeljars", "catalog")
            : Path.of(configured.strip());
    return directory.toAbsolutePath().normalize().resolve("registry.properties");
  }

  private static boolean offline() {
    String configured = System.getProperty(OFFLINE_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(OFFLINE_ENV);
    }
    return configured != null && Boolean.parseBoolean(configured.strip());
  }

  private static byte[] shippedCatalog() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = RemoteCatalogRegistry.class.getClassLoader();
    }
    try (InputStream input =
        loader.getResourceAsStream(ClasspathModelJarRegistry.REGISTRY_RESOURCE)) {
      return input == null ? new byte[0] : input.readAllBytes();
    } catch (IOException exception) {
      return new byte[0];
    }
  }

  private record Candidate(byte[] content, ModelJarRegistry registry) {}
}
