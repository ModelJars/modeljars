package org.modeljars;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Loads compact model payloads bundled inside ModelJars and verifies their immutable metadata. */
public final class ModelJarResourceLoader {
  private static final int BUFFER_SIZE = 16 * 1024;

  private final ClassLoader classLoader;

  public ModelJarResourceLoader(ClassLoader classLoader) {
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
  }

  /** Loads a bundled payload after verifying its declared byte count and SHA-256 digest. */
  public byte[] readVerified(ModelJarDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    String resource =
        descriptor
            .classpathResource()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Marker has no bundled classpath resource: " + descriptor.alias()));
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

    try (InputStream input = classLoader.getResourceAsStream(resource)) {
      if (input == null) {
        throw new ModelJarException("Bundled model resource is missing: " + resource);
      }
      byte[] payload = readBounded(input, expectedSize, resource);
      String actualSha256 = sha256(payload);
      if (!actualSha256.equals(expectedSha256)) {
        throw new ModelJarException(
            "Bundled model resource SHA-256 mismatch for "
                + resource
                + ": expected "
                + expectedSha256
                + ", got "
                + actualSha256);
      }
      return payload;
    } catch (IOException e) {
      throw new ModelJarException("Unable to load bundled model resource: " + resource, e);
    }
  }

  private static byte[] readBounded(InputStream input, long expectedSize, String resource)
      throws IOException {
    if (expectedSize > Integer.MAX_VALUE) {
      throw new ModelJarException(
          "Bundled model resource is too large for in-memory loading: " + resource);
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
    byte[] buffer = new byte[BUFFER_SIZE];
    long actualSize = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      actualSize += read;
      if (actualSize > expectedSize) {
        throw sizeMismatch(resource, expectedSize, actualSize);
      }
      output.write(buffer, 0, read);
    }
    if (actualSize != expectedSize) {
      throw sizeMismatch(resource, expectedSize, actualSize);
    }
    return output.toByteArray();
  }

  private static ModelJarException sizeMismatch(
      String resource, long expectedSize, long actualSize) {
    return new ModelJarException(
        "Bundled model resource size mismatch for "
            + resource
            + ": expected "
            + expectedSize
            + ", got "
            + actualSize);
  }

  private static String sha256(byte[] payload) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
