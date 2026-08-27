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

/** Evidence-backed RAG usage level for an exact model artifact and execution path. */
public enum RagUseCaseTier {
  /** The artifact did not pass the production qualification policy. */
  UNQUALIFIED,

  /** The model can answer directly while preserving the benchmark's quality bar. */
  GENERATIVE_RAG,

  /** The model is production-usable with the Models grounding and extractive fallback policy. */
  GUARDED_RAG
}
