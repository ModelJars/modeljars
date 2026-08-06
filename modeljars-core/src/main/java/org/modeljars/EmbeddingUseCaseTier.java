/*
 * Copyright 2026 Integrallis Software, LLC
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

/**
 * How an embedding artifact met the retrieval quality policy.
 *
 * <p>Deliberately separate from {@link RagUseCaseTier}. That enum grades a model's ability to
 * answer from retrieved evidence; this one grades a model's ability to retrieve. The two are
 * measured against different corpora with disjoint metrics, and collapsing them would leave every
 * artifact carrying a majority of meaningless fields.
 */
public enum EmbeddingUseCaseTier {

  /** Did not meet the retrieval floor, or was not evaluated. */
  UNQUALIFIED,

  /**
   * Meets the retrieval floor: suitable for first-stage semantic search, where a later stage may
   * rerank or a generator may ground on what was returned.
   */
  SEMANTIC_SEARCH,

  /**
   * Meets the stricter floor at which retrieval can be trusted without a reranking stage, so the
   * top results are used directly.
   */
  PRECISION_RETRIEVAL
}
