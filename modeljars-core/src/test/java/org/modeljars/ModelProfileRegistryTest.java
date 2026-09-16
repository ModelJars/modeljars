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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelProfileRegistryTest {
  private static final String SHA = "a".repeat(64);
  private static final String PREFIX = "modelProfile.example_q4_k_m.";

  @Test
  void parsesAProvenanceCarryingGenerationProfile() throws IOException {
    ModelProfile profile = ModelProfileRegistry.fromProperties(properties()).profiles().getFirst();
    ModelGenerationProfile generation = profile.generation().orElseThrow();

    assertEquals("example_q4_k_m", profile.modelAlias());
    assertEquals(SHA, profile.artifactSha256());
    assertEquals(2, generation.sources().size());
    ModelProfileSource gguf = generation.sources().getFirst();
    assertEquals("gguf", gguf.id());
    assertEquals("gguf-metadata", gguf.kind());
    assertEquals("Example-Q4_K_M.gguf", gguf.file());
    assertEquals(
        URI.create("https://huggingface.co/example/model/resolve/rev/Example-Q4_K_M.gguf"),
        gguf.uri());
    assertEquals("b".repeat(40), gguf.revision());
    assertEquals(OptionalDouble.of(0.7), generation.temperature());
    assertEquals(OptionalDouble.of(0.8), generation.topP());
    assertEquals(OptionalInt.of(20), generation.topK());
    assertEquals(OptionalDouble.empty(), generation.minP());
    assertEquals(OptionalDouble.of(1.1), generation.repetitionPenalty());
    assertEquals(Optional.of(true), generation.doSample());
    assertEquals(List.of(151645, 151643), generation.eosTokenIds());
    assertEquals(
        Optional.of(new ModelGenerationProfile.ReasoningToken("<think>", 151667)),
        generation.reasoningOpenToken());
    assertEquals(
        Optional.of(new ModelGenerationProfile.ReasoningToken("</think>", 151668)),
        generation.reasoningCloseToken());
    assertEquals(Optional.of(true), generation.thinkingDefault());
    assertEquals(
        List.of("generation-config:temperature", "gguf:general.sampling.temp"),
        generation.provenance().get("sampling.temperature"));
    assertEquals(
        List.of("gguf:tokenizer.ggml.eos_token_id", "generation-config:eos_token_id"),
        generation.provenance().get("eosTokenId.151645"));
    assertEquals(
        List.of("gguf:tokenizer.chat_template"),
        generation.provenance().get("reasoning.thinkingDefault"));
    assertEquals(
        List.of("gguf:general.sampling.penalty_repeat=1.0"),
        generation.conflicts().get("sampling.repetitionPenalty"));
  }

  @Test
  void parsesAComputedMemoryFitTable() throws IOException {
    ModelMemoryFit fit =
        ModelProfileRegistry.fromProperties(properties())
            .profiles()
            .getFirst()
            .memoryFit()
            .orElseThrow();

    assertEquals(5_027_783_488L, fit.weightBytes());
    assertEquals(1_073_741_824L, fit.fixedOverheadBytes());
    assertEquals(40_960, fit.contextLength());
    assertEquals(OptionalInt.empty(), fit.slidingWindow());
    assertFalse(fit.upperBound());
    assertFalse(fit.recurrentStateExcluded());
    assertEquals(List.of("note one"), fit.notes());
    ModelMemoryFit.KvCacheFit f16 = fit.kvCache("f16").orElseThrow();
    assertEquals(147_456L, f16.bytesPerToken());
    assertEquals(0L, f16.slidingWindowBytesPerToken());
    assertEquals(
        List.of(
            new ModelMemoryFit.ContextTotal(4096, 603_979_776L, 6_705_505_088L),
            new ModelMemoryFit.ContextTotal(40960, 6_039_797_760L, 12_141_323_072L)),
        f16.contexts());
    assertEquals(
        List.of(
            new ModelMemoryFit.BudgetFit(8_589_934_592L, 16_875, "memory"),
            new ModelMemoryFit.BudgetFit(17_179_869_184L, 40_960, "context-length")),
        f16.budgets());
    assertTrue(fit.kvCache("q8_0").isEmpty());
  }

  @Test
  void bindsAProfileOnlyToTheExactArtifact() throws IOException {
    ModelProfileRegistry registry = ModelProfileRegistry.fromProperties(properties());

    assertTrue(registry.profileFor(descriptor("example_q4_k_m", SHA)).isPresent());
    assertTrue(registry.profileFor(descriptor("example_q4_k_m", "c".repeat(64))).isEmpty());
    assertTrue(registry.profileFor(descriptor("other_q4_k_m", SHA)).isEmpty());
  }

  @Test
  void treatsARegistryWithoutProfilesAsEmptyAndRejectsUnknownSchemas() throws IOException {
    Properties marker = new Properties();
    marker.setProperty("model.example_q4_k_m.sourceId", "hf://example/model");
    assertTrue(ModelProfileRegistry.fromProperties(marker).profiles().isEmpty());
    assertTrue(ModelProfileRegistry.empty().profiles().isEmpty());

    Properties future = properties();
    future.setProperty("modeljars.modelProfiles.schemaVersion", "2");
    assertThrows(ModelJarException.class, () -> ModelProfileRegistry.fromProperties(future));

    Properties unversioned = properties();
    unversioned.remove("modeljars.modelProfiles.schemaVersion");
    assertThrows(ModelJarException.class, () -> ModelProfileRegistry.fromProperties(unversioned));
  }

  private static Properties properties() throws IOException {
    Properties properties = new Properties();
    properties.load(
        new StringReader(
            String.join(
                "\n",
                "modeljars.modelProfiles.schemaVersion=1",
                PREFIX + "artifactSha256=" + SHA,
                PREFIX + "generation.source.count=2",
                PREFIX + "generation.source.000.id=gguf",
                PREFIX + "generation.source.000.kind=gguf-metadata",
                PREFIX + "generation.source.000.file=Example-Q4_K_M.gguf",
                PREFIX
                    + "generation.source.000.uri=https://huggingface.co/example/model/resolve/rev/Example-Q4_K_M.gguf",
                PREFIX + "generation.source.000.revision=" + "b".repeat(40),
                PREFIX + "generation.source.000.sha256=" + SHA,
                PREFIX + "generation.source.001.id=generation-config",
                PREFIX + "generation.source.001.kind=hf-generation-config",
                PREFIX + "generation.source.001.file=generation_config.json",
                PREFIX
                    + "generation.source.001.uri=https://huggingface.co/example/model/resolve/rev/generation_config.json",
                PREFIX + "generation.source.001.revision=" + "b".repeat(40),
                PREFIX + "generation.source.001.sha256=" + "d".repeat(64),
                PREFIX + "generation.sampling.doSample=true",
                PREFIX + "generation.sampling.doSample.provenance=generation-config:do_sample",
                PREFIX + "generation.sampling.temperature=0.7",
                PREFIX
                    + "generation.sampling.temperature.provenance=generation-config:temperature,gguf:general.sampling.temp",
                PREFIX + "generation.sampling.topP=0.8",
                PREFIX + "generation.sampling.topP.provenance=generation-config:top_p",
                PREFIX + "generation.sampling.topK=20",
                PREFIX + "generation.sampling.topK.provenance=generation-config:top_k",
                PREFIX + "generation.sampling.repetitionPenalty=1.1",
                PREFIX
                    + "generation.sampling.repetitionPenalty.provenance=generation-config:repetition_penalty",
                PREFIX
                    + "generation.sampling.repetitionPenalty.conflicts=gguf:general.sampling.penalty_repeat=1.0",
                PREFIX + "generation.eosTokenIds=151645,151643",
                PREFIX
                    + "generation.eosTokenId.151645.provenance=gguf:tokenizer.ggml.eos_token_id,generation-config:eos_token_id",
                PREFIX + "generation.eosTokenId.151643.provenance=generation-config:eos_token_id",
                PREFIX + "generation.reasoning.openToken=<think>",
                PREFIX + "generation.reasoning.openTokenId=151667",
                PREFIX + "generation.reasoning.closeToken=</think>",
                PREFIX + "generation.reasoning.closeTokenId=151668",
                PREFIX + "generation.reasoning.markers.provenance=gguf:tokenizer.ggml.tokens",
                PREFIX + "generation.reasoning.thinkingDefault=true",
                PREFIX + "generation.reasoning.thinkingDefault.rule=opt-out-enable-thinking",
                PREFIX
                    + "generation.reasoning.thinkingDefault.provenance=gguf:tokenizer.chat_template",
                PREFIX + "memory.status=computed",
                PREFIX + "memory.weightBytes=5027783488",
                PREFIX + "memory.fixedOverheadBytes=1073741824",
                PREFIX + "memory.contextLength=40960",
                PREFIX + "memory.upperBound=false",
                PREFIX + "memory.recurrentStateExcluded=false",
                PREFIX + "memory.note.count=1",
                PREFIX + "memory.note.000=note one",
                PREFIX + "memory.kvTypes=f16",
                PREFIX + "memory.kv.f16.bytesPerToken=147456",
                PREFIX + "memory.kv.f16.slidingWindowBytesPerToken=0",
                PREFIX + "memory.kv.f16.contexts=4096,40960",
                PREFIX + "memory.kv.f16.context.4096.kvBytes=603979776",
                PREFIX + "memory.kv.f16.context.4096.totalBytes=6705505088",
                PREFIX + "memory.kv.f16.context.40960.kvBytes=6039797760",
                PREFIX + "memory.kv.f16.context.40960.totalBytes=12141323072",
                PREFIX + "memory.kv.f16.budgets=8589934592,17179869184",
                PREFIX + "memory.kv.f16.budget.8589934592.maxContextTokens=16875",
                PREFIX + "memory.kv.f16.budget.8589934592.limitedBy=memory",
                PREFIX + "memory.kv.f16.budget.17179869184.maxContextTokens=40960",
                PREFIX + "memory.kv.f16.budget.17179869184.limitedBy=context-length")));
    return properties;
  }

  private static ModelJarDescriptor descriptor(String alias, String sha256) {
    return new ModelJarDescriptor(
        alias,
        "hf://example/model",
        ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q4_k_m:1.0.0-q4_k_m.1"),
        ModelVersion.parse("1.0.0"),
        "q4_k_m",
        "gguf",
        "qwen3",
        "Q4_K_M",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/model/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of(sha256),
        Optional.of(5_027_783_488L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation"),
        Set.of(),
        List.of(),
        Map.of("pure-java", true),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Set.of("general"),
        ModelDimensions.unknown());
  }
}
