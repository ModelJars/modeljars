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
  private final ProgressReporter progressReporter;

  /**
   * Creates an installer backed by a model registry.
   *
   * @param registry registry used to resolve model selectors
   */
  public ModelJarInstaller(ModelJarRegistry registry) {
    this(
        registry,
        ModelJarInstaller::pauseBeforeRetry,
        legacyProgressReporter(ModelJarInstaller::reportProgress));
  }

  /**
   * Creates an installer with an application-owned progress reporter.
   *
   * @param registry registry used to resolve model selectors
   * @param progressReporter receiver for download, verification, and retry messages
   * @return installer that forwards progress messages to the supplied receiver
   */
  public static ModelJarInstaller reportingTo(
      ModelJarRegistry registry, Consumer<String> progressReporter) {
    return new ModelJarInstaller(
        registry,
        ModelJarInstaller::pauseBeforeRetry,
        legacyProgressReporter(Objects.requireNonNull(progressReporter, "progressReporter")));
  }

  /**
   * Creates an installer with structured byte-level progress suitable for terminal rendering.
   *
   * @param registry registry used to resolve model selectors
   * @param progressReporter receiver for typed download, verification, retry, and completion events
   * @return installer that forwards structured progress to the supplied receiver
   */
  public static ModelJarInstaller reportingProgressTo(
      ModelJarRegistry registry, Consumer<ModelInstallProgress> progressReporter) {
    return new ModelJarInstaller(
        registry,
        ModelJarInstaller::pauseBeforeRetry,
        Objects.requireNonNull(progressReporter, "progressReporter")::accept);
  }

  ModelJarInstaller(ModelJarRegistry registry, RetryDelay retryDelay) {
    this(
        registry,
        retryDelay,
        legacyProgressReporter(ModelJarInstaller::reportProgress));
  }

  ModelJarInstaller(
      ModelJarRegistry registry, RetryDelay retryDelay, Consumer<String> progressReporter) {
    this(
        registry,
        retryDelay,
        legacyProgressReporter(Objects.requireNonNull(progressReporter, "progressReporter")));
  }

  private ModelJarInstaller(
      ModelJarRegistry registry,
      RetryDelay retryDelay,
      ProgressReporter progressReporter) {
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
      verify(destination, descriptor, ModelInstallProgress.Source.CACHE);
      progressReporter.accept(
          new ModelInstallProgress.Completed(
              descriptor.alias(),
              destination.toAbsolutePath().normalize(),
              sizeBytes(descriptor),
              ModelInstallProgress.Source.CACHE));
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
          new ModelInstallProgress.DownloadStarted(
              descriptor.alias(),
              downloadUri,
              destination.toAbsolutePath().normalize(),
              Files.size(temporary),
              expectedSize));
      download(descriptor.alias(), downloadUri, temporary, expectedSize);
      verify(temporary, descriptor, ModelInstallProgress.Source.DOWNLOAD);
      moveIntoPlace(temporary, destination);
      progressReporter.accept(
          new ModelInstallProgress.Completed(
              descriptor.alias(),
              destination.toAbsolutePath().normalize(),
              expectedSize,
              ModelInstallProgress.Source.DOWNLOAD));
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
    verify(artifact, descriptor, ModelInstallProgress.Source.OFFLINE);
    progressReporter.accept(
        new ModelInstallProgress.Completed(
            descriptor.alias(),
            artifact.toAbsolutePath().normalize(),
            sizeBytes(descriptor),
            ModelInstallProgress.Source.OFFLINE));
    return artifact;
  }

  /**
   * Verifies an artifact against the immutable size and SHA-256 metadata in its marker.
   *
   * @param artifact local model artifact
   * @param descriptor immutable model marker metadata
   */
  public void verify(Path artifact, ModelJarDescriptor descriptor) {
    verify(artifact, descriptor, ModelInstallProgress.Source.EXPLICIT);
    progressReporter.accept(
        new ModelInstallProgress.Completed(
            descriptor.alias(),
            artifact.toAbsolutePath().normalize(),
            sizeBytes(descriptor),
            ModelInstallProgress.Source.EXPLICIT));
  }

  private void verify(
      Path artifact, ModelJarDescriptor descriptor, ModelInstallProgress.Source source) {
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
      progressReporter.accept(
          new ModelInstallProgress.VerificationStarted(
              descriptor.alias(), artifact.toAbsolutePath().normalize(), expectedSize, source));
      String actualSha256 = sha256(artifact, descriptor.alias(), expectedSize);
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
        long completedBytes = Files.size(destination);
        long delayMillis = retryDelayMillis(attempt);
        progressReporter.accept(
            new ModelInstallProgress.Retrying(
                alias,
                completedBytes,
                expectedSize,
                attempt + 1,
                MAX_DOWNLOAD_ATTEMPTS,
                delayMillis,
                lastFailure == null ? null : lastFailure.getMessage()));
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
    long delayMillis = retryDelayMillis(failedAttempt);
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
        long downloaded = append ? offset : 0;
        while ((read = input.read(buffer)) != -1) {
          if (read > 0) {
            output.write(buffer, 0, read);
            downloaded += read;
            progressReporter.accept(
                new ModelInstallProgress.DownloadAdvanced(
                    alias, Math.min(downloaded, expectedSize), expectedSize));
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

  private String sha256(Path artifact, String alias, long totalBytes) throws IOException {
    MessageDigest digest = sha256Digest();
    byte[] buffer = new byte[BUFFER_SIZE];
    long completedBytes = 0;
    try (InputStream input = Files.newInputStream(artifact)) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read > 0) {
          digest.update(buffer, 0, read);
          completedBytes += read;
          progressReporter.accept(
              new ModelInstallProgress.VerificationAdvanced(
                  alias, Math.min(completedBytes, totalBytes), totalBytes));
        }
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

  private static long retryDelayMillis(int failedAttempt) {
    return INITIAL_RETRY_DELAY_MILLIS << (failedAttempt - 1);
  }

  private static long sizeBytes(ModelJarDescriptor descriptor) {
    return descriptor
        .sizeBytes()
        .orElseThrow(() -> new ModelJarException("Marker has no sizeBytes: " + descriptor.alias()));
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

  private static ProgressReporter legacyProgressReporter(Consumer<String> reporter) {
    return new LegacyProgressReporter(reporter);
  }

  @FunctionalInterface
  private interface ProgressReporter {
    void accept(ModelInstallProgress progress);
  }

  private static final class LegacyProgressReporter implements ProgressReporter {
    private final Consumer<String> reporter;
    private int nextDownloadPercentage = 10;

    private LegacyProgressReporter(Consumer<String> reporter) {
      this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    @Override
    public void accept(ModelInstallProgress progress) {
      if (progress instanceof ModelInstallProgress.DownloadStarted started) {
        nextDownloadPercentage = 10;
        reporter.accept(
            "Downloading model "
                + started.alias()
                + " ("
                + formatBytes(started.totalBytes())
                + ") to "
                + started.destination());
      } else if (progress instanceof ModelInstallProgress.DownloadAdvanced advanced) {
        int percentage =
            advanced.totalBytes() == 0
                ? 100
                : (int) Math.min(100, advanced.completedBytes() * 100.0 / advanced.totalBytes());
        if (percentage >= nextDownloadPercentage) {
          reporter.accept(
              "Downloading "
                  + advanced.alias()
                  + ": "
                  + percentage
                  + "% ("
                  + formatBytes(advanced.completedBytes())
                  + " of "
                  + formatBytes(advanced.totalBytes())
                  + ")");
          nextDownloadPercentage = percentage / 10 * 10 + 10;
        }
      } else if (progress instanceof ModelInstallProgress.Retrying retrying) {
        reporter.accept(
            "Model download interrupted at "
                + formatBytes(retrying.completedBytes())
                + "; retrying "
                + retrying.alias()
                + " (attempt "
                + retrying.attempt()
                + " of "
                + retrying.maximumAttempts()
                + ")");
      } else if (progress instanceof ModelInstallProgress.VerificationStarted started) {
        reporter.accept(verificationMessage(started));
      } else if (progress instanceof ModelInstallProgress.Completed completed) {
        reporter.accept(completedMessage(completed));
      }
    }

    private static String verificationMessage(ModelInstallProgress.VerificationStarted started) {
      return switch (started.source()) {
        case DOWNLOAD -> "Download complete; verifying SHA-256 for " + started.alias();
        case CACHE ->
            "Verifying cached model "
                + started.alias()
                + " ("
                + formatBytes(started.totalBytes())
                + ") at "
                + started.artifact();
        case OFFLINE ->
            "Verifying offline model "
                + started.alias()
                + " ("
                + formatBytes(started.totalBytes())
                + ") at "
                + started.artifact();
        case EXPLICIT -> "Verifying model " + started.alias() + " at " + started.artifact();
      };
    }

    private static String completedMessage(ModelInstallProgress.Completed completed) {
      return switch (completed.source()) {
        case DOWNLOAD -> "Model ready in cache: " + completed.alias();
        case CACHE -> "Model cache verified: " + completed.alias();
        case OFFLINE -> "Offline model cache verified: " + completed.alias();
        case EXPLICIT -> "Model verified: " + completed.alias();
      };
    }
  }
}
