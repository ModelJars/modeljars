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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ModelJarResourceLoaderTest {
  private static final String RESOURCE = "META-INF/modeljars/test/semantic-order.txt";
  private static final byte[] PAYLOAD = "alpha\nbeta\ngamma\n".getBytes(StandardCharsets.UTF_8);

  @Test
  void readsAndVerifiesBundledPayload() {
    ModelJarDescriptor descriptor = descriptor(RESOURCE, PAYLOAD.length, sha256(PAYLOAD));

    byte[] loaded =
        new ModelJarResourceLoader(getClass().getClassLoader()).readVerified(descriptor);

    assertEquals(RESOURCE, descriptor.classpathResource().orElseThrow());
    assertArrayEquals(PAYLOAD, loaded);
  }

  @Test
  void rejectsMissingResource() {
    ModelJarDescriptor descriptor = descriptor("missing.txt", PAYLOAD.length, sha256(PAYLOAD));

    ModelJarException error =
        assertThrows(
            ModelJarException.class,
            () -> new ModelJarResourceLoader(getClass().getClassLoader()).readVerified(descriptor));

    assertEquals("Bundled model resource is missing: missing.txt", error.getMessage());
  }

  @Test
  void rejectsSizeOrChecksumMismatch() {
    ModelJarResourceLoader loader = new ModelJarResourceLoader(getClass().getClassLoader());
    ModelJarDescriptor wrongSize = descriptor(RESOURCE, PAYLOAD.length + 1L, sha256(PAYLOAD));
    ModelJarDescriptor wrongChecksum = descriptor(RESOURCE, PAYLOAD.length, "0".repeat(64));

    assertThrows(ModelJarException.class, () -> loader.readVerified(wrongSize));
    assertThrows(ModelJarException.class, () -> loader.readVerified(wrongChecksum));
  }

  private static ModelJarDescriptor descriptor(String resource, long size, String sha256) {
    Properties properties = new Properties();
    properties.setProperty("model.example.sourceId", "github://example/semantic-order");
    properties.setProperty(
        "model.example.markerCoordinate",
        "org.modeljars.github:example.semantic-order:1.0.0-optimal.1");
    properties.setProperty("model.example.modelVersion", "1.0.0");
    properties.setProperty("model.example.variant", "optimal");
    properties.setProperty("model.example.format", "wordtour-v1");
    properties.setProperty("model.example.architecture", "wordtour");
    properties.setProperty("model.example.quantization", "NONE");
    properties.setProperty("model.example.classpathResource", resource);
    properties.setProperty("model.example.sha256", sha256);
    properties.setProperty("model.example.sizeBytes", Long.toString(size));
    properties.setProperty("model.example.capabilities", "semantic-neighbors");
    properties.setProperty("model.example.backend.semantic-order", "true");
    return PropertiesModelJarRegistry.fromProperties(properties).descriptors().getFirst();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
