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
 * How an embedding artifact met the production equivalence policy.
 *
 * <p>Deliberately separate from {@link RagUseCaseTier}. That enum grades how well a model answers
 * from retrieved evidence, which depends on the runtime. This one records whether a runtime
 * faithfully reproduces an embedding model, because retrieval quality is a published property of
 * the weights rather than something a runtime improves on.
 *
 * <p>Only two values, and deliberately so. A finer grading would need retrieval evidence this
 * policy does not produce, and grading on reproduction fidelity alone would be meaningless: an
 * artifact agreeing with the reference at 0.9999 supports exactly the use cases as one agreeing at
 * 0.9995.
 */
public enum EmbeddingUseCaseTier {

  /** Does not reproduce the reference implementation, or was not evaluated. */
  UNQUALIFIED,

  /**
   * Reproduces the reference implementation, so the model's published retrieval quality applies to
   * this runtime and the artifact is usable for semantic search.
   */
  SEMANTIC_SEARCH
}
