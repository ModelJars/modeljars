package org.modeljars.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarException;
import org.modeljars.ModelJarInstaller;
import org.modeljars.ModelJarRegistry;

/** Standalone command line interface for discovering and prefetching qualified ModelJars. */
public final class ModelJarsCli {
  private static final String USAGE =
      """
      Usage: modeljars <command> [arguments]

      Commands:
        search [query]               Search the bundled qualified model catalog
        list                         List models already present in the local cache
        show <model>                 Show pinned source and artifact metadata
        pull <model>                 Download and verify a model in the runtime cache
             [--cache <directory>]
        cache-dir                    Print the configured runtime cache root
        version                      Print the CLI version
        help                         Show this help

      Cache configuration:
        --cache overrides modeljars.cache and MODELJARS_CACHE for one pull.
      """;

  private final ModelJarRegistry registry;
  private final ArtifactInstaller installer;

  ModelJarsCli(ModelJarRegistry registry, ArtifactInstaller installer) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.installer = Objects.requireNonNull(installer, "installer");
  }

  /**
   * Runs the standalone CLI.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarInstaller installer = ModelJarInstaller.reportingTo(registry, System.err::println);
    int status =
        new ModelJarsCli(registry, installer::install).run(args, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  int run(String[] args, PrintStream output, PrintStream error) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(error, "error");
    try {
      if (args.length == 0) {
        error.print(USAGE);
        return 2;
      }
      return switch (args[0]) {
        case "help", "--help", "-h" -> help(args, output);
        case "version", "--version" -> version(args, output);
        case "cache-dir" -> cacheDirectory(args, output);
        case "search" -> search(args, output);
        case "list" -> list(args, output);
        case "show", "inspect" -> inspect(args, output);
        case "pull" -> pull(args, output);
        default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
      };
    } catch (IllegalArgumentException | ModelJarException exception) {
      error.println("Error: " + exception.getMessage());
      error.println("Run 'modeljars help' for usage.");
      return 2;
    }
  }

  private static int help(String[] args, PrintStream output) {
    requireArgumentCount(args, 1);
    output.print(USAGE);
    return 0;
  }

  private static int version(String[] args, PrintStream output) {
    requireArgumentCount(args, 1);
    String version = ModelJarsCli.class.getPackage().getImplementationVersion();
    output.println(version == null ? "development" : version);
    return 0;
  }

  private static int cacheDirectory(String[] args, PrintStream output) {
    requireArgumentCount(args, 1);
    output.println(ModelJarCache.defaultDirectory());
    return 0;
  }

  private int search(String[] args, PrintStream output) {
    String query =
        args.length == 1
            ? ""
            : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                .toLowerCase(Locale.ROOT);
    output.println("ALIAS\tFORMAT\tSIZE_BYTES\tCOORDINATE");
    registry.descriptors().stream()
        .filter(descriptor -> matchesQuery(descriptor, query))
        .sorted(
            Comparator.comparing(ModelJarDescriptor::alias)
                .thenComparing(descriptor -> descriptor.markerCoordinate().toString()))
        .forEach(
            descriptor ->
                output.printf(
                    Locale.ROOT,
                    "%s\t%s\t%s\t%s%n",
                    descriptor.alias(),
                    descriptor.format(),
                    descriptor.sizeBytes().map(String::valueOf).orElse(""),
                    descriptor.markerCoordinate()));
    return 0;
  }

  private int list(String[] args, PrintStream output) {
    requireArgumentCount(args, 1);
    output.println("ALIAS\tSIZE_BYTES\tPATH");
    registry.descriptors().stream()
        .sorted(Comparator.comparing(ModelJarDescriptor::alias))
        .map(descriptor -> new CachedModel(descriptor, ModelJarCache.artifactPath(descriptor)))
        .filter(cached -> Files.isRegularFile(cached.path()))
        .forEach(
            cached ->
                output.printf(
                    Locale.ROOT,
                    "%s\t%s\t%s%n",
                    cached.descriptor().alias(),
                    cached.descriptor().sizeBytes().map(String::valueOf).orElse(""),
                    cached.path()));
    return 0;
  }

  private int inspect(String[] args, PrintStream output) {
    requireArgumentCount(args, 2);
    printDescriptor(resolve(args[1]), output);
    return 0;
  }

  private int pull(String[] args, PrintStream output) {
    if (args.length != 2 && args.length != 4) {
      throw new IllegalArgumentException(
          "pull requires an alias or coordinate and optional --cache <directory>");
    }
    Path cacheDirectory = ModelJarCache.defaultDirectory();
    if (args.length == 4) {
      if (!args[2].equals("--cache")) {
        throw new IllegalArgumentException("Unknown pull option: " + args[2]);
      }
      if (args[3].isBlank()) {
        throw new IllegalArgumentException("--cache must not be blank");
      }
      cacheDirectory = Path.of(args[3]);
    }

    ModelJarDescriptor descriptor = resolve(args[1]);
    Path destination = ModelJarCache.artifactPath(descriptor, cacheDirectory);
    Path artifact = installer.install(descriptor, destination);
    output.println("coordinate=" + descriptor.markerCoordinate());
    output.println("sha256=" + descriptor.sha256().orElse(""));
    output.println("path=" + artifact.toAbsolutePath().normalize());
    return 0;
  }

  private ModelJarDescriptor resolve(String selector) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException("Model selector must not be blank");
    }
    String requested = selector.trim();
    List<ModelJarDescriptor> exact =
        registry.descriptors().stream()
            .filter(
                descriptor ->
                    descriptor.alias().equals(requested)
                        || descriptor.markerCoordinate().toString().equals(requested))
            .toList();
    if (exact.size() == 1) {
      return exact.getFirst();
    }

    List<ModelJarDescriptor> sourceMatches =
        registry.descriptors().stream()
            .filter(descriptor -> descriptor.sourceId().equals(requested))
            .toList();
    if (sourceMatches.size() == 1) {
      return sourceMatches.getFirst();
    }
    if (sourceMatches.size() > 1) {
      throw new IllegalArgumentException(
          "Source matches multiple variants; use an alias or exact marker coordinate: " + requested);
    }
    List<ModelJarDescriptor> queryMatches =
        registry.descriptors().stream()
            .filter(descriptor -> matchesQuery(descriptor, requested.toLowerCase(Locale.ROOT)))
            .toList();
    if (queryMatches.size() == 1) {
      return queryMatches.getFirst();
    }
    if (queryMatches.size() > 1) {
      String aliases =
          queryMatches.stream()
              .map(ModelJarDescriptor::alias)
              .sorted()
              .limit(5)
              .reduce((left, right) -> left + ", " + right)
              .orElse("");
      throw new IllegalArgumentException(
          "Model selector is ambiguous; use an exact alias: " + aliases);
    }
    throw new IllegalArgumentException("No qualified model matched: " + requested);
  }

  private static boolean matchesQuery(ModelJarDescriptor descriptor, String query) {
    if (query.isEmpty()) {
      return true;
    }
    String searchable =
        String.join(
                " ",
                descriptor.alias(),
                descriptor.name().orElse(""),
                descriptor.sourceId(),
                descriptor.markerCoordinate().toString(),
                descriptor.variant(),
                descriptor.quantization())
            .toLowerCase(Locale.ROOT);
    return java.util.Arrays.stream(query.split("\\s+"))
        .filter(token -> !token.isEmpty())
        .allMatch(searchable::contains);
  }

  private static void printDescriptor(ModelJarDescriptor descriptor, PrintStream output) {
    output.println("alias=" + descriptor.alias());
    output.println("name=" + descriptor.name().orElse(""));
    output.println("coordinate=" + descriptor.markerCoordinate());
    output.println("source=" + descriptor.sourceId());
    output.println("source_uri=" + descriptor.sourceUri().map(Object::toString).orElse(""));
    output.println("download_uri=" + descriptor.downloadUri().map(Object::toString).orElse(""));
    output.println("revision=" + descriptor.revision().orElse(""));
    output.println("format=" + descriptor.format());
    output.println("quantization=" + descriptor.quantization());
    output.println("size_bytes=" + descriptor.sizeBytes().map(String::valueOf).orElse(""));
    output.println("sha256=" + descriptor.sha256().orElse(""));
    output.println("license=" + descriptor.license().orElse(""));
    Path cachePath = ModelJarCache.artifactPath(descriptor);
    output.println("status=" + (Files.isRegularFile(cachePath) ? "cached" : "not_pulled"));
    output.println("cache_path=" + cachePath);
  }

  private static void requireArgumentCount(String[] args, int expected) {
    if (args.length != expected) {
      String requirement = expected == 1 ? "takes no additional arguments" : "requires one model";
      throw new IllegalArgumentException(args[0] + " " + requirement);
    }
  }

  @FunctionalInterface
  interface ArtifactInstaller {
    Path install(ModelJarDescriptor descriptor, Path destination);
  }

  private record CachedModel(ModelJarDescriptor descriptor, Path path) {}
}
