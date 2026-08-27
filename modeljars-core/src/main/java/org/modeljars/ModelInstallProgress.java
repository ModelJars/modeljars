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
import java.nio.file.Path;
import java.util.Objects;

/** Structured progress emitted while a model artifact is downloaded and verified. */
public sealed interface ModelInstallProgress {

  /** Where the artifact being verified came from. */
  enum Source {
    /** The artifact was downloaded during this installation. */
    DOWNLOAD,
    /** The artifact was already present in the local cache. */
    CACHE,
    /** The artifact was resolved from an explicitly configured offline cache. */
    OFFLINE,
    /** Verification was requested directly through the installer API. */
    EXPLICIT
  }

  /**
   * Returns the model alias associated with this event.
   *
   * @return stable catalog alias
   */
  String alias();

  /**
   * A model download is about to begin.
   *
   * @param alias stable catalog alias
   * @param source artifact download URI
   * @param destination final cache path
   * @param completedBytes bytes already present in the resumable transfer
   * @param totalBytes expected artifact size
   */
  record DownloadStarted(
      String alias, URI source, Path destination, long completedBytes, long totalBytes)
      implements ModelInstallProgress {
    /** Validates the download metadata and byte range. */
    public DownloadStarted {
      alias = requireAlias(alias);
      source = Objects.requireNonNull(source, "source");
      destination = Objects.requireNonNull(destination, "destination");
      requireProgress(completedBytes, totalBytes);
    }
  }

  /**
   * More model bytes have been written to the temporary artifact.
   *
   * @param alias stable catalog alias
   * @param completedBytes bytes written so far
   * @param totalBytes expected artifact size
   */
  record DownloadAdvanced(String alias, long completedBytes, long totalBytes)
      implements ModelInstallProgress {
    /** Validates the alias and byte range. */
    public DownloadAdvanced {
      alias = requireAlias(alias);
      requireProgress(completedBytes, totalBytes);
    }
  }

  /**
   * A failed transfer will be resumed after a bounded delay.
   *
   * @param alias stable catalog alias
   * @param completedBytes bytes retained for the resumed transfer
   * @param totalBytes expected artifact size
   * @param attempt next attempt number
   * @param maximumAttempts maximum number of attempts
   * @param delayMillis delay before the next attempt
   * @param reason description of the transfer failure
   */
  record Retrying(
      String alias,
      long completedBytes,
      long totalBytes,
      int attempt,
      int maximumAttempts,
      long delayMillis,
      String reason)
      implements ModelInstallProgress {
    /** Validates the retry metadata and supplies a fallback reason. */
    public Retrying {
      alias = requireAlias(alias);
      requireProgress(completedBytes, totalBytes);
      if (attempt < 2 || maximumAttempts < attempt) {
        throw new IllegalArgumentException("invalid retry attempt");
      }
      if (delayMillis < 0) {
        throw new IllegalArgumentException("delayMillis must not be negative");
      }
      reason = Objects.requireNonNullElse(reason, "download interrupted");
    }
  }

  /**
   * SHA-256 verification is about to begin.
   *
   * @param alias stable catalog alias
   * @param artifact artifact being verified
   * @param totalBytes expected artifact size
   * @param source origin of the artifact
   */
  record VerificationStarted(String alias, Path artifact, long totalBytes, Source source)
      implements ModelInstallProgress {
    /** Validates the verification metadata. */
    public VerificationStarted {
      alias = requireAlias(alias);
      artifact = Objects.requireNonNull(artifact, "artifact");
      if (totalBytes < 0) {
        throw new IllegalArgumentException("totalBytes must not be negative");
      }
      source = Objects.requireNonNull(source, "source");
    }
  }

  /**
   * More artifact bytes have contributed to the SHA-256 digest.
   *
   * @param alias stable catalog alias
   * @param completedBytes bytes digested so far
   * @param totalBytes expected artifact size
   */
  record VerificationAdvanced(String alias, long completedBytes, long totalBytes)
      implements ModelInstallProgress {
    /** Validates the alias and byte range. */
    public VerificationAdvanced {
      alias = requireAlias(alias);
      requireProgress(completedBytes, totalBytes);
    }
  }

  /**
   * The artifact is installed and its immutable digest has been verified.
   *
   * @param alias stable catalog alias
   * @param artifact verified artifact path
   * @param totalBytes verified artifact size
   * @param source origin of the artifact
   */
  record Completed(String alias, Path artifact, long totalBytes, Source source)
      implements ModelInstallProgress {
    /** Validates the completion metadata. */
    public Completed {
      alias = requireAlias(alias);
      artifact = Objects.requireNonNull(artifact, "artifact");
      if (totalBytes < 0) {
        throw new IllegalArgumentException("totalBytes must not be negative");
      }
      source = Objects.requireNonNull(source, "source");
    }
  }

  private static String requireAlias(String alias) {
    Objects.requireNonNull(alias, "alias");
    if (alias.isBlank()) {
      throw new IllegalArgumentException("alias must not be blank");
    }
    return alias;
  }

  private static void requireProgress(long completedBytes, long totalBytes) {
    if (completedBytes < 0 || totalBytes < 0 || completedBytes > totalBytes) {
      throw new IllegalArgumentException("invalid byte progress");
    }
  }
}
