package org.modeljars;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Downloads immutable model artifacts and verifies their size and SHA-256 digest. */
public final class ModelJarInstaller {
  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
  private static final int READ_TIMEOUT_MILLIS = 60_000;

  private final ModelJarRegistry registry;

  public ModelJarInstaller(ModelJarRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** Resolves, downloads when necessary, and verifies a model artifact. */
  public Path install(ModelJarRequirement requirement) {
    Objects.requireNonNull(requirement, "requirement");
    ModelJarDescriptor descriptor =
        registry
            .resolve(requirement)
            .orElseThrow(() -> new ModelJarException("No ModelJars descriptor matched " + requirement));
    return install(descriptor);
  }

  /** Downloads when necessary and verifies the artifact described by a marker. */
  public Path install(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    Path destination =
        descriptor
            .localPath()
            .orElseThrow(
                () -> new ModelJarException("Marker has no local path: " + descriptor.alias()));

    if (Files.exists(destination)) {
      verify(destination, descriptor);
      return destination;
    }

    URI downloadUri =
        descriptor
            .downloadUri()
            .orElseThrow(
                () -> new ModelJarException("Marker has no download URI: " + descriptor.alias()));

    Path parent = destination.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new ModelJarException("Model path has no parent directory: " + destination);
    }

    Path temporary = null;
    try {
      Files.createDirectories(parent);
      temporary = Files.createTempFile(parent, destination.getFileName() + ".", ".part");
      download(downloadUri, temporary);
      verify(temporary, descriptor);
      moveIntoPlace(temporary, destination);
      return destination;
    } catch (IOException e) {
      throw new ModelJarException(
          "Unable to install model artifact " + descriptor.alias() + " from " + downloadUri, e);
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

  /** Verifies an artifact against the immutable size and SHA-256 metadata in its marker. */
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

  private static void download(URI source, Path destination) throws IOException {
    URLConnection connection = source.toURL().openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    connection.setReadTimeout(READ_TIMEOUT_MILLIS);
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream input = connection.getInputStream();
        OutputStream output = Files.newOutputStream(destination)) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
      }
    }
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
}
