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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarRegistry;
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
    assertTrue(plain.output().startsWith("ALIAS\tCAPABILITIES"));
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
    assertTrue(canonical.output().contains(finance.alias()));
    assertEquals(0, alias.status());
    assertTrue(alias.output().contains(finance.alias()));
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
    assertTrue(human.output().contains("Memory floor"));
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
        plain
            .output()
            .contains("downloadUri=https://huggingface.co/example/model/model.gguf"));
  }

  @Test
  void printsCopyReadyCoordinatesForMultipleBuildTools() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = cli(descriptor);

    Result result =
        run(
            cli,
            "coordinates",
            descriptor.alias(),
            "--tool",
            "maven",
            "--tool",
            "gradle-kotlin");

    assertEquals(0, result.status());
    assertTrue(result.output().contains("<groupId>org.modeljars.huggingface</groupId>"));
    assertTrue(result.output().contains("implementation(\"" + descriptor.markerCoordinate() + "\")"));
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
              progress.accept("downloading " + selected.alias());
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
        run(
            cli,
            "pull",
            descriptor.alias(),
            "--cache",
            temporaryDirectory.toString(),
            "--quiet");

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
    assertEquals("reported", progressMessage.get());
    assertEquals(expected + System.lineSeparator(), quiet.output());
    assertTrue(quiet.error().isEmpty());
  }

  @Test
  void listsAndRemovesCachedModelsByExactIdentity() throws IOException {
    ModelJarDescriptor descriptor = descriptor();
    Path artifact = ModelJarCache.artifactPath(descriptor, temporaryDirectory);
    Files.createDirectories(artifact.getParent());
    Files.write(artifact, new byte[] {1, 2, 3, 4});
    ModelJarsCli cli = cli(descriptor);

    Result listed =
        run(cli, "list", "--cache", temporaryDirectory.toString(), "--coordinates");
    Result detailed =
        run(cli, "list", "--cache", temporaryDirectory.toString(), "--details");
    Result fuzzyRemoval =
        run(cli, "remove", "example q4", "--cache", temporaryDirectory.toString());
    Result removed =
        run(cli, "rm", descriptor.alias(), "--cache", temporaryDirectory.toString());

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
  void keepsLongListCoordinatesAndAliasesIntactAtNormalTerminalWidth() throws IOException {
    ModelJarDescriptor descriptor =
        descriptor(
            "second_state_e5_mistral_7b_instruct_embedding_gguf_q4_k_m", "Q4_K_M");
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
    Result colored =
        run(cli, "search", "--details", "--color", "always", "--width", "72");

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
    assertTrue(help.output().contains("coordinates, coords, snippet, dependency, deps"));
    assertTrue(help.output().contains("info, system, env"));
    assertFalse(help.output().contains("generate-completion"));
    assertEquals(0, snippet.status());
    assertTrue(snippet.output().contains("<artifactId>example.model.q4_0</artifactId>"));
  }

  @Test
  void opensAnInteractivePromptOnlyWhenNoArgumentsAreProvided() {
    ModelJarsCli cli = cli(descriptor());
    ByteArrayOutputStream interactiveOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream interactiveError = new ByteArrayOutputStream();
    int interactiveStatus =
        cli.launch(
            new String[0],
            new ByteArrayInputStream(
                ("snippet example_q4_0 --tool gradle-kotlin\nhelp\nexit\n")
                    .getBytes(StandardCharsets.UTF_8)),
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
    assertTrue(promptSession.contains("Usage: modeljars"));
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

  private static ModelJarsCli cli(ModelJarDescriptor... descriptors) {
    return new ModelJarsCli(
        ModelJarRegistry.of(List.of(descriptors)),
        (selected, destination, progress) -> destination);
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
        status,
        output.toString(StandardCharsets.UTF_8),
        error.toString(StandardCharsets.UTF_8));
  }

  private static ModelJarDescriptor descriptor() {
    return descriptor("example_q4_0", "Q4_0");
  }

  private static ModelJarDescriptor descriptor(String alias, String quantization) {
    return descriptor(alias, quantization, Set.of("general"));
  }

  private static ModelJarDescriptor descriptor(
      String alias, String quantization, Set<String> domains) {
    String variant = quantization.toLowerCase(java.util.Locale.ROOT);
    return new ModelJarDescriptor(
        alias,
        "hf://example/model",
        ModelJarCoordinate.parse(
            "org.modeljars.huggingface:example.model."
                + variant
                + ":1.0.0-"
                + variant
                + ".1"),
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
        Set.of("text-generation"),
        Set.of("chat-template"),
        Map.of("java", true, "native", true),
        Optional.of("Example model"),
        Optional.of("Small deterministic test model."),
        Optional.empty(),
        domains,
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
