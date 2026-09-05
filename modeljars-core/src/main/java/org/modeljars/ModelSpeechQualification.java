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

/** Production admission thresholds for text-to-speech artifacts. */
public final class ModelSpeechQualification {
  /** Lowest accepted cosine similarity between generated and oracle PCM. */
  public static final double MINIMUM_PCM_COSINE = 0.995;

  /** Lowest accepted signal-to-difference ratio, in decibels. */
  public static final double MINIMUM_SIGNAL_TO_DIFFERENCE_DB = 20.0;

  /** Highest accepted p95 synthesis time divided by generated-audio duration. */
  public static final double MAXIMUM_P95_REAL_TIME_FACTOR = 2.0;

  /** Highest accepted p95 delay before the first audio chunk, in milliseconds. */
  public static final double MAXIMUM_P95_TIME_TO_FIRST_AUDIO_MILLIS = 2_000.0;

  private ModelSpeechQualification() {}
}
