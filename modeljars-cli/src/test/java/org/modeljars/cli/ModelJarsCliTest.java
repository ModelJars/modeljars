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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ClasspathModelJarRegistry;
import org.modeljars.ModelArtifactFile;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelInstallProgress;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelMemoryFit;
import org.modeljars.ModelProfileRegistry;
import org.modeljars.ModelVersion;

class ModelJarsCliTest {
  @TempDir Path temporaryDirectory;

  @Test
  void searchesWithAlignedHumanReadableOutputAndStablePlainOutput() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = cli(descriptor);

    Result table = run(cli, "search", "example", "q4");
    Result plain = run(cli, "search", "example", "--output", "plain");

    assertEquals(0, table.status());
    assertTrue(table.output().contains("MODEL"));
    assertTrue(table.output().contains("4.00 GiB"));
    assertTrue(table.output().contains("available"));
    assertFalse(table.output().contains("\t"));
    assertEquals(0, plain.status());
    assertTrue(plain.output().startsWith("SHORT_NAME\tALIAS\tNEW\tCAPABILITIES"));
    assertTrue(plain.output().contains(descriptor.markerCoordinate().toString()));
  }

  @Test
  void filtersSearchesAndEmitsMachineReadableJsonWithoutColor() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = cli(descriptor);

    Result matched =
        run(
            cli,
            "--color",
            "always",
            "search",
            "--capability",
            "text-generation",
            "--backend",
            "java",
            "--output",
            "json");
    Result missed = run(cli, "search", "--capability", "embedding");

    assertEquals(0, matched.status());
    assertTrue(matched.output().contains("\"alias\": \"example_q4_0\""));
    assertFalse(matched.output().contains("\u001B["));
    assertEquals(0, missed.status());
    assertTrue(missed.output().contains("No qualified models matched"));
  }

  @Test
  void searchesCanonicalTagsThroughCommonDiscoveryAliases() {
    ModelJarDescriptor finance = descriptor("finance_model_q4_0", "Q4_0", Set.of("finance"));
    ModelJarsCli cli = cli(finance);

    Result canonical = run(cli, "search", "finance");
    Result alias = run(cli, "search", "fintech");

    assertEquals(0, canonical.status());
    assertTrue(canonical.output().contains("example"));
    assertEquals(0, alias.status());
    assertTrue(alias.output().contains("example"));
  }

  @Test
  void exposesModelsAsACatalogSearchAlias() {
    Result result = run(cli(descriptor()), "models", "example");

    assertEquals(0, result.status());
    assertTrue(result.output().contains("example"));
  }

  @Test
  void generatesAJBangDemoWithoutOverwritingAnExistingFileUnlessRequested() throws Exception {
    ModelJarDescriptor descriptor = descriptor();
    Path script = temporaryDirectory.resolve("model-demo.java");
    ModelJarsCli cli = cli(descriptor);

    Result generated =
        run(
            cli,
            "demo",
            descriptor.alias(),
            "Name one JVM language.",
            "--output-file",
            script.toString());
    String initialSource = Files.readString(script);
    Result protectedFile = run(cli, "demo", descriptor.alias(), "--output-file", script.toString());
    Result replaced =
        run(
            cli,
            "demo",
            descriptor.alias(),
            "A different prompt",
            "--output-file",
            script.toString(),
            "--force");

    assertEquals(0, generated.status());
    assertTrue(generated.output().contains("jbang '" + script.toAbsolutePath() + "'"));
    assertTrue(initialSource.contains("Name one JVM language."));
    assertEquals(2, protectedFile.status());
    assertTrue(protectedFile.error().contains("already exists"));
    assertEquals(0, replaced.status());
    assertTrue(Files.readString(script).contains("A different prompt"));
  }

  @Test
  void createsUsesListsAndRemovesPersistentCustomAliases() {
    ModelJarDescriptor descriptor = descriptor();
    ModelAliasStore aliases = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));
    ModelJarsCli cli = cli(aliases, descriptor);

    Result created = run(cli, "alias", "set", "demo", descriptor.alias());
    Result shown = run(cli, "show", "demo");
    Result listed = run(cli, "alias", "list");
    Result removed = run(cli, "alias", "rm", "demo");

    assertEquals(0, created.status());
    assertTrue(created.output().contains("demo"));
    assertEquals(0, shown.status());
    assertTrue(shown.output().contains(descriptor.markerCoordinate().toString()));
    assertEquals(0, listed.status());
    assertTrue(listed.output().contains("demo"));
    assertTrue(listed.output().contains(descriptor.alias()));
    assertEquals(0, removed.status());
    assertTrue(aliases.aliases().isEmpty());
  }

  @Test
  void generatesAndResolvesTheCatalogShortNameWithoutUserConfiguration() {
    ModelJarDescriptor descriptor = descriptor();
    ModelAliasStore aliases = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));
    ModelJarsCli cli = cli(aliases, descriptor);

    Result shown = run(cli, "show", "example");

    assertEquals(0, shown.status());
    assertTrue(shown.output().contains("Short name"));
    assertTrue(shown.output().contains("example"));
    assertTrue(shown.output().contains(descriptor.alias()));
    assertTrue(aliases.aliases().isEmpty());
  }

  @Test
  void showsTheSourcedGenerationProfileAndComputedMemoryFit() throws IOException {
    ModelJarDescriptor descriptor = descriptor();
    java.util.Properties properties = new java.util.Properties();
    String prefix = "modelProfile." + descriptor.alias() + ".";
    properties.load(
        new java.io.StringReader(
            String.join(
                "\n",
                "modeljars.modelProfiles.schemaVersion=1",
                prefix + "artifactSha256=" + "a".repeat(64),
                prefix + "generation.source.count=1",
                prefix + "generation.source.000.id=gguf",
                prefix + "generation.source.000.kind=gguf-metadata",
                prefix + "generation.source.000.file=model.gguf",
                prefix
                    + "generation.source.000.uri=https://huggingface.co/example/model/model.gguf",
                prefix + "generation.source.000.revision=" + "b".repeat(40),
                prefix + "generation.source.000.sha256=" + "a".repeat(64),
                prefix + "generation.sampling.temperature=0.6",
                prefix + "generation.sampling.temperature.provenance=gguf:general.sampling.temp",
                prefix + "generation.eosTokenIds=151645,151643",
                prefix + "generation.reasoning.openToken=<think>",
                prefix + "generation.reasoning.openTokenId=151667",
                prefix + "generation.reasoning.closeToken=</think>",
                prefix + "generation.reasoning.closeTokenId=151668",
                prefix + "generation.reasoning.thinkingDefault=true",
                prefix + "memory.status=computed",
                prefix + "memory.weightBytes=5027783488",
                prefix + "memory.fixedOverheadBytes=1073741824",
                prefix + "memory.contextLength=40960",
                prefix + "memory.upperBound=false",
                prefix + "memory.kvTypes=f16",
                prefix + "memory.kv.f16.bytesPerToken=147456",
                prefix + "memory.kv.f16.slidingWindowBytesPerToken=0",
                prefix + "memory.kv.f16.contexts=4096",
                prefix + "memory.kv.f16.context.4096.kvBytes=603979776",
                prefix + "memory.kv.f16.context.4096.totalBytes=6705505088",
                prefix + "memory.kv.f16.budgets=8589934592",
                prefix + "memory.kv.f16.budget.8589934592.maxContextTokens=16875",
                prefix + "memory.kv.f16.budget.8589934592.limitedBy=memory")));
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor)),
            (selected, destination, progress) -> destination,
            ModelJarsCliTest::snapshot,
            request -> {
              throw new UnsupportedOperationException();
            },
            Clock.systemUTC(),
            new ModelAliasStore(temporaryDirectory.resolve("aliases.properties")),
            ModelProfileRegistry.fromProperties(properties));

    Result shown = run(cli, "show", descriptor.alias());

    assertEquals(0, shown.status());
    assertTrue(shown.output().contains("GENERATION PROFILE"), shown.output());
    assertTrue(shown.output().contains("Temperature"), shown.output());
    assertTrue(shown.output().contains("0.6 (gguf:general.sampling.temp)"), shown.output());
    assertTrue(shown.output().contains("151645, 151643"), shown.output());
    assertTrue(shown.output().contains("<think> (151667) / </think> (151668)"), shown.output());
    assertTrue(shown.output().contains("MEMORY FIT"), shown.output());
    assertTrue(
        shown.output().contains("computed from GGUF metadata, not measured"), shown.output());
    assertTrue(shown.output().contains("16875"), shown.output());
    assertTrue(shown.output().contains("6.24 GiB"), shown.output());
    assertFalse(shown.output().contains("Top-p"), shown.output());
    assertTrue(
        shown.output().matches("(?s).*Repetition-loop stops\\s+not measured.*"), shown.output());

    properties.load(
        new java.io.StringReader(
            String.join(
                "\n",
                prefix + "repetitionLoop.count=1",
                prefix + "repetitionLoop.000.backend=pure-java",
                prefix + "repetitionLoop.000.workload=general",
                prefix + "repetitionLoop.000.modelsVersion=0.3.41",
                prefix + "repetitionLoop.000.modelsRevision=" + "e".repeat(40),
                prefix + "repetitionLoop.000.detector.maxSpan=32",
                prefix + "repetitionLoop.000.detector.minRepeats=4",
                prefix + "repetitionLoop.000.detector.minLoopTokens=16",
                prefix + "repetitionLoop.000.generations=27",
                prefix + "repetitionLoop.000.stops=3",
                prefix + "repetitionLoop.000.report=benchmark-results/loops.json",
                prefix + "repetitionLoop.000.reportSha256=" + "f".repeat(64))));
    Result measured =
        run(
            new ModelJarsCli(
                ModelJarRegistry.of(List.of(descriptor)),
                (selected, destination, progress) -> destination,
                ModelJarsCliTest::snapshot,
                request -> {
                  throw new UnsupportedOperationException();
                },
                Clock.systemUTC(),
                new ModelAliasStore(temporaryDirectory.resolve("aliases.properties")),
                ModelProfileRegistry.fromProperties(properties)),
            "show",
            descriptor.alias());
    assertTrue(
        measured
            .output()
            .contains(
                "11.1% (3/27) pure-java, general workload, Models 0.3.41, detector 32/4/16;"
                    + " measured at the documented sampling"),
        measured.output());
  }

  @Test
  void omitsProfileSectionsWhenTheCatalogPublishesNone() {
    Result shown = run(cli(descriptor()), "show", "example");

    assertEquals(0, shown.status());
    assertFalse(shown.output().contains("GENERATION PROFILE"));
    assertFalse(shown.output().contains("MEMORY FIT"));
  }

  @Test
  void labelsAGenerativeModelsTransformerWidthWithoutCallingItAnEmbeddingOutput() {
    Result shown = run(cli(descriptor()), "show", "example");

    assertEquals(0, shown.status());
    assertTrue(shown.output().contains("Hidden width"));
    assertFalse(shown.output().contains("Embedding     4096 dimensions"));
  }

  @Test
  void labelsAnEmbeddingModelsOutputWidthAsEmbeddingDimensions() {
    ModelJarDescriptor embedding =
        descriptor(
            "example_embedding_q8_0",
            "Q8_0",
            Set.of("retrieval"),
            Optional.empty(),
            Set.of("text-embedding", "semantic-search"));

    Result shown = run(cli(embedding), "show", "example");

    assertEquals(0, shown.status());
    assertTrue(shown.output().contains("Embedding"));
    assertFalse(shown.output().contains("Hidden width"));
  }

  @Test
  void generatedCatalogNamesTakePrecedenceOverLegacyCustomAliases() {
    ModelJarDescriptor descriptor = descriptor();
    ModelAliasStore aliases = new ModelAliasStore(temporaryDirectory.resolve("aliases.properties"));
    aliases.set("example", "missing_model", Set.of());
    ModelJarsCli cli = cli(aliases, descriptor);

    Result shown = run(cli, "show", "example");

    assertEquals(0, shown.status());
    assertTrue(shown.output().contains(descriptor.alias()));
  }

  @Test
  void highlightsCatalogEntriesPublishedWithinTheLastFortyEightHours() {
    Instant now = Instant.parse("2026-08-27T20:00:00Z");
    ModelJarDescriptor recent =
        descriptor(
            "recent_model_q4_0",
            "Q4_0",
            Set.of("general"),
            Optional.of(now.minus(Duration.ofHours(47))));
    ModelJarDescriptor older =
        descriptor(
            "older_model_q4_0",
            "Q4_0",
            Set.of("general"),
            Optional.of(now.minus(Duration.ofHours(49))));
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(recent, older)),
            (selected, destination, progress) -> destination,
            Clock.fixed(now, ZoneOffset.UTC));

    Result table = run(cli, "--color", "always", "search");
    Result plain = run(cli, "search", "--output", "plain");
    Result json = run(cli, "search", "--output", "json");

    assertTrue(table.output().contains("NEW"), table.output());
    assertTrue(table.output().contains("\u001B[33mrecent_model_q4_0"), table.output());
    assertFalse(table.output().contains("\u001B[33molder_model_q4_0"), table.output());
    assertTrue(plain.output().contains("recent_model_q4_0\ttrue\t"), plain.output());
    assertTrue(plain.output().contains("older_model_q4_0\tfalse\t"), plain.output());
    assertTrue(json.output().contains("\"new\": true"), json.output());
    assertTrue(json.output().contains("\"new\": false"), json.output());
    assertTrue(json.output().contains("\"publishedAt\": \"2026-08-25T21:00:00Z\""), json.output());
  }

  @Test
  void showsTheProfileMemoryFitRatherThanTheLegacyFloorForBundledModels() {
    ModelJarsCli cli =
        new ModelJarsCli(
            ClasspathModelJarRegistry.load(),
            (selected, destination, progress) -> destination,
            ModelJarsCliTest::snapshot,
            request -> {
              throw new UnsupportedOperationException();
            },
            Clock.systemUTC(),
            new ModelAliasStore(temporaryDirectory.resolve("aliases.properties")),
            ModelProfileRegistry.fromClasspath());
    ModelProfileRegistry profiles = ModelProfileRegistry.fromClasspath();
    ModelJarRegistry registry = ClasspathModelJarRegistry.load();

    // Hand-computed at 4,096 tokens with an f16 KV cache (2 bytes per element):
    // total = GGUF bytes + 1 GiB stated overhead + sum over layers of tokens * kvHeads * (k + v) *
    // 2.
    //
    // Gemma 4 26B A4B: 25 sliding-window layers charged min(4096, 1024) tokens with 8 KV heads of
    // 256 + 256, and 5 full-attention layers with 2 KV heads of 512 + 512.
    //   25 * 1024 * 8 * 512 * 2 + 5 * 4096 * 2 * 1024 * 2 = 209,715,200 + 83,886,080 = 293,601,280
    //   16,796,015,136 + 1,073,741,824 + 293,601,280 = 18,163,358,240 (16.9 GiB)
    // The legacy floor charged 30 layers * 16 attention heads * (512 + 512) and no overhead:
    //   16,796,015,136 + 4096 * 30 * 16 * 1024 * 2 = 20,822,546,976 (19.4 GiB).
    assertComputedMemory(
        cli,
        registry,
        profiles,
        "ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m",
        18_163_358_240L,
        "16.9 GiB",
        "19.4 GiB");
    // Qwen3 8B: 36 layers * 4096 * 8 KV heads * (128 + 128) * 2 = 603,979,776
    //   5,027,783,488 + 1,073,741,824 + 603,979,776 = 6,705,505,088 (6.24 GiB); legacy 5.24 GiB.
    assertComputedMemory(
        cli, registry, profiles, "qwen3_8b_q4_k_m", 6_705_505_088L, "6.24 GiB", "5.24 GiB");
    // Gemma 3 1B: 26 layers * 4096 * 1 KV head * (256 + 256) * 2 = 109,051,904. The header declares
    // a 512-token window without a per-layer pattern, so every layer is charged full attention and
    // the value is an upper bound.
    //   806,058,496 + 1,073,741,824 + 109,051,904 = 1,988,852,224 (1.85 GiB); legacy 872.7 MiB.
    Result gemma3 =
        assertComputedMemory(
            cli,
            registry,
            profiles,
            "bartowski_google_gemma_3_1b_it_gguf_q4_k_m",
            1_988_852_224L,
            "1.85 GiB",
            "872.7 MiB");
    assertTrue(gemma3.output().contains("upper bound"), gemma3.output());
  }

  @Test
  void bundledProfilesCarryTheTurnTerminatorReadFromTheChatTemplate() {
    ModelProfileRegistry profiles = ModelProfileRegistry.fromClasspath();
    ModelJarDescriptor gemma3 =
        ClasspathModelJarRegistry.load().descriptors().stream()
            .filter(
                descriptor ->
                    descriptor.alias().equals("bartowski_google_gemma_3_1b_it_gguf_q4_k_m"))
            .findFirst()
            .orElseThrow();
    var generation = profiles.profileFor(gemma3).orElseThrow().generation().orElseThrow();

    assertEquals(List.of(1, 106), generation.eosTokenIds());
    assertEquals(
        List.of("gguf:tokenizer.chat_template(<end_of_turn>)"),
        generation.provenance().get("eosTokenId.106"));
  }

  private static Result assertComputedMemory(
      ModelJarsCli cli,
      ModelJarRegistry registry,
      ModelProfileRegistry profiles,
      String alias,
      long expectedTotalBytes,
      String expected,
      String legacy) {
    ModelJarDescriptor descriptor =
        registry.descriptors().stream()
            .filter(candidate -> candidate.alias().equals(alias))
            .findFirst()
            .orElseThrow();
    ModelMemoryFit fit = profiles.profileFor(descriptor).orElseThrow().memoryFit().orElseThrow();
    assertEquals(
        expectedTotalBytes, fit.contextTotal("f16", 4096).orElseThrow().totalBytes(), alias);

    Result shown = run(cli, "show", alias);
    assertEquals(0, shown.status(), shown.error());
    assertFalse(shown.output().contains("Memory floor"), shown.output());
    assertTrue(
        shown.output().contains(expected + " at 4096 tokens (f16 KV"), alias + shown.output());
    assertTrue(shown.output().contains("computed, not measured"), shown.output());
    assertFalse(shown.output().contains(legacy + " at 4096"), shown.output());
    return shown;
  }

  @Test
  void showsReadableAndExactPinnedArtifactMetadata() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = cli(descriptor);

    Result human = run(cli, "inspect", descriptor.alias());
    Result detailed = run(cli, "show", descriptor.alias(), "--details");
    Result plain = run(cli, "show", descriptor.alias(), "--output", "plain");

    assertEquals(0, human.status());
    assertTrue(human.output().contains("PROVENANCE"));
    assertFalse(human.output().contains("Memory floor"));
    assertFalse(human.output().contains("Computed memory"), "no catalog memory fit, no value");
    assertTrue(human.output().contains("Run 'modeljars coordinates"));
    assertFalse(human.output().contains("Capabilities"));
    assertFalse(human.output().contains("Backends"));
    assertEquals(0, detailed.status());
    assertTrue(detailed.output().contains("Capabilities"));
    assertTrue(detailed.output().contains("Backends"));
    assertEquals(0, plain.status());
    assertTrue(plain.output().contains("revision=" + "b".repeat(40)));
    assertTrue(plain.output().contains("sha256=" + "a".repeat(64)));
    assertTrue(
        plain.output().contains("downloadUri=https://huggingface.co/example/model/model.gguf"));
  }

  @Test
  void printsCopyReadyCoordinatesForMultipleBuildTools() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = cli(descriptor);

    Result result =
        run(cli, "coordinates", descriptor.alias(), "--tool", "maven", "--tool", "gradle-kotlin");

    assertEquals(0, result.status());
    assertTrue(result.output().contains("<groupId>org.modeljars.huggingface</groupId>"));
    assertTrue(
        result.output().contains("implementation(\"" + descriptor.markerCoordinate() + "\")"));
  }

  @Test
  void pullsIntoTheRuntimeCacheLayoutAndSupportsQuietAutomation() {
    ModelJarDescriptor descriptor = descriptor();
    AtomicReference<Path> installedAt = new AtomicReference<>();
    AtomicReference<String> progressMessage = new AtomicReference<>();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor)),
            (selected, destination, progress) -> {
              installedAt.set(destination);
              progress.accept(
                  new ModelInstallProgress.DownloadStarted(
                      selected.alias(),
                      selected.downloadUri().orElseThrow(),
                      destination,
                      0,
                      selected.sizeBytes().orElseThrow()));
              progress.accept(
                  new ModelInstallProgress.Completed(
                      selected.alias(),
                      destination,
                      selected.sizeBytes().orElseThrow(),
                      ModelInstallProgress.Source.DOWNLOAD));
              progressMessage.set("reported");
              return destination;
            });

    Result result =
        run(
            cli,
            "pull",
            descriptor.markerCoordinate().toString(),
            "--cache",
            temporaryDirectory.toString(),
            "--output",
            "plain");
    Result quiet =
        run(cli, "pull", descriptor.alias(), "--cache", temporaryDirectory.toString(), "--quiet");

    Path expected =
        temporaryDirectory
            .resolve("sha256")
            .resolve("aa")
            .resolve("a".repeat(64))
            .resolve("model.gguf")
            .toAbsolutePath()
            .normalize();
    assertEquals(0, result.status());
    assertEquals(expected, installedAt.get());
    assertTrue(result.output().contains("path=" + expected));
    assertTrue(result.error().contains("Downloading " + descriptor.alias()));
    assertFalse(result.error().contains("\u001B["));
    assertEquals("reported", progressMessage.get());
    assertEquals(expected + System.lineSeparator(), quiet.output());
    assertTrue(quiet.error().isEmpty());
  }

  @Test
  void discoversAndPullsEveryMemberOfAQualifiedComposite() {
    ModelJarDescriptor chat = descriptor("qwen3_chat_q4_0", "Q4_0");
    ModelJarDescriptor tools = descriptor("qwen3_tools_q8_0", "Q8_0");
    ModelJarDescriptor hybrid = compositeDescriptor(chat, tools);
    List<String> installed = new java.util.concurrent.CopyOnWriteArrayList<>();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(chat, tools, hybrid)),
            (selected, destination, progress) -> {
              installed.add(selected.alias());
              return destination;
            });

    Result listed = run(cli, "models", "hybrid");
    Result shown = run(cli, "show", hybrid.alias(), "--details");
    Result pulled = run(cli, "pull", hybrid.alias());

    assertEquals(0, listed.status());
    assertTrue(listed.output().contains("hybrid"), listed.output());
    assertTrue(listed.output().contains("MIXED"), listed.output());
    assertEquals(0, shown.status());
    assertTrue(shown.output().contains("Qualified hybrid"), shown.output());
    assertTrue(shown.output().contains("chat"), shown.output());
    assertTrue(shown.output().contains("tools"), shown.output());
    assertEquals(0, pulled.status());
    assertEquals(List.of(chat.alias(), tools.alias()), installed);
    assertTrue(pulled.output().contains("2 members ready"), pulled.output());
    assertTrue(pulled.output().contains(hybrid.markerCoordinate().toString()), pulled.output());
  }

  @Test
  void hidesCompositionComponentsButInstallsThemThroughTheirQualifiedHybrid() {
    ModelJarDescriptor base = descriptor("qwen3_base_q8_0", "Q8_0");
    ModelJarDescriptor adapter = activatedAdapterComponentDescriptor();
    ModelJarDescriptor hybrid = compositeDescriptor(base, adapter);
    List<String> installed = new java.util.concurrent.CopyOnWriteArrayList<>();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(base, adapter, hybrid)),
            (selected, destination, progress) -> {
              installed.add(selected.alias());
              return destination;
            });

    Result listed = run(cli, "models", "--output", "plain");
    Result directPull = run(cli, "pull", adapter.alias());
    Result hybridPull = run(cli, "pull", hybrid.alias());

    assertEquals(0, listed.status());
    assertTrue(listed.output().contains(base.alias()), listed.output());
    assertTrue(listed.output().contains("qwen3_chat_tools_composite"), listed.output());
    assertFalse(listed.output().contains(adapter.alias()), listed.output());
    assertEquals(2, directPull.status());
    assertTrue(
        directPull.error().contains("installed through their qualified hybrid"),
        directPull.error());
    assertEquals(0, hybridPull.status());
    assertEquals(List.of(base.alias(), adapter.alias()), installed);
  }

  @Test
  void keepsJsonCleanAndAllowsProgressToBeDisabled() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor)),
            (selected, destination, progress) -> {
              long size = selected.sizeBytes().orElseThrow();
              progress.accept(
                  new ModelInstallProgress.DownloadStarted(
                      selected.alias(),
                      selected.downloadUri().orElseThrow(),
                      destination,
                      0,
                      size));
              progress.accept(
                  new ModelInstallProgress.DownloadAdvanced(selected.alias(), size, size));
              progress.accept(
                  new ModelInstallProgress.VerificationStarted(
                      selected.alias(), destination, size, ModelInstallProgress.Source.DOWNLOAD));
              progress.accept(
                  new ModelInstallProgress.VerificationAdvanced(selected.alias(), size, size));
              progress.accept(
                  new ModelInstallProgress.Completed(
                      selected.alias(), destination, size, ModelInstallProgress.Source.DOWNLOAD));
              return destination;
            });

    Result json = run(cli, "pull", descriptor.alias(), "--output", "json");
    Result disabled = run(cli, "pull", descriptor.alias(), "--progress", "off");

    assertEquals(0, json.status());
    assertTrue(json.output().stripLeading().startsWith("{"), json.output());
    assertTrue(json.output().contains("\"sizeBytes\": 4294967296"), json.output());
    assertFalse(json.output().contains("Downloading"), json.output());
    assertFalse(json.output().contains("\u001B["), json.output());
    assertTrue(json.error().contains("Downloading " + descriptor.alias()), json.error());
    assertFalse(json.error().contains("\u001B["), json.error());
    assertEquals(0, disabled.status());
    assertTrue(disabled.error().isEmpty(), disabled.error());
  }

  @Test
  void listsAndRemovesCachedModelsByExactIdentity() throws IOException {
    ModelJarDescriptor descriptor = descriptor();
    Path artifact = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(artifact.getParent());
    Files.write(artifact, new byte[] {1, 2, 3, 4});
    ModelJarsCli cli = cli(descriptor);

    Result listed = run(cli, "list", "--cache", temporaryDirectory.toString(), "--coordinates");
    Result detailed = run(cli, "list", "--cache", temporaryDirectory.toString(), "--details");
    Result fuzzyRemoval =
        run(cli, "remove", "example q4", "--cache", temporaryDirectory.toString());
    Result removed = run(cli, "rm", descriptor.alias(), "--cache", temporaryDirectory.toString());

    assertEquals(0, listed.status());
    assertTrue(listed.output().contains(descriptor.markerCoordinate().toString()));
    assertFalse(listed.output().contains("CAPABILITIES"));
    assertTrue(listed.output().contains("  COORDINATE  " + descriptor.markerCoordinate()));
    assertFalse(listed.output().contains("…"));
    assertEquals(0, detailed.status());
    assertTrue(detailed.output().contains("CAPABILITIES"));
    assertTrue(detailed.output().contains("BACKENDS"));
    assertEquals(2, fuzzyRemoval.status());
    assertTrue(fuzzyRemoval.error().contains("requires an exact alias"));
    assertEquals(0, removed.status());
    assertFalse(Files.exists(artifact));
  }

  @Test
  void removesEveryFileInAMultiFileModelBundle() throws IOException {
    ModelJarDescriptor descriptor = multiFileDescriptor();
    Path primary = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Path bundle = primary.getParent();
    Files.createDirectories(bundle);
    Files.write(primary, new byte[] {1, 2, 3, 4});
    Files.writeString(bundle.resolve("config.json"), "{}");

    Result removed =
        run(
            cli(descriptor),
            "remove",
            descriptor.alias(),
            "--cache",
            temporaryDirectory.toString());

    assertEquals(0, removed.status());
    assertFalse(Files.exists(primary));
    assertFalse(Files.exists(bundle.resolve("config.json")));
    assertFalse(Files.exists(bundle));
  }

  @Test
  void refusesToRemoveAMultiFileBundleContainingAnUnexpectedFile() throws IOException {
    ModelJarDescriptor descriptor = multiFileDescriptor();
    Path primary = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Path bundle = primary.getParent();
    Files.createDirectories(bundle);
    Files.write(primary, new byte[] {1, 2, 3, 4});
    Files.writeString(bundle.resolve("config.json"), "{}");
    Path unexpected = Files.writeString(bundle.resolve("notes.txt"), "keep me");

    Result removed =
        run(
            cli(descriptor),
            "remove",
            descriptor.alias(),
            "--cache",
            temporaryDirectory.toString());

    assertEquals(2, removed.status());
    assertTrue(removed.error().contains("unexpected cache file"));
    assertTrue(Files.isRegularFile(primary));
    assertTrue(Files.isRegularFile(bundle.resolve("config.json")));
    assertTrue(Files.isRegularFile(unexpected));
  }

  @Test
  void doesNotListAnIncompleteMultiFileBundleAsCached() throws IOException {
    ModelJarDescriptor descriptor = multiFileDescriptor();
    Path primary = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(primary.getParent());
    Files.write(primary, new byte[] {1, 2, 3, 4});

    Result listed = run(cli(descriptor), "list", "--cache", temporaryDirectory.toString());

    assertEquals(0, listed.status());
    assertFalse(listed.output().contains(descriptor.alias()));
    assertTrue(listed.output().contains("No qualified models are present"));
  }

  @Test
  void reportsTheCompleteMultiFileBundleSizeInSystemInfo() throws IOException {
    ModelJarDescriptor descriptor = multiFileDescriptor();
    Path primary = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(primary.getParent());
    Files.write(primary, new byte[] {1, 2, 3, 4});
    Files.writeString(primary.getParent().resolve("config.json"), "{}");

    Result result =
        run(cli(descriptor), "env", "--cache", temporaryDirectory.toString(), "--output", "json");

    assertEquals(0, result.status());
    assertTrue(result.output().contains("\"cachedModels\": 1"));
    assertTrue(result.output().contains("\"cachedBytes\": 6"));
  }

  @Test
  void reportsTheCompleteDeclaredMultiFileBundleSizeAcrossCatalogShowListAndPull()
      throws IOException {
    ModelJarDescriptor descriptor = multiFileDescriptor();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor)),
            (selected, destination, progress) -> {
              progress.accept(
                  new ModelInstallProgress.Completed(
                      selected.alias(),
                      destination.getParent(),
                      6,
                      ModelInstallProgress.Source.DOWNLOAD));
              return destination.getParent();
            });

    Result searchTable = run(cli, "search");
    Result searchPlain = run(cli, "search", "--output", "plain");
    Result searchJson = run(cli, "search", "--output", "json");
    Result showHuman = run(cli, "show", descriptor.alias());
    Result showJson = run(cli, "show", descriptor.alias(), "--output", "json");
    Result pullHuman = run(cli, "pull", descriptor.alias());
    Result pullJson = run(cli, "pull", descriptor.alias(), "--output", "json");

    Path primary = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(primary.getParent());
    Files.write(primary, new byte[] {1, 2, 3, 4});
    Files.writeString(primary.getParent().resolve("config.json"), "{}");
    Result listTable = run(cli, "list", "--cache", temporaryDirectory.toString());
    Result listPlain =
        run(cli, "list", "--cache", temporaryDirectory.toString(), "--output", "plain");
    Result listJson =
        run(cli, "list", "--cache", temporaryDirectory.toString(), "--output", "json");

    assertTrue(searchTable.output().contains("6 B"), searchTable.output());
    assertTrue(searchPlain.output().contains("\t6\t"), searchPlain.output());
    assertTrue(searchJson.output().contains("\"sizeBytes\": 6"), searchJson.output());
    assertTrue(showHuman.output().contains("Download") && showHuman.output().contains("6 B"));
    assertTrue(showJson.output().contains("\"sizeBytes\": 6"), showJson.output());
    assertTrue(pullHuman.output().contains("6 B"), pullHuman.output());
    assertTrue(pullJson.output().contains("\"sizeBytes\": 6"), pullJson.output());
    assertTrue(listTable.output().contains("6 B"), listTable.output());
    assertTrue(listPlain.output().contains("\t6\t"), listPlain.output());
    assertTrue(listJson.output().contains("\"sizeBytes\": 6"), listJson.output());
  }

  @Test
  void keepsLongListCoordinatesAndAliasesIntactAtNormalTerminalWidth() throws IOException {
    ModelJarDescriptor descriptor =
        descriptor("second_state_e5_mistral_7b_instruct_embedding_gguf_q4_k_m", "Q4_K_M");
    Path artifact = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(artifact.getParent());
    Files.write(artifact, new byte[] {1, 2, 3, 4});

    Result listed =
        run(
            cli(descriptor),
            "list",
            "--cache",
            temporaryDirectory.toString(),
            "--coordinates",
            "--width",
            "120");

    assertEquals(0, listed.status());
    assertTrue(listed.output().contains(descriptor.alias()));
    assertTrue(listed.output().contains(descriptor.markerCoordinate().toString()));
    assertFalse(listed.output().contains("…"));
  }

  @Test
  void reportsHostHardwareBackendsAndCatalogCapabilities() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor)),
            (selected, destination, progress) -> destination,
            ModelJarsCliTest::snapshot);

    Result table = run(cli, "info", "--width", "140");
    Result json = run(cli, "env", "--output", "json");

    assertEquals(0, table.status());
    assertTrue(table.output().contains("███╗"));
    assertTrue(table.output().contains("Apple M4 Pro"));
    assertTrue(table.output().contains("Apple Foundation Models"));
    assertTrue(table.output().contains("eligible"));
    assertTrue(table.output().contains("GPU model offload"));
    assertTrue(table.output().contains("hardware is inventory only"));
    assertEquals(0, json.status());
    assertFalse(json.output().contains("███╗"));
    assertTrue(json.output().contains("\"physicalCores\": 12"));
    assertTrue(json.output().contains("\"status\": \"detected\""));
  }

  @Test
  void honorsExplicitColorAndKeepsNarrowTableValuesComplete() {
    ModelJarsCli cli = cli(descriptor());

    Result compact = run(cli, "search", "--width", "72");
    Result colored = run(cli, "search", "--details", "--color", "always", "--width", "72");

    assertEquals(0, compact.status());
    assertFalse(compact.output().contains("CAPABILITIES"));
    assertFalse(compact.output().contains("BACKENDS"));
    assertEquals(0, colored.status());
    assertTrue(colored.output().contains("\u001B[36m"));
    assertTrue(colored.output().contains("CAPABILITIES"));
    assertTrue(colored.output().contains("BACKENDS"));
    assertFalse(colored.output().contains("…"));
  }

  @Test
  void providesDiscoverableHelpAndDependencySnippetAliases() {
    ModelJarsCli cli = cli(descriptor());

    Result help = run(cli, "help");
    Result snippet = run(cli, "snippet", descriptor().alias(), "--tool", "maven");

    assertEquals(0, help.status());
    for (String command : List.of("coordinates", "coords", "snippet", "dependency", "deps")) {
      assertTrue(help.output().contains(command), command + " missing from help output");
    }
    for (String command : List.of("info", "system", "env")) {
      assertTrue(help.output().contains(command), command + " missing from help output");
    }
    for (String command : List.of("contribute", "submit-model")) {
      assertTrue(help.output().contains(command), command + " missing from help output");
    }
    assertFalse(help.output().contains("generate-completion"));
    assertEquals(0, snippet.status());
    assertTrue(snippet.output().contains("<artifactId>example.model.q4_0</artifactId>"));
  }

  @Test
  void opensAnInteractivePromptOnlyWhenNoArgumentsAreProvided() {
    ModelJarsCli cli = cli(descriptor());
    ByteArrayOutputStream interactiveOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream interactiveError = new ByteArrayOutputStream();
    String commands =
        String.join(System.lineSeparator(), "snippet example_q4_0 --tool gradle-kotlin", "");
    int interactiveStatus =
        cli.launch(
            new String[0],
            new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(interactiveOutput, true, StandardCharsets.UTF_8),
            new PrintStream(interactiveError, true, StandardCharsets.UTF_8),
            false,
            temporaryDirectory.resolve("history"));

    ByteArrayOutputStream oneShotOutput = new ByteArrayOutputStream();
    int oneShotStatus =
        cli.launch(
            new String[] {"version"},
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(oneShotOutput, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            false,
            temporaryDirectory.resolve("unused-history"));

    String promptSession = interactiveOutput.toString(StandardCharsets.UTF_8);
    assertEquals(0, interactiveStatus);
    assertTrue(promptSession.contains("███╗"));
    assertTrue(promptSession.contains("ModelJars "));
    assertTrue(promptSession.contains("modeljars> "));
    assertTrue(
        promptSession.contains("implementation(\"org.modeljars.huggingface:"), promptSession);
    assertTrue(interactiveError.toString(StandardCharsets.UTF_8).isEmpty());
    assertEquals(0, oneShotStatus);
    assertFalse(oneShotOutput.toString(StandardCharsets.UTF_8).contains("modeljars> "));
  }

  @Test
  void rejectsAnAmbiguousUpstreamSource() {
    ModelJarDescriptor first = descriptor();
    ModelJarDescriptor second = descriptor("example_q8_0", "Q8_0");
    ModelJarsCli cli = cli(first, second);

    Result result = run(cli, "inspect", first.sourceId());

    assertEquals(2, result.status());
    assertTrue(result.error().contains("matches multiple variants"));
  }

  @Test
  void preparesAVerifiedContributionFileAndPrintsTheSingleSubmitCommand() throws IOException {
    ContributionDraft draft =
        new ContributionDraft(
            "Qwen/Demo",
            URI.create("https://huggingface.co/Qwen/Demo"),
            "a".repeat(40),
            "Demo Q4_0",
            "gguf",
            Optional.of("qwen2"),
            Optional.of("Apache-2.0"),
            List.of("text-generation", "chat"),
            List.of("general"),
            List.of(new ContributionFile("demo-q4_0.gguf", "model-weights", "b".repeat(64), 42)));
    AtomicReference<ContributionRequest> requested = new AtomicReference<>();
    ContributionService contributions =
        request -> {
          requested.set(request);
          return draft;
        };
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(List.of(descriptor())),
            (selected, destination, progress) -> destination,
            ModelJarsCliTest::snapshot,
            contributions);
    Path output = temporaryDirectory.resolve("candidate.md");

    Result result =
        run(
            cli,
            "contribute",
            "Qwen/Demo",
            "--file",
            "demo-q4_0.gguf",
            "--domain",
            "coding",
            "--output-file",
            output.toString());

    assertEquals(0, result.status());
    assertEquals("Qwen/Demo", requested.get().source());
    assertEquals(List.of("demo-q4_0.gguf"), requested.get().files());
    assertEquals(List.of("coding"), requested.get().domains());
    assertTrue(Files.readString(output).contains("ModelJars candidate submission"));
    assertTrue(result.output().contains("gh issue create --repo ModelJars/modeljars"));
    assertTrue(result.output().contains("--body-file '" + output.toAbsolutePath() + "'"));
  }

  private static ModelJarsCli cli(ModelJarDescriptor... descriptors) {
    return new ModelJarsCli(
        ModelJarRegistry.of(List.of(descriptors)),
        (selected, destination, progress) -> destination);
  }

  private static ModelJarsCli cli(ModelAliasStore aliases, ModelJarDescriptor... descriptors) {
    return new ModelJarsCli(
        ModelJarRegistry.of(List.of(descriptors)),
        (selected, destination, progress) -> destination,
        ModelJarsCliTest::snapshot,
        request -> {
          throw new UnsupportedOperationException();
        },
        Clock.systemUTC(),
        aliases);
  }

  private static Result run(ModelJarsCli cli, String... arguments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int status =
        cli.run(
            arguments,
            new PrintStream(output, true, StandardCharsets.UTF_8),
            new PrintStream(error, true, StandardCharsets.UTF_8));
    return new Result(
        status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
  }

  private static ModelJarDescriptor descriptor() {
    return descriptor("example_q4_0", "Q4_0");
  }

  private static ModelJarDescriptor descriptor(String alias, String quantization) {
    return descriptor(alias, quantization, Set.of("general"));
  }

  private static ModelJarDescriptor descriptor(
      String alias, String quantization, Set<String> domains) {
    return descriptor(alias, quantization, domains, Optional.empty());
  }

  private static ModelJarDescriptor descriptor(
      String alias,
      String quantization,
      Set<String> domains,
      Optional<Instant> catalogPublishedAt) {
    return descriptor(alias, quantization, domains, catalogPublishedAt, Set.of("text-generation"));
  }

  private static ModelJarDescriptor descriptor(
      String alias,
      String quantization,
      Set<String> domains,
      Optional<Instant> catalogPublishedAt,
      Set<String> capabilities) {
    String variant = quantization.toLowerCase(java.util.Locale.ROOT);
    return new ModelJarDescriptor(
        alias,
        "hf://example/model",
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:example.model." + variant + ":1.0.0-" + variant + ".1"),
        ModelVersion.parse("1.0.0"),
        variant,
        "gguf",
        "llama",
        quantization,
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/model")),
        Optional.of(URI.create("https://huggingface.co/example/model/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of("a".repeat(64)),
        Optional.of(4L * 1024L * 1024L * 1024L),
        Optional.of("Apache-2.0"),
        capabilities,
        Set.of("chat-template"),
        java.util.List.of(),
        Map.of("java", true, "native", true),
        Optional.of("Example model"),
        Optional.of("Small deterministic test model."),
        Optional.empty(),
        domains,
        catalogPublishedAt,
        new ModelDimensions(
            Optional.of(7_000_000_000L),
            Optional.of(8192),
            Optional.of(4096),
            Optional.of(32),
            Optional.of(32),
            Optional.of(8),
            Optional.of(11_008),
            Optional.empty(),
            Optional.empty(),
            Optional.of(128),
            Optional.of(128),
            Optional.of(32)));
  }

  private static ModelJarDescriptor multiFileDescriptor() {
    URI base = URI.create("https://huggingface.co/example/model/resolve/" + "b".repeat(40) + "/");
    List<ModelArtifactFile> files =
        List.of(
            new ModelArtifactFile(
                "model.safetensors",
                "weights",
                base.resolve("model.safetensors"),
                "a".repeat(64),
                4),
            new ModelArtifactFile(
                "config.json", "config", base.resolve("config.json"), "c".repeat(64), 2));
    ModelJarDescriptor source = descriptor("example_bf16", "BF16");
    return new ModelJarDescriptor(
        source.alias(),
        source.sourceId(),
        source.markerCoordinate(),
        source.modelVersion(),
        source.variant(),
        "safetensors",
        source.architecture(),
        source.quantization(),
        source.localPath(),
        source.classpathResource(),
        source.sourceUri(),
        Optional.of(base.resolve("model.safetensors")),
        source.revision(),
        Optional.of("a".repeat(64)),
        Optional.of(4L),
        source.license(),
        source.capabilities(),
        Set.of("chat-template", "multi-file-artifact"),
        files,
        source.backendSupport(),
        source.name(),
        source.description(),
        source.licenseUri(),
        source.domains(),
        source.dimensions());
  }

  private static ModelJarDescriptor activatedAdapterComponentDescriptor() {
    ModelJarDescriptor source = multiFileDescriptor();
    return new ModelJarDescriptor(
        "qwen3_tools_r32_adapter",
        "integrallis/qwen3-tools-r32",
        ModelJarCoordinate.parse("org.modeljars.integrallis:qwen3-tools-r32:1.0.0-r32.1"),
        source.modelVersion(),
        "r32",
        source.format(),
        "qwen3-activated-lora",
        "F32",
        source.localPath(),
        source.classpathResource(),
        source.sourceUri(),
        source.downloadUri(),
        source.revision(),
        source.sha256(),
        source.sizeBytes(),
        source.license(),
        Set.of("composition-component"),
        Set.of("multi-file-artifact", "activated-lora-adapter"),
        source.files(),
        Map.of("pure-java", true),
        Optional.of("Qwen3 activated tool adapter"),
        Optional.of("Internal component of a qualified hybrid."),
        source.licenseUri(),
        Set.of("tool-use"),
        source.catalogPublishedAt(),
        ModelDimensions.unknown());
  }

  private static ModelJarDescriptor compositeDescriptor(
      ModelJarDescriptor chat, ModelJarDescriptor tools) {
    return new ModelJarDescriptor(
        "qwen3_chat_tools_composite",
        "modeljars://qwen3-chat-tools",
        ModelJarCoordinate.parse("org.modeljars.composite:qwen3-chat-tools:0.1.38"),
        ModelVersion.parse("0.1.38"),
        "chat-tools",
        "composite",
        "hybrid",
        "MIXED",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://github.com/ModelJars/modeljars")),
        Optional.empty(),
        Optional.of("d".repeat(40)),
        Optional.of("c".repeat(64)),
        Optional.of(chat.sizeBytes().orElseThrow() + tools.sizeBytes().orElseThrow()),
        Optional.of("Apache-2.0"),
        Set.of("text-generation", "chat", "tool-calling"),
        Set.of(
            "virtual-model",
            "composition-member:" + chat.alias(),
            "composition-member:" + tools.alias(),
            "composition-role:chat=" + chat.alias(),
            "composition-role:tools=" + tools.alias()),
        List.of(),
        Map.of("pure-java", true),
        Optional.of("Qwen3 chat + tools hybrid"),
        Optional.of("Qualified virtual model with separate chat and tool members."),
        Optional.empty(),
        Set.of("general", "tool-use"),
        Optional.of(Instant.parse("2026-09-11T20:00:00Z")),
        new ModelDimensions(
            Optional.empty(),
            Optional.of(40_960),
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

  private static SystemCapabilities.Snapshot snapshot() {
    return new SystemCapabilities.Snapshot(
        "macOS 26.0",
        "aarch64",
        "Apple M4 Pro",
        12,
        12,
        48L * 1024L * 1024L * 1024L,
        32L * 1024L * 1024L * 1024L,
        List.of("NEON (128-bit)"),
        List.of(new SystemCapabilities.GraphicsDevice("Apple M4 Pro", Optional.empty())),
        true,
        "GraalVM native executable",
        List.of(
            new SystemCapabilities.Backend(
                "GGUF / CPU",
                SystemCapabilities.Status.READY,
                "generation, embeddings",
                "SIMD selected automatically"),
            new SystemCapabilities.Backend(
                "Apple Foundation Models",
                SystemCapabilities.Status.ELIGIBLE,
                "system text generation",
                "backend-apple verifies availability"),
            new SystemCapabilities.Backend(
                "GPU model offload",
                SystemCapabilities.Status.DETECTED,
                "not yet supported",
                "hardware detected")));
  }

  private record Result(int status, String output, String error) {}
}
