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

/** Common evidence required to execute one immutable generative model artifact. */
public interface ModelExecutionQualification {

  /**
   * Returns the stable identity of the qualified catalog model.
   *
   * @return stable catalog model identifier
   */
  String modelId();

  /**
   * Returns the model's display name.
   *
   * @return human-readable model name
   */
  String model();

  /**
   * Returns the backend used to produce this evidence.
   *
   * @return backend used for qualification
   */
  String backend();

  /**
   * Returns the exact backend version used to produce this evidence.
   *
   * @return immutable backend version or revision
   */
  String backendVersion();

  /**
   * Returns the workload exercised by the qualification run.
   *
   * @return qualification workload identifier
   */
  String workload();

  /**
   * Returns the prompt template applied by the qualification run.
   *
   * @return prompt-template identifier used by the run
   */
  String promptTemplate();

  /**
   * Returns the digest of the exact model artifact under test.
   *
   * @return qualified artifact SHA-256 digest
   */
  String artifactSha256();

  /**
   * Returns the byte length of the exact model artifact under test.
   *
   * @return qualified artifact size in bytes
   */
  long artifactSizeBytes();

  /**
   * Returns the report path within the evidence repository.
   *
   * @return repository-relative qualification report path
   */
  String reportPath();

  /**
   * Returns the public location of the immutable report.
   *
   * @return public immutable qualification report location
   */
  URI reportUri();

  /**
   * Returns the digest used to verify the immutable report.
   *
   * @return qualification report SHA-256 digest
   */
  String reportSha256();

  /**
   * Returns the human-readable result of the qualification run.
   *
   * @return qualification verdict
   */
  String verdict();

  /**
   * Reports whether the evidence passed its qualification policy.
   *
   * @return whether the evidence satisfies its qualification policy
   */
  boolean qualified();

  /**
   * Returns the number of attempts represented by this evidence.
   *
   * @return total number of qualification attempts
   */
  int attempts();

  /**
   * Returns the measured tail latency of the workload.
   *
   * @return 95th-percentile end-to-end latency in milliseconds
   */
  double p95EndToEndMillis();

  /**
   * Reports whether the evidence approves production use.
   *
   * @return whether this evidence approves the artifact for production use
   */
  boolean productionUsable();

  /**
   * Tests whether this evidence applies to a descriptor's identity, digest, and backend.
   *
   * @param descriptor model descriptor to test
   * @return whether this qualification matches the descriptor
   */
  boolean matches(ModelJarDescriptor descriptor);
}
