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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

record ContributionRequest(
    String source,
    String revision,
    List<String> files,
    Optional<String> license,
    List<String> domains,
    List<String> capabilities) {
  ContributionRequest {
    source = Objects.requireNonNull(source, "source").trim();
    revision = Objects.requireNonNull(revision, "revision").trim();
    files = List.copyOf(files);
    license =
        Objects.requireNonNull(license, "license")
            .map(String::trim)
            .filter(value -> !value.isEmpty());
    domains = List.copyOf(domains);
    capabilities = List.copyOf(capabilities);
    if (source.isEmpty())
      throw new IllegalArgumentException("Contribution source must not be blank");
    if (revision.isEmpty())
      throw new IllegalArgumentException("Contribution revision must not be blank");
  }
}
