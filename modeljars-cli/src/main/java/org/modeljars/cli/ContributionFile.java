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

import java.util.Objects;

record ContributionFile(String path, String role, String sha256, long sizeBytes) {
  ContributionFile {
    path = Objects.requireNonNull(path, "path");
    role = Objects.requireNonNull(role, "role");
    sha256 = Objects.requireNonNull(sha256, "sha256");
    if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
      throw new IllegalArgumentException(
          "Contribution file path must be normalized and relative: " + path);
    }
    if (!sha256.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Contribution file SHA-256 is invalid: " + path);
    }
    if (sizeBytes <= 0)
      throw new IllegalArgumentException("Contribution file size must be positive: " + path);
  }
}
