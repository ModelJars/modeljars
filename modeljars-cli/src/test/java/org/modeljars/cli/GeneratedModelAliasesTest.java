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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelVersion;

class GeneratedModelAliasesTest {
  @Test
  void usesTheFamilyNameWhenItIdentifiesTheOnlyModel() {
    ModelJarDescriptor qwen = descriptor("qwen3_0_6b_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0");

    assertEquals(Map.of("qwen", qwen.alias()), GeneratedModelAliases.from(List.of(qwen)));
  }

  @Test
  void addsOnlyTheModelSizeNeededToDistinguishModelsInTheSameFamily() {
    ModelJarDescriptor small = descriptor("qwen3_0_6b_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0");
    ModelJarDescriptor larger = descriptor("qwen3_1_7b_q8_0", "Qwen3 1.7B GGUF Q8_0", "Q8_0");

    assertEquals(
        Map.of("qwen-0.6b", small.alias(), "qwen-1.7b", larger.alias()),
        GeneratedModelAliases.from(List.of(small, larger)));
  }

  @Test
  void addsPurposeAndQuantizationOnlyWhenTheyAreNeededForUniqueness() {
    ModelJarDescriptor chat =
        descriptor(
            "qwen3_0_6b_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0", Set.of("text-generation", "chat"));
    ModelJarDescriptor embedding =
        descriptor(
            "qwen_qwen3_embedding_0_6b_gguf_q8_0",
            "Qwen3 Embedding 0.6B GGUF Q8_0",
            "Q8_0",
            Set.of("text-embedding", "semantic-search"));
    ModelJarDescriptor coderQ4 =
        descriptor(
            "qwen2_5_coder_0_5b_instruct_q4_0",
            "Qwen2.5 Coder 0.5B Instruct GGUF Q4_0",
            "Q4_0",
            Set.of("text-generation", "chat", "code-completion"));
    ModelJarDescriptor coderQ8 =
        descriptor(
            "qwen2_5_coder_0_5b_instruct_q8_0",
            "Qwen2.5 Coder 0.5B Instruct GGUF Q8_0",
            "Q8_0",
            Set.of("text-generation", "chat", "code-completion"));

    assertEquals(
        Map.of(
            "qwen", chat.alias(),
            "qwen-embedding", embedding.alias(),
            "qwen-coder-q4", coderQ4.alias(),
            "qwen-coder-q8", coderQ8.alias()),
        GeneratedModelAliases.from(List.of(chat, embedding, coderQ4, coderQ8)));
  }

  @Test
  void generationIsIndependentOfCatalogOrder() {
    ModelJarDescriptor small = descriptor("qwen3_0_6b_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0");
    ModelJarDescriptor larger = descriptor("qwen3_1_7b_q8_0", "Qwen3 1.7B GGUF Q8_0", "Q8_0");

    assertEquals(
        GeneratedModelAliases.from(List.of(small, larger)),
        GeneratedModelAliases.from(List.of(larger, small)));
  }

  @Test
  void skipsPublisherWordsAndNeverUsesFragmentsOfTheQuantizationAsDiscriminators() {
    ModelJarDescriptor needle =
        descriptor(
            "cactus_compute_needle2_cact_cq2_mixed",
            "Cactus Compute Needle 2 CACT CQ2 Mixed",
            "CQ2_MIXED",
            Set.of("tool-calling"));
    ModelJarDescriptor qwenBf16 = descriptor("qwen_bf16", "Qwen2.5 0.5B BF16", "BF16");
    ModelJarDescriptor qwenQ4 = descriptor("qwen_q4_k_m", "Qwen2.5 0.5B GGUF Q4_K_M", "Q4_K_M");

    assertEquals(Map.of("needle", needle.alias()), GeneratedModelAliases.from(List.of(needle)));
    assertEquals(
        Map.of("qwen-bf16", qwenBf16.alias(), "qwen-q4", qwenQ4.alias()),
        GeneratedModelAliases.from(List.of(qwenBf16, qwenQ4)));
  }

  @Test
  void givesTheMsMarcoCrossEncoderAHumanRerankerName() {
    ModelJarDescriptor reranker =
        descriptor(
            "cstr_ms_marco_minilm_l6_v2_gguf_q4_k_imatrix_g7c_f7",
            "MS MARCO MiniLM L6 v2 reranker corrected Q4_K imatrix",
            "Q4_K",
            Set.of("reranking", "text-ranking"));

    assertEquals(
        Map.of("minilm-reranker", reranker.alias()), GeneratedModelAliases.from(List.of(reranker)));
  }

  @Test
  void expandsTheQuantizationOnlyWhenTwoVariantsShareTheSameCoarseName() {
    ModelJarDescriptor q4 = descriptor("qwen_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0");
    ModelJarDescriptor q4Km = descriptor("qwen_q4_k_m", "Qwen3 0.6B GGUF Q4_K_M", "Q4_K_M");

    assertEquals(
        Map.of("qwen-q4-0", q4.alias(), "qwen-q4-k-m", q4Km.alias()),
        GeneratedModelAliases.from(List.of(q4, q4Km)));
  }

  @Test
  void neverGeneratesANameThatSelectsAnotherModelsCanonicalId() {
    ModelJarDescriptor canonicalQwen = descriptor("qwen", "Acme Alpha 1B GGUF Q4_0", "Q4_0");
    ModelJarDescriptor qwenModel = descriptor("qwen3_0_6b_q4_0", "Qwen3 0.6B GGUF Q4_0", "Q4_0");

    assertEquals(
        Map.of("acme", canonicalQwen.alias(), "qwen-0.6b", qwenModel.alias()),
        GeneratedModelAliases.from(List.of(canonicalQwen, qwenModel)));
  }

  private static ModelJarDescriptor descriptor(String id, String name, String quantization) {
    return descriptor(id, name, quantization, Set.of("text-generation", "chat"));
  }

  private static ModelJarDescriptor descriptor(
      String id, String name, String quantization, Set<String> capabilities) {
    String variant = quantization.toLowerCase(java.util.Locale.ROOT);
    return new ModelJarDescriptor(
        id,
        "hf://example/" + id,
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:example." + id.replace('_', '-') + ":1.0.0-" + variant),
        ModelVersion.parse("1.0.0"),
        variant,
        "gguf",
        "qwen3",
        quantization,
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/" + id)),
        Optional.of(URI.create("https://huggingface.co/example/" + id + "/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of("a".repeat(64)),
        Optional.of(1024L),
        Optional.of("Apache-2.0"),
        capabilities,
        Set.of(),
        List.of(),
        Map.of("pure-java", true),
        Optional.of(name),
        Optional.empty(),
        Optional.empty(),
        Set.of("general"),
        Optional.empty(),
        new ModelDimensions(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
  }
}
