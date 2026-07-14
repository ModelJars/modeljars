package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LaunchModelCatalogTest {
  @Test
  void launchCatalogContainsAtLeastOneHundredDistinctPinnedHuggingFaceModels() {
    List<ModelJarDescriptor> descriptors =
        ModelJarRegistry.fromClasspath().descriptors().stream()
            .filter(descriptor -> descriptor.sourceId().startsWith("hf://"))
            .toList();

    long distinctSources =
        descriptors.stream().map(ModelJarDescriptor::sourceId).distinct().count();
    assertTrue(
        distinctSources >= 100,
        () -> "Expected at least 100 distinct Hugging Face models, found " + distinctSources);

    for (ModelJarDescriptor descriptor : descriptors) {
      String revision = descriptor.revision().orElseThrow();
      String downloadUri = descriptor.downloadUri().orElseThrow().toString();

      assertTrue(revision.matches("[0-9a-f]{40,64}"), descriptor.sourceId());
      assertTrue(downloadUri.contains("/resolve/" + revision + "/"), descriptor.sourceId());
      assertEquals(64, descriptor.sha256().orElseThrow().length(), descriptor.sourceId());
      assertTrue(descriptor.sizeBytes().orElseThrow() > 0, descriptor.sourceId());
      String license = descriptor.license().orElseThrow();
      assertNotEquals("NOASSERTION", license, descriptor.sourceId());
    }
  }

  @Test
  void launchMarkersExposeSelfDescribingResourceMetadata() {
    List<ModelJarDescriptor> descriptors =
        ModelJarRegistry.fromClasspath().descriptors().stream()
            .filter(descriptor -> descriptor.format().equals("gguf"))
            .toList();

    assertTrue(descriptors.size() >= 100);
    for (ModelJarDescriptor descriptor : descriptors) {
      assertTrue(descriptor.name().isPresent(), descriptor.alias());
      assertTrue(descriptor.description().isPresent(), descriptor.alias());
      assertTrue(descriptor.sourceUri().isPresent(), descriptor.alias());
      assertTrue(descriptor.downloadUri().isPresent(), descriptor.alias());
      assertTrue(!descriptor.domains().isEmpty(), descriptor.alias());
      assertTrue(descriptor.dimensions().parameterCount().isPresent(), descriptor.alias());
      assertTrue(descriptor.dimensions().contextLength().isPresent(), descriptor.alias());
      assertTrue(descriptor.dimensions().embeddingLength().isPresent(), descriptor.alias());
      assertTrue(descriptor.dimensions().blockCount().isPresent(), descriptor.alias());
      assertTrue(descriptor.dimensions().attentionBlockCount().isPresent(), descriptor.alias());
      assertTrue(descriptor.dimensions().attentionHeadCount().isPresent(), descriptor.alias());
      assertTrue(
          descriptor.estimateMemory(1, KvCachePrecision.FLOAT16).isPresent(), descriptor.alias());
    }
  }

  @Test
  void exposesVerifiedSmolLm3PureJavaBackend() {
    ModelJarDescriptor descriptor =
        ModelJarRegistry.fromClasspath()
            .resolve(
                ModelJarRequirement.forSource("hf://ggml-org/SmolLM3-3B-GGUF")
                    .versionRange("[3.0.0,4.0.0)")
                    .variant("q4_k_m")
                    .backend("pure-java")
                    .capability("text-generation")
                    .build())
            .orElseThrow();

    assertEquals("smollm3_3b_q4_k_m", descriptor.alias());
  }

  @Test
  void resolvesPinnedLaunchModelChoices() {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

    for (ExpectedModel expected : EXPECTED_MODELS) {
      ModelJarDescriptor descriptor =
          registry
              .resolve(
                  ModelJarRequirement.forSource(expected.sourceId())
                      .versionRange(expected.versionRange())
                      .variant(expected.variant())
                      .backend("llama.cpp")
                      .capability("text-generation")
                      .build())
              .orElseThrow(() -> new AssertionError("Missing launch model: " + expected.sourceId()));

      assertEquals(expected.alias(), descriptor.alias());
      assertEquals(expected.architecture(), descriptor.architecture());
      assertEquals(expected.quantization(), descriptor.quantization());
      assertEquals(expected.revision(), descriptor.revision().orElseThrow());
      assertEquals(expected.sha256(), descriptor.sha256().orElseThrow());
      assertEquals(expected.sizeBytes(), descriptor.sizeBytes().orElseThrow());
      assertEquals(expected.license(), descriptor.license().orElseThrow());
      assertEquals(
          expected.filename(), descriptor.localPath().orElseThrow().getFileName().toString());
    }
  }

  private static final List<ExpectedModel> EXPECTED_MODELS =
      List.of(
          new ExpectedModel(
              "hf://ibm-granite/granite-4.1-8b-GGUF",
              "[4.1.0,4.2.0)",
              "q4_k_m",
              "granite_4_1_8b_q4_k_m",
              "granite",
              "Q4_K_M",
              "865b82c2e7970d82e3731278c88c57ae7138359c",
              "ed902ac9eb6adce5a90c6a08c8ea201b50e23fdc5976d1cd0362006afac5309e",
              5_347_914_400L,
              "Apache-2.0",
              "granite-4.1-8b-Q4_K_M.gguf"),
          new ExpectedModel(
              "hf://ggml-org/SmolLM3-3B-GGUF",
              "[3.0.0,4.0.0)",
              "q4_k_m",
              "smollm3_3b_q4_k_m",
              "smollm3",
              "Q4_K_M",
              "4965cb60b150737b68a0408c36aeefb65078f894",
              "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e",
              1_915_305_312L,
              "Apache-2.0",
              "SmolLM3-Q4_K_M.gguf"),
          new ExpectedModel(
              "hf://bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
              "[3.1.0,3.2.0)",
              "q4_k_m",
              "llama_3_1_8b_instruct_q4_k_m",
              "llama",
              "Q4_K_M",
              "bf5b95e96dac0462e2a09145ec66cae9a3f12067",
              "7b064f5842bf9532c91456deda288a1b672397a54fa729aa665952863033557c",
              4_920_739_232L,
              "LicenseRef-Llama-3.1",
              "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
          new ExpectedModel(
              "hf://bartowski/microsoft_Phi-4-mini-instruct-GGUF",
              "[4.0.0,5.0.0)",
              "q4_k_m",
              "phi_4_mini_instruct_q4_k_m",
              "phi3",
              "Q4_K_M",
              "7ff82c2aaa4dde30121698a973765f39be5288c0",
              "01999f17c39cc3074afae5e9c539bc82d45f2dd7faa3917c66cbef76fce8c0c2",
              2_491_874_688L,
              "MIT",
              "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf"),
          new ExpectedModel(
              "hf://mistralai/Ministral-3-3B-Instruct-2512-GGUF",
              "[3.0.0,4.0.0)",
              "q4_k_m",
              "ministral_3_3b_instruct_2512_q4_k_m",
              "mistral3",
              "Q4_K_M",
              "eb599d408350ea2bb60452cb86be7c7b2fc28227",
              "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8",
              2_147_023_008L,
              "Apache-2.0",
              "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
          new ExpectedModel(
              "hf://google/gemma-4-E4B-it-qat-q4_0-gguf",
              "[4.0.0,5.0.0)",
              "q4_0",
              "gemma_4_e4b_it_q4_0",
              "gemma4",
              "Q4_0",
              "7edc6763a77bbca236126a361613b834c5ea0f7a",
              "e8b6a059ba86947a44ace84d6e5679795bc41862c25c30513142588f0e9dba1d",
              5_154_939_136L,
              "Apache-2.0",
              "gemma-4-E4B_q4_0-it.gguf"),
          new ExpectedModel(
              "hf://ggml-org/gemma-3n-E2B-it-GGUF",
              "[3.0.0,4.0.0)",
              "q8_0",
              "gemma_3n_e2b_it_q8_0",
              "gemma3n",
              "Q8_0",
              "989cffaba23976934324f5e3abfabe31b30eb73b",
              "038a47c482e7af3009c462b56a7592e1ade3c7862540717aa1d9dee1760c337b",
              4_788_112_064L,
              "LicenseRef-Gemma",
              "gemma-3n-E2B-it-Q8_0.gguf"));

  private record ExpectedModel(
      String sourceId,
      String versionRange,
      String variant,
      String alias,
      String architecture,
      String quantization,
      String revision,
      String sha256,
      long sizeBytes,
      String license,
      String filename) {}
}
