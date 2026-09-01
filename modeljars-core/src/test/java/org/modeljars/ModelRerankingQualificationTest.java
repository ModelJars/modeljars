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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelRerankingQualificationTest {

  private static ModelRerankingQualification qualification(
      boolean qualified, double onnxDelta, double artifactDelta, boolean exactOrder, double p95) {
    return new ModelRerankingQualification(
        "cstr_ms_marco_minilm_l6_v2_gguf_q4_k_imatrix_g7c_f7",
        "MS MARCO MiniLM L6 v2 corrected Q4_K imatrix",
        "pure-java",
        "models-0.3.23",
        "reranking-oracle-and-latency-v1",
        "0752a92bc33289f3fe230d1a33cce471f1a7fe1c8d5a491ca7fff6b068b0fc83",
        19_986_112L,
        "benchmark-results/reranking/ms-marco-minilm-l6-v2-q4-k-imatrix/performance-intel-mac.json",
        "114f393304830f279b89dfc7f04e3e6ea7055692b517f58956a39bc8f13c0a4f",
        qualified,
        6,
        onnxDelta,
        artifactDelta,
        exactOrder,
        221.346,
        p95,
        930.176,
        7.875);
  }

  @Test
  void acceptsEvidenceThatPassesNumericalOrderingAndLatencyGates() {
    assertTrue(qualification(true, 0.101034, 0.036392, true, 174.357).productionUsable());
  }

  @Test
  void preservesFailedEvidenceWithoutMakingItProductionUsable() {
    assertFalse(qualification(false, 1.0, 1.0, false, 5000.0).productionUsable());
  }

  @Test
  void rejectsAQualifiedClaimThatMissesAnyGate() {
    assertThrows(
        IllegalArgumentException.class, () -> qualification(true, 0.151, 0.036392, true, 174.357));
    assertThrows(
        IllegalArgumentException.class, () -> qualification(true, 0.101034, 0.051, true, 174.357));
    assertThrows(
        IllegalArgumentException.class,
        () -> qualification(true, 0.101034, 0.036392, false, 174.357));
    assertThrows(
        IllegalArgumentException.class, () -> qualification(true, 0.101034, 0.036392, true, 251.0));
  }
}
