package org.modeljars;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads immutable model artifacts and verifies their size and SHA-256 digest. */
public final class ModelJarInstaller {
  private static final System.Logger LOGGER = System.getLogger(ModelJarInstaller.class.getName());
  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
  private static final int READ_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_DOWNLOAD_ATTEMPTS = 5;
  private static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
  private static final Pattern CONTENT_RANGE =
      Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

  private final ModelJarRegistry registry;
  private final RetryDelay retryDelay;
  private final Consumer<String> progressReporter;

  /**
   * Creates an installer backed by a model registry.
   *
   * @param registry registry used to resolve model selectors
   */
  public ModelJarInstaller(ModelJarRegistry registry) {
    this(registry, ModelJarInstaller::pauseBeforeRetry, ModelJarInstaller::reportProgress);
  }

  ModelJarInstaller(ModelJarRegistry registry, RetryDelay retryDelay) {
    this(registry, retryDelay, ModelJarInstaller::reportProgress);
  }

  ModelJarInstaller(
      ModelJarRegistry registry, RetryDelay retryDelay, Consumer<String> progressReporter) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.progressReporter = Objects.requireNonNull(progressReporter, "progressReporter");
  }

  /**
   * Resolves, downloads when necessary, and verifies a model artifact.
   *
   * @param requirement model selection constraints
   * @return verified local model path
   */
  public Path install(ModelJar requirement) {
    Objects.requireNonNull(requirement, "requirement");
    ModelJarDescriptor descriptor =
        registry
            .resolve(requirement)
            .orElseThrow(
                () -> new ModelJarException("No ModelJars descriptor matched " + requirement));
    return install(descriptor);
  }

  /**
   * Downloads when necessary and verifies the artifact described by a marker.
   *
   * @param descriptor immutable model marker metadata
   * @return verified local model path
   */
  public Path install(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Path destination =
        descriptor
            .localPath()
            .orElseThrow(
                () -> new ModelJarException("Marker has no local path: " + descriptor.alias()));
    return install(descriptor, destination);
  }

  /**
   * Downloads when necessary and verifies the artifact at an explicit destination.
   *
   * <p>This overload lets higher-level runtimes use content-addressed caches without changing the
   * immutable marker metadata.
   *
   * @param descriptor immutable model marker metadata
   * @param destination local artifact destination
   * @return verified local model path
   */
  public Path install(ModelJarDescriptor descriptor, Path destination) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(destination, "destination");

    if (Files.exists(destination)) {
      progressReporter.accept(
          "Verifying cached model "
              + descriptor.alias()
              + " ("
              + displaySize(descriptor)
              + ") at "
              + destination.toAbsolutePath().normalize());
      verify(destination, descriptor);
      progressReporter.accept("Model cache verified: " + descriptor.alias());
      return destination;
    }

    URI downloadUri =
        descriptor
            .downloadUri()
            .orElseThrow(
                () -> new ModelJarException("Marker has no download URI: " + descriptor.alias()));
    long expectedSize =
        descriptor
            .sizeBytes()
            .orElseThrow(
                () -> new ModelJarException("Marker has no sizeBytes: " + descriptor.alias()));

    Path parent = destination.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new ModelJarException("Model path has no parent directory: " + destination);
    }

    Path temporary = null;
    try {
      Files.createDirectories(parent);
      temporary = Files.createTempFile(parent, destination.getFileName() + ".", ".part");
      progressReporter.accept(
          "Downloading model "
              + descriptor.alias()
              + " ("
              + formatBytes(expectedSize)
              + ") to "
              + destination.toAbsolutePath().normalize());
      download(descriptor.alias(), downloadUri, temporary, expectedSize);
      progressReporter.accept("Download complete; verifying SHA-256 for " + descriptor.alias());
      verify(temporary, descriptor);
      moveIntoPlace(temporary, destination);
      progressReporter.accept("Model ready in cache: " + descriptor.alias());
      return destination;
    } catch (IOException e) {
      throw new ModelJarException("Unable to install model artifact " + descriptor.alias(), e);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The installation result or primary failure is more useful than temporary-file cleanup.
        }
      }
    }
  }

  /**
   * Verifies an artifact already present in an offline cache.
   *
   * @param descriptor immutable model marker metadata
   * @param artifact cached model artifact
   * @return the verified cached model path
   */
  public Path verifyCached(ModelJarDescriptor descriptor, Path artifact) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(artifact, "artifact");
    if (!Files.isRegularFile(artifact)) {
      throw new ModelJarException(
          "Model artifact is not available in the offline cache: " + artifact);
    }
    progressReporter.accept(
        "Verifying offline model "
            + descriptor.alias()
            + " ("
            + displaySize(descriptor)
            + ") at "
            + artifact.toAbsolutePath().normalize());
    verify(artifact, descriptor);
    progressReporter.accept("Offline model cache verified: " + descriptor.alias());
    return artifact;
  }

  /**
   * Verifies an artifact against the immutable size and SHA-256 metadata in its marker.
   *
   * @param artifact local model artifact
   * @param descriptor immutable model marker metadata
   */
  public void verify(Path artifact, ModelJarDescriptor descriptor) {
    Objects.requireNonNull(artifact, "artifact");
    Objects.requireNonNull(descriptor, "descriptor");
    long expectedSize =
        descriptor
            .sizeBytes()
            .orElseThrow(
                () -> new ModelJarException("Marker has no sizeBytes: " + descriptor.alias()));
    String expectedSha256 =
        descriptor
            .sha256()
            .orElseThrow(
                () -> new ModelJarException("Marker has no SHA-256: " + descriptor.alias()));

    try {
      long actualSize = Files.size(artifact);
      if (actualSize != expectedSize) {
        throw new ModelJarException(
            "Model artifact size mismatch for "
                + artifact
                + ": expected "
                + expectedSize
                + ", got "
                + actualSize);
      }
      String actualSha256 = sha256(artifact);
      if (!actualSha256.equals(expectedSha256)) {
        throw new ModelJarException(
            "Model artifact SHA-256 mismatch for "
                + artifact
                + ": expected "
                + expectedSha256
                + ", got "
                + actualSha256);
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to verify model artifact: " + artifact, e);
    }
  }

  private void download(String alias, URI source, Path destination, long expectedSize)
      throws IOException {
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
      long downloaded = Files.size(destination);
      if (downloaded == expectedSize) {
        return;
      }
      if (downloaded > expectedSize) {
        truncate(destination);
      }

      try {
        downloadAttempt(alias, source, destination, expectedSize);
        downloaded = Files.size(destination);
        if (downloaded == expectedSize) {
          return;
        }
        lastFailure =
            new IOException(
                "Incomplete model download: expected "
                    + expectedSize
                    + " bytes, got "
                    + downloaded);
      } catch (IOException e) {
        lastFailure = e;
      }
      if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
        progressReporter.accept(
            "Model download interrupted at "
                + formatBytes(Files.size(destination))
                + "; retrying "
                + alias
                + " (attempt "
                + (attempt + 1)
                + " of "
                + MAX_DOWNLOAD_ATTEMPTS
                + ")");
        retryDelay.pause(attempt);
      }
    }

    long downloaded = Files.size(destination);
    throw new IOException(
        "Unable to download "
            + expectedSize
            + " bytes after "
            + MAX_DOWNLOAD_ATTEMPTS
            + " attempts; received "
            + downloaded,
        lastFailure);
  }

  private static void pauseBeforeRetry(int failedAttempt) throws IOException {
    long delayMillis = INITIAL_RETRY_DELAY_MILLIS << (failedAttempt - 1);
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting to retry the model download", e);
    }
  }

  private void downloadAttempt(String alias, URI source, Path destination, long expectedSize)
      throws IOException {
    long offset = Files.size(destination);
    URLConnection connection = source.toURL().openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    connection.setReadTimeout(READ_TIMEOUT_MILLIS);
    connection.setRequestProperty("Accept-Encoding", "identity");

    HttpURLConnection http =
        connection instanceof HttpURLConnection httpConnection ? httpConnection : null;
    try {
      if (http != null && offset > 0) {
        http.setRequestProperty("Range", "bytes=" + offset + "-");
      }

      boolean append = false;
      if (http != null) {
        int status = http.getResponseCode();
        if (offset > 0 && status == HttpURLConnection.HTTP_PARTIAL) {
          verifyContentRange(http.getHeaderField("Content-Range"), offset, expectedSize);
          append = true;
        } else if (status != HttpURLConnection.HTTP_OK) {
          throw new IOException(
              "Model download returned HTTP " + status + " for " + alias);
        }
      }

      byte[] buffer = new byte[BUFFER_SIZE];
      try (InputStream input = connection.getInputStream();
          OutputStream output =
              append
                  ? Files.newOutputStream(
                      destination, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                  : Files.newOutputStream(
                      destination,
                      StandardOpenOption.WRITE,
                      StandardOpenOption.TRUNCATE_EXISTING)) {
        int read;
        long downloaded = offset;
        int nextPercentage = Math.max(10, ((int) (offset * 100 / expectedSize) / 10 + 1) * 10);
        while ((read = input.read(buffer)) != -1) {
          if (read > 0) {
            output.write(buffer, 0, read);
            downloaded += read;
            int percentage = (int) Math.min(100, downloaded * 100.0 / expectedSize);
            if (percentage >= nextPercentage) {
              progressReporter.accept(
                  "Downloading "
                      + alias
                      + ": "
                      + percentage
                      + "% ("
                      + formatBytes(downloaded)
                      + " of "
                      + formatBytes(expectedSize)
                      + ")");
              nextPercentage = percentage / 10 * 10 + 10;
            }
          }
        }
      }
    } finally {
      if (http != null) {
        http.disconnect();
      }
    }
  }

  private static void verifyContentRange(String contentRange, long offset, long expectedSize)
      throws IOException {
    Matcher matcher = contentRange == null ? null : CONTENT_RANGE.matcher(contentRange);
    if (matcher == null || !matcher.matches()) {
      throw new IOException(
          "Invalid Content-Range for model download at byte " + offset + ": " + contentRange);
    }
    try {
      long start = Long.parseLong(matcher.group(1));
      long end = Long.parseLong(matcher.group(2));
      long total = Long.parseLong(matcher.group(3));
      if (start != offset || end < start || end >= expectedSize || total != expectedSize) {
        throw new IOException(
            "Invalid Content-Range for model download at byte " + offset + ": " + contentRange);
      }
    } catch (NumberFormatException e) {
      throw new IOException("Invalid numeric Content-Range: " + contentRange, e);
    }
  }

  private static void truncate(Path destination) throws IOException {
    Files.newOutputStream(
            destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        .close();
  }

  @FunctionalInterface
  interface RetryDelay {
    void pause(int failedAttempt) throws IOException;
  }

  private static String sha256(Path artifact) throws IOException {
    MessageDigest digest = sha256Digest();
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream input = Files.newInputStream(artifact)) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static void moveIntoPlace(Path source, Path destination) throws IOException {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, destination);
    }
  }

  private static String displaySize(ModelJarDescriptor descriptor) {
    return descriptor.sizeBytes().map(ModelJarInstaller::formatBytes).orElse("unknown size");
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024) {
      return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }
    if (bytes < 1024L * 1024 * 1024) {
      return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
    }
    return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
  }

  private static void reportProgress(String message) {
    LOGGER.log(System.Logger.Level.INFO, message);
  }
}
