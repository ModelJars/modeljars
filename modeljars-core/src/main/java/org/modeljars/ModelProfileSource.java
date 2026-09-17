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

import java.net.URI;
import java.util.Objects;

/**
 * A pinned file a model profile value was read from.
 *
 * @param id identifier that profile provenance entries reference
 * @param kind source kind, such as {@code gguf-metadata} or {@code hf-generation-config}
 * @param file file name at the pinned revision
 * @param uri immutable download URI of the file
 * @param revision pinned upstream revision
 * @param sha256 SHA-256 digest of the file
 */
public record ModelProfileSource(
    String id, String kind, String file, URI uri, String revision, String sha256) {
  /** Validates that every field is present and the digest is hexadecimal SHA-256. */
  public ModelProfileSource {
    id = requireText(id, "id");
    kind = requireText(kind, "kind");
    file = requireText(file, "file");
    uri = Objects.requireNonNull(uri, "uri");
    revision = requireText(revision, "revision");
    sha256 = requireText(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
