package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelVersion;

class ModelJarsCliTest {
  @TempDir Path temporaryDirectory;

  @Test
  void listsAndInspectsPinnedArtifactMetadata() {
    ModelJarDescriptor descriptor = descriptor();
    ModelJarsCli cli = new ModelJarsCli(ModelJarRegistry.of(java.util.List.of(descriptor)), (d, p) -> p);

    Result listed = run(cli, "search", "example", "q4");
    Result inspected = run(cli, "inspect", descriptor.alias());

    assertEquals(0, listed.status());
    assertTrue(listed.output().contains(descriptor.markerCoordinate().toString()));
    assertEquals(0, inspected.status());
    assertTrue(inspected.output().contains("revision=" + "b".repeat(40)));
    assertTrue(inspected.output().contains("sha256=" + "a".repeat(64)));
    assertTrue(inspected.output().contains("download_uri=https://huggingface.co/example/model/model.gguf"));
  }

  @Test
  void pullsIntoTheRuntimeCacheLayout() {
    ModelJarDescriptor descriptor = descriptor();
    AtomicReference<Path> installedAt = new AtomicReference<>();
    ModelJarsCli cli =
        new ModelJarsCli(
            ModelJarRegistry.of(java.util.List.of(descriptor)),
            (selected, destination) -> {
              installedAt.set(destination);
              return destination;
            });

    Result result = run(cli, "pull", descriptor.markerCoordinate().toString(), "--cache", temporaryDirectory.toString());

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
  }

  @Test
  void rejectsAnAmbiguousUpstreamSource() {
    ModelJarDescriptor first = descriptor();
    ModelJarDescriptor second =
        new ModelJarDescriptor(
            "example_q8_0",
            first.sourceId(),
            ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q8_0:1.0.0-q8_0.1"),
            first.modelVersion(),
            "q8_0",
            first.format(),
            first.architecture(),
            "Q8_0",
            first.localPath(),
            first.classpathResource(),
            first.sourceUri(),
            first.downloadUri(),
            first.revision(),
            first.sha256(),
            first.sizeBytes(),
            first.license(),
            first.capabilities(),
            first.features(),
            first.backendSupport(),
            first.name(),
            first.description(),
            first.licenseUri(),
            first.domains(),
            first.dimensions());
    ModelJarsCli cli =
        new ModelJarsCli(ModelJarRegistry.of(java.util.List.of(first, second)), (d, p) -> p);

    Result result = run(cli, "inspect", first.sourceId());

    assertEquals(2, result.status());
    assertTrue(result.error().contains("matches multiple variants"));
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
    return new ModelJarDescriptor(
        "example_q4_0",
        "hf://example/model",
        ModelJarCoordinate.parse("org.modeljars.huggingface:example.model.q4_0:1.0.0-q4_0.1"),
        ModelVersion.parse("1.0.0"),
        "q4_0",
        "gguf",
        "llama",
        "Q4_0",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://huggingface.co/example/model")),
        Optional.of(URI.create("https://huggingface.co/example/model/model.gguf")),
        Optional.of("b".repeat(40)),
        Optional.of("a".repeat(64)),
        Optional.of(4L),
        Optional.of("Apache-2.0"),
        Set.of("text-generation"),
        Set.of(),
        Map.of("java", true),
        Optional.of("Example"),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        ModelDimensions.unknown());
  }

  private record Result(int status, String output, String error) {}
}
