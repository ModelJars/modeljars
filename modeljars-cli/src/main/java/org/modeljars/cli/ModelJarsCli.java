package org.modeljars.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.modeljars.KvCachePrecision;
import org.modeljars.ModelDimensions;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarException;
import org.modeljars.ModelJarInstaller;
import org.modeljars.ModelJarRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.shell.jline3.PicocliJLineCompleter;

/** Standalone command line interface for discovering and prefetching qualified ModelJars. */
@Command(
    name = "modeljars",
    description = "Discover, inspect, and prefetch qualified ModelJars.",
    mixinStandardHelpOptions = true,
    versionProvider = ModelJarsCli.VersionProvider.class,
    sortOptions = false,
    subcommands = {
      ModelJarsCli.SearchCommand.class,
      ModelJarsCli.ListCommand.class,
      ModelJarsCli.ShowCommand.class,
      ModelJarsCli.PullCommand.class,
      ModelJarsCli.RemoveCommand.class,
      ModelJarsCli.CoordinatesCommand.class,
      ModelJarsCli.InfoCommand.class,
      ModelJarsCli.CacheDirectoryCommand.class,
      ModelJarsCli.VersionCommand.class,
      CommandLine.HelpCommand.class
    })
public final class ModelJarsCli implements Callable<Integer> {
  private static final String BANNER = loadBanner();
  private static final Map<String, List<String>> SEARCH_ALIASES =
      Map.ofEntries(
          Map.entry("fintech", List.of("finance")),
          Map.entry("financial", List.of("finance")),
          Map.entry("medical", List.of("healthcare", "clinical")),
          Map.entry("medicine", List.of("healthcare", "clinical")),
          Map.entry("programming", List.of("coding")),
          Map.entry("developer", List.of("coding")),
          Map.entry("java", List.of("pure-java")),
          Map.entry("local", List.of("offline", "on-device")),
          Map.entry("vector", List.of("embeddings", "text-embedding")),
          Map.entry("vectors", List.of("embeddings", "text-embedding")),
          Map.entry("embed", List.of("embeddings", "text-embedding")),
          Map.entry("similarity", List.of("semantic-search")),
          Map.entry("law", List.of("legal")),
          Map.entry("math", List.of("mathematics")),
          Map.entry("translate", List.of("translation")),
          Map.entry("speech", List.of("voice", "audio")),
          Map.entry("tts", List.of("voice", "audio", "text-to-speech")));
  private static final DateTimeFormatter MODIFIED_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

  private final ModelJarRegistry registry;
  private final ArtifactInstaller installer;
  private final SystemCapabilities.Probe systemProbe;

  @Spec private CommandSpec commandSpec;

  @Option(
      names = {"-o", "--output"},
      description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
      defaultValue = "table",
      scope = ScopeType.INHERIT)
  private CliOutput.Format outputFormat;

  @Option(
      names = "--color",
      description = "Color mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
      defaultValue = "auto",
      scope = ScopeType.INHERIT)
  private CliOutput.ColorMode colorMode;

  @Option(
      names = "--cache",
      paramLabel = "DIRECTORY",
      description = "Override the shared ModelJars cache directory.",
      scope = ScopeType.INHERIT)
  private Path cacheDirectory;

  @Option(
      names = "--width",
      paramLabel = "COLUMNS",
      description = "Set table and help width (minimum: 40).",
      scope = ScopeType.INHERIT)
  private Integer requestedWidth;

  private PrintStream output;
  private PrintStream error;
  private int detectedWidth;

  ModelJarsCli(ModelJarRegistry registry, ArtifactInstaller installer) {
    this(registry, installer, new SystemCapabilities()::detect);
  }

  ModelJarsCli(
      ModelJarRegistry registry,
      ArtifactInstaller installer,
      SystemCapabilities.Probe systemProbe) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.systemProbe = Objects.requireNonNull(systemProbe, "systemProbe");
  }

  /**
   * Runs the standalone CLI.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ArtifactInstaller installer =
        (descriptor, destination, progress) ->
            ModelJarInstaller.reportingTo(registry, progress).install(descriptor, destination);
    int status =
        new ModelJarsCli(registry, installer)
            .launch(args, System.in, System.out, System.err, true);
    if (status != 0) {
      System.exit(status);
    }
  }

  @Override
  public Integer call() {
    commandSpec.commandLine().usage(commandSpec.commandLine().getOut());
    return 0;
  }

  int run(String[] args, PrintStream standardOutput, PrintStream standardError) {
    Objects.requireNonNull(args, "args");
    prepare(standardOutput, standardError);
    return commandLine().execute(args);
  }

  int launch(
      String[] args,
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal) {
    return launch(
        args,
        standardInput,
        standardOutput,
        standardError,
        systemTerminal,
        defaultHistoryPath());
  }

  int launch(
      String[] args,
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal,
      Path history) {
    Objects.requireNonNull(args, "args");
    return args.length == 0
        ? runInteractive(standardInput, standardOutput, standardError, systemTerminal, history)
        : run(args, standardOutput, standardError);
  }

  private int runInteractive(
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal,
      Path history) {
    Objects.requireNonNull(standardInput, "standardInput");
    Objects.requireNonNull(history, "history");
    prepare(standardOutput, standardError);
    CommandLine completionCommand = commandLine();
    boolean persistentHistory;
    try {
      Files.createDirectories(history.getParent());
      persistentHistory = Files.isWritable(history.getParent());
    } catch (IOException ignored) {
      persistentHistory = false;
    }
    try {
      try (Terminal terminal =
          systemTerminal
              ? TerminalBuilder.builder()
                  .system(true)
                  .streams(standardInput, standardOutput)
                  .dumb(true)
                  .build()
              : new DumbTerminal(standardInput, standardOutput)) {
        DefaultParser parser = new DefaultParser();
        LineReaderBuilder readerBuilder =
            LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .completer(new PicocliJLineCompleter(completionCommand.getCommandSpec()))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        if (persistentHistory) {
          readerBuilder.variable(LineReader.HISTORY_FILE, history);
        }
        LineReader reader = readerBuilder.build();

        standardOutput.println(BANNER);
        standardOutput.println("ModelJars " + version());
        standardOutput.println("Type 'help' for commands; 'exit' or 'quit' to leave.");
        while (true) {
          try {
            String line = reader.readLine("modeljars> ").strip();
            if (line.isEmpty()) {
              continue;
            }
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
              return 0;
            }
            ParsedLine parsed = parser.parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE);
            run(parsed.words().toArray(String[]::new), standardOutput, standardError);
          } catch (UserInterruptException ignored) {
            standardOutput.println();
          } catch (EndOfFileException ignored) {
            standardOutput.println();
            return 0;
          }
        }
      }
    } catch (IOException exception) {
      standardError.println("Error: unable to open interactive terminal: " + exception.getMessage());
      return 2;
    }
  }

  private void prepare(PrintStream standardOutput, PrintStream standardError) {
    output = Objects.requireNonNull(standardOutput, "standardOutput");
    error = Objects.requireNonNull(standardError, "standardError");
    outputFormat = CliOutput.Format.TABLE;
    colorMode = CliOutput.ColorMode.AUTO;
    cacheDirectory = ModelJarCache.defaultDirectory();
    requestedWidth = null;
    detectedWidth = detectTerminalWidth();
  }

  private static Path defaultHistoryPath() {
    Path cache = ModelJarCache.defaultDirectory();
    Path modelJarsHome = cache.getParent();
    return (modelJarsHome == null ? cache : modelJarsHome).resolve("cli-history");
  }

  private static String loadBanner() {
    try (InputStream resource =
        ModelJarsCli.class.getResourceAsStream("/org/modeljars/cli/banner.txt")) {
      if (resource == null) {
        return "MODELJARS";
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
    } catch (IOException ignored) {
      return "MODELJARS";
    }
  }

  private CommandLine commandLine() {
    CommandLine commandLine = new CommandLine(this);
    commandLine.setOut(new PrintWriter(output, true));
    commandLine.setErr(new PrintWriter(error, true));
    commandLine.setCaseInsensitiveEnumValuesAllowed(true);
    commandLine.setUsageHelpWidth(detectedWidth);
    commandLine.setParameterExceptionHandler(
        (exception, arguments) -> {
          error.println("Error: " + exception.getMessage());
          error.println("Run 'modeljars help' or 'modeljars <command> --help' for usage.");
          return 2;
        });
    commandLine.setExecutionExceptionHandler(
        (exception, arguments, parseResult) -> {
          Throwable reported = exception;
          if (exception.getCause() instanceof ModelJarException) {
            reported = exception.getCause();
          }
          error.println("Error: " + Objects.requireNonNullElse(reported.getMessage(), reported.toString()));
          error.println("Run 'modeljars <command> --help' for usage.");
          return 2;
        });
    return commandLine;
  }

  private CliOutput out() {
    return new CliOutput(output, outputFormat, colorMode, width());
  }

  private CliOutput err() {
    return new CliOutput(error, CliOutput.Format.TABLE, colorMode, width());
  }

  private int width() {
    return requestedWidth == null ? detectedWidth : Math.max(40, requestedWidth);
  }

  private Path cacheDirectory() {
    return cacheDirectory.toAbsolutePath().normalize();
  }

  private List<ModelJarDescriptor> descriptors() {
    return registry.descriptors();
  }

  private ModelJarDescriptor resolve(String selector) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException("Model selector must not be blank");
    }
    String requested = selector.trim();
    List<ModelJarDescriptor> exact =
        descriptors().stream()
            .filter(
                descriptor ->
                    descriptor.alias().equals(requested)
                        || descriptor.markerCoordinate().toString().equals(requested))
            .toList();
    if (exact.size() == 1) {
      return exact.getFirst();
    }

    List<ModelJarDescriptor> sourceMatches =
        descriptors().stream().filter(descriptor -> descriptor.sourceId().equals(requested)).toList();
    if (sourceMatches.size() == 1) {
      return sourceMatches.getFirst();
    }
    if (sourceMatches.size() > 1) {
      throw new IllegalArgumentException(
          "Source matches multiple variants; use an alias or exact marker coordinate: " + requested);
    }
    List<ModelJarDescriptor> queryMatches =
        descriptors().stream()
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

  private ModelJarDescriptor resolveExact(String selector) {
    ModelJarDescriptor descriptor = resolve(selector);
    String requested = selector.trim();
    if (!descriptor.alias().equals(requested)
        && !descriptor.markerCoordinate().toString().equals(requested)
        && !descriptor.sourceId().equals(requested)) {
      throw new IllegalArgumentException(
          "This command requires an exact alias, source, or coordinate: " + selector);
    }
    return descriptor;
  }

  private boolean cached(ModelJarDescriptor descriptor) {
    return Files.isRegularFile(ModelJarCache.artifactPath(descriptor, cacheDirectory()));
  }

  private static boolean matchesQuery(ModelJarDescriptor descriptor, String query) {
    if (query.isEmpty()) {
      return true;
    }
    String searchable =
        Stream.of(
                descriptor.alias(),
                descriptor.name().orElse(""),
                descriptor.description().orElse(""),
                descriptor.sourceId(),
                descriptor.markerCoordinate().toString(),
                descriptor.variant(),
                descriptor.format(),
                descriptor.architecture(),
                descriptor.quantization(),
                String.join(" ", descriptor.capabilities()),
                String.join(" ", descriptor.features()),
                String.join(" ", descriptor.domains()))
            .reduce("", (left, right) -> left + ' ' + right)
            .toLowerCase(Locale.ROOT);
    return Arrays.stream(query.split("\\s+"))
        .filter(token -> !token.isEmpty())
        .allMatch(
            token ->
                Stream.concat(Stream.of(token), SEARCH_ALIASES.getOrDefault(token, List.of()).stream())
                    .anyMatch(searchable::contains));
  }

  private static Map<String, Object> descriptorMap(
      ModelJarDescriptor descriptor, Path cachePath) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("alias", descriptor.alias());
    values.put("name", descriptor.name().orElse(null));
    values.put("coordinate", descriptor.markerCoordinate().toString());
    values.put("source", descriptor.sourceId());
    values.put("sourceUri", descriptor.sourceUri().map(Object::toString).orElse(null));
    values.put("downloadUri", descriptor.downloadUri().map(Object::toString).orElse(null));
    values.put("revision", descriptor.revision().orElse(null));
    values.put("format", descriptor.format());
    values.put("architecture", descriptor.architecture());
    values.put("quantization", descriptor.quantization());
    values.put("sizeBytes", descriptor.sizeBytes().orElse(null));
    values.put("sha256", descriptor.sha256().orElse(null));
    values.put("license", descriptor.license().orElse(null));
    values.put("capabilities", sorted(descriptor.capabilities()));
    values.put("features", sorted(descriptor.features()));
    values.put("domains", sorted(descriptor.domains()));
    values.put("backends", descriptor.backendSupport());
    values.put("dimensions", dimensionsMap(descriptor.dimensions()));
    values.put("status", Files.isRegularFile(cachePath) ? "cached" : "not_pulled");
    values.put("cachePath", cachePath.toString());
    return values;
  }

  private static Map<String, Object> dimensionsMap(ModelDimensions dimensions) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("parameterCount", dimensions.parameterCount().orElse(null));
    values.put("contextLength", dimensions.contextLength().orElse(null));
    values.put("embeddingLength", dimensions.embeddingLength().orElse(null));
    values.put("blockCount", dimensions.blockCount().orElse(null));
    values.put("attentionHeadCount", dimensions.attentionHeadCount().orElse(null));
    values.put("keyValueHeadCount", dimensions.keyValueHeadCount().orElse(null));
    values.put("feedForwardLength", dimensions.feedForwardLength().orElse(null));
    values.put("expertCount", dimensions.expertCount().orElse(null));
    values.put("expertUsedCount", dimensions.expertUsedCount().orElse(null));
    return values;
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  private static String capabilities(ModelJarDescriptor descriptor) {
    return descriptor.capabilities().stream()
        .sorted()
        .map(value -> value.equals("text-generation") ? "generation" : value)
        .reduce((left, right) -> left + ", " + right)
        .orElse("unknown");
  }

  private static String backends(ModelJarDescriptor descriptor) {
    return descriptor.backendSupport().entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .reduce((left, right) -> left + ", " + right)
        .orElse("none");
  }

  private static String humanParameters(long parameters) {
    if (parameters >= 1_000_000_000L) {
      return String.format(Locale.ROOT, "%.1fB", parameters / 1_000_000_000.0);
    }
    if (parameters >= 1_000_000L) {
      return String.format(Locale.ROOT, "%.0fM", parameters / 1_000_000.0);
    }
    return Long.toString(parameters);
  }

  private static String version() {
    String implementationVersion = ModelJarsCli.class.getPackage().getImplementationVersion();
    return implementationVersion == null ? "development" : implementationVersion;
  }

  private static int detectTerminalWidth() {
    Optional<Integer> configured =
        Optional.ofNullable(System.getenv("COLUMNS"))
            .flatMap(ModelJarsCli::positiveInteger);
    if (configured.isPresent()) {
      return Math.max(40, configured.orElseThrow());
    }
    if (System.console() != null) {
      return new SystemCapabilities.CommandRunner(Duration.ofSeconds(1))
          .run("tput", "cols")
          .flatMap(ModelJarsCli::positiveInteger)
          .map(value -> Math.max(40, value))
          .orElse(120);
    }
    return 120;
  }

  private static Optional<Integer> positiveInteger(String value) {
    try {
      int parsed = Integer.parseInt(value.strip());
      return parsed > 0 ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  @Command(
      name = "search",
      aliases = "available",
      description = "Search and filter the qualified model catalog.",
      mixinStandardHelpOptions = true)
  static final class SearchCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(arity = "0..*", paramLabel = "QUERY", description = "Search terms.")
    private List<String> query = List.of();

    @Option(names = "--capability", paramLabel = "NAME", description = "Filter by capability.")
    private String capability;

    @Option(names = "--architecture", paramLabel = "NAME", description = "Filter by architecture.")
    private String architecture;

    @Option(names = "--quantization", paramLabel = "NAME", description = "Filter by quantization.")
    private String quantization;

    @Option(names = "--format", paramLabel = "NAME", description = "Filter by model format.")
    private String modelFormat;

    @Option(names = "--backend", paramLabel = "NAME", description = "Filter by supported backend.")
    private String backend;

    @Option(names = "--installed", description = "Show only locally cached models.")
    private boolean installed;

    @Option(
        names = {"-v", "--details"},
        description = "Show capabilities and supported backends for each model.")
    private boolean details;

    @Option(
        names = "--fits-memory",
        description = "Show models whose weights fit in currently free memory.")
    private boolean fitsMemory;

    @Option(names = "--limit", defaultValue = "0", description = "Limit results; zero means all.")
    private int limit;

    @Option(
        names = "--sort",
        defaultValue = "alias",
        description = "Sort by ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private Sort sort;

    enum Sort {
      ALIAS,
      SIZE,
      PARAMETERS;

      @Override
      public String toString() {
        return name().toLowerCase(Locale.ROOT);
      }
    }

    @Override
    public Integer call() {
      if (limit < 0) {
        throw new IllegalArgumentException("--limit must be zero or greater");
      }
      String terms = String.join(" ", query).toLowerCase(Locale.ROOT);
      long availableMemory =
          fitsMemory ? parent.systemProbe.detect().freeMemoryBytes() : Long.MAX_VALUE;
      if (fitsMemory && availableMemory < 0) {
        throw new IllegalStateException(
            "Free system memory is unavailable; --fits-memory cannot be evaluated");
      }
      Comparator<ModelJarDescriptor> comparator =
          switch (sort) {
            case ALIAS -> Comparator.comparing(ModelJarDescriptor::alias);
            case SIZE ->
                Comparator.comparingLong(
                        (ModelJarDescriptor descriptor) ->
                            descriptor.sizeBytes().orElse(Long.MAX_VALUE))
                    .thenComparing(ModelJarDescriptor::alias);
            case PARAMETERS ->
                Comparator.comparingLong(
                        (ModelJarDescriptor descriptor) ->
                            descriptor.dimensions().parameterCount().orElse(Long.MAX_VALUE))
                    .thenComparing(ModelJarDescriptor::alias);
          };
      List<ModelJarDescriptor> allMatches =
          parent.descriptors().stream()
              .filter(descriptor -> matchesQuery(descriptor, terms))
              .filter(descriptor -> matches(capability, descriptor.capabilities()))
              .filter(descriptor -> matches(architecture, descriptor.architecture()))
              .filter(descriptor -> matches(quantization, descriptor.quantization()))
              .filter(descriptor -> matches(modelFormat, descriptor.format()))
              .filter(
                  descriptor ->
                      backend == null || descriptor.supportsBackend(backend.toLowerCase(Locale.ROOT)))
              .filter(descriptor -> !installed || parent.cached(descriptor))
              .filter(
                  descriptor ->
                      !fitsMemory
                          || descriptor.sizeBytes().map(size -> size <= availableMemory).orElse(false))
              .sorted(comparator)
              .toList();
      List<ModelJarDescriptor> matches =
          limit == 0 ? allMatches : allMatches.stream().limit(limit).toList();
      render(matches, allMatches.size());
      return 0;
    }

    private void render(List<ModelJarDescriptor> matches, int totalMatches) {
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        out.json(
            matches.stream()
                .map(
                    descriptor ->
                        descriptorMap(
                            descriptor,
                            ModelJarCache.artifactPath(descriptor, parent.cacheDirectory())))
                .toList());
        return;
      }
      if (out.format() == CliOutput.Format.PLAIN) {
        out.line("ALIAS\tCAPABILITIES\tARCHITECTURE\tQUANTIZATION\tSIZE_BYTES\tSTATUS\tCOORDINATE");
        matches.forEach(
            descriptor ->
                out.line(
                    String.join(
                        "\t",
                        descriptor.alias(),
                        capabilities(descriptor),
                        descriptor.architecture(),
                        descriptor.quantization(),
                        descriptor.sizeBytes().map(String::valueOf).orElse(""),
                        parent.cached(descriptor) ? "cached" : "available",
                        descriptor.markerCoordinate().toString())));
        return;
      }
      if (matches.isEmpty()) {
        out.line("No qualified models matched the requested filters.");
        out.hint("Try 'modeljars search' to list the complete qualified catalog.");
        return;
      }

      int modelWidth =
          Math.max(
              "MODEL".length(),
              matches.stream().mapToInt(descriptor -> descriptor.alias().length()).max().orElse(0));
      int architectureWidth =
          Math.max(
              "ARCH".length(),
              matches.stream()
                  .mapToInt(descriptor -> descriptor.architecture().length())
                  .max()
                  .orElse(0));
      int quantizationWidth =
          Math.max(
              "QUANT".length(),
              matches.stream()
                  .mapToInt(descriptor -> descriptor.quantization().length())
                  .max()
                  .orElse(0));
      int sizeWidth =
          Math.max(
              "SIZE".length(),
              matches.stream()
                  .mapToInt(
                      descriptor ->
                          descriptor
                              .sizeBytes()
                              .map(CliOutput::humanBytes)
                              .orElse("unknown")
                              .length())
                  .max()
                  .orElse(0));
      List<CliOutput.Column> columns = new ArrayList<>();
      columns.add(CliOutput.Column.left("MODEL", modelWidth, modelWidth));
      columns.add(
          CliOutput.Column.left("ARCH", architectureWidth, architectureWidth));
      columns.add(CliOutput.Column.left("QUANT", quantizationWidth, quantizationWidth));
      columns.add(CliOutput.Column.right("SIZE", sizeWidth, sizeWidth));
      columns.add(CliOutput.Column.left("STATUS", 9, 9));

      List<List<CliOutput.Cell>> rows =
          matches.stream()
              .map(
                  descriptor -> {
                    List<CliOutput.Cell> row = new ArrayList<>();
                    row.add(CliOutput.Cell.text(descriptor.alias()));
                    row.add(CliOutput.Cell.text(descriptor.architecture()));
                    row.add(CliOutput.Cell.text(descriptor.quantization()));
                    row.add(
                        CliOutput.Cell.text(
                            descriptor.sizeBytes().map(CliOutput::humanBytes).orElse("unknown")));
                    boolean cached = parent.cached(descriptor);
                    row.add(
                        new CliOutput.Cell(
                            cached ? "cached" : "available",
                            cached ? CliOutput.Tone.SUCCESS : CliOutput.Tone.MUTED));
                    return List.copyOf(row);
                  })
              .toList();
      if (details) {
        List<List<CliOutput.Detail>> modelDetails =
            matches.stream()
                .map(
                    descriptor ->
                        List.of(
                            CliOutput.Detail.text("CAPABILITIES", capabilities(descriptor)),
                            CliOutput.Detail.text("BACKENDS", backends(descriptor))))
                .toList();
        out.table(columns, rows, modelDetails);
      } else {
        out.table(columns, rows);
      }
      out.hint(
          matches.size() == totalMatches
              ? totalMatches + " qualified model" + (totalMatches == 1 ? "" : "s")
              : "Showing " + matches.size() + " of " + totalMatches + " matching models");
    }

    private static boolean matches(String requested, Set<String> values) {
      if (requested == null) {
        return true;
      }
      String normalized = requested.strip().toLowerCase(Locale.ROOT);
      return values.stream()
          .map(value -> value.toLowerCase(Locale.ROOT))
          .anyMatch(value -> value.equals(normalized) || value.contains(normalized));
    }

    private static boolean matches(String requested, String value) {
      return requested == null || value.equalsIgnoreCase(requested.strip());
    }
  }

  @Command(
      name = "list",
      aliases = "ls",
      description = "List models present in the local cache.",
      mixinStandardHelpOptions = true)
  static final class ListCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Option(names = "--coordinates", description = "Include marker coordinates in the table.")
    private boolean coordinates;

    @Option(names = {"-v", "--details"}, description = "Show capabilities for each model.")
    private boolean details;

    @Override
    public Integer call() {
      List<CachedModel> models =
          parent.descriptors().stream()
              .map(
                  descriptor ->
                      new CachedModel(
                          descriptor,
                          ModelJarCache.artifactPath(descriptor, parent.cacheDirectory())))
              .filter(model -> Files.isRegularFile(model.path()))
              .sorted(Comparator.comparing(model -> model.descriptor().alias()))
              .toList();
      render(models);
      return 0;
    }

    private void render(List<CachedModel> models) {
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        out.json(
            models.stream()
                .map(
                    model -> {
                      Map<String, Object> value =
                          descriptorMap(model.descriptor(), model.path());
                      value.put("modified", modified(model.path()).toString());
                      return value;
                    })
                .toList());
        return;
      }
      if (out.format() == CliOutput.Format.PLAIN) {
        out.line("ALIAS\tSIZE_BYTES\tMODIFIED\tPATH\tCOORDINATE");
        models.forEach(
            model ->
                out.line(
                    String.join(
                        "\t",
                        model.descriptor().alias(),
                        model.descriptor().sizeBytes().map(String::valueOf).orElse(""),
                        modified(model.path()).toString(),
                        model.path().toString(),
                        model.descriptor().markerCoordinate().toString())));
        return;
      }
      if (models.isEmpty()) {
        out.line("No qualified models are present in the local cache.");
        out.hint("Run 'modeljars search', then 'modeljars pull <model>'.");
        return;
      }

      int modelWidth =
          models.stream()
              .map(model -> model.descriptor().alias().length())
              .max(Integer::compareTo)
              .orElse("MODEL".length());
      int sizeWidth =
          models.stream()
              .map(
                  model ->
                      model
                          .descriptor()
                          .sizeBytes()
                          .map(CliOutput::humanBytes)
                          .orElse("unknown")
                          .length())
              .max(Integer::compareTo)
              .orElse("SIZE".length());
      List<CliOutput.Column> columns = new ArrayList<>();
      columns.add(CliOutput.Column.left("MODEL", modelWidth, modelWidth));
      columns.add(CliOutput.Column.right("SIZE", sizeWidth, sizeWidth));
      columns.add(CliOutput.Column.left("MODIFIED", 16, 16));
      List<List<CliOutput.Cell>> rows =
          models.stream()
              .map(
                  model -> {
                    List<CliOutput.Cell> row = new ArrayList<>();
                    row.add(CliOutput.Cell.text(model.descriptor().alias()));
                    row.add(
                        CliOutput.Cell.text(
                            model
                                .descriptor()
                                .sizeBytes()
                                .map(CliOutput::humanBytes)
                                .orElse("unknown")));
                    row.add(CliOutput.Cell.text(MODIFIED_TIME.format(modified(model.path()))));
                    return List.copyOf(row);
                  })
              .toList();
      if (details || coordinates) {
        List<List<CliOutput.Detail>> modelDetails =
            models.stream()
                .map(
                    model -> {
                      List<CliOutput.Detail> fields = new ArrayList<>();
                      if (details) {
                        fields.add(
                            CliOutput.Detail.text(
                                "CAPABILITIES", capabilities(model.descriptor())));
                        fields.add(
                            CliOutput.Detail.text(
                                "BACKENDS", backends(model.descriptor())));
                      }
                      if (coordinates) {
                        fields.add(
                            CliOutput.Detail.text(
                                "COORDINATE", model.descriptor().markerCoordinate().toString()));
                      }
                      return List.copyOf(fields);
                    })
                .toList();
        out.table(columns, rows, modelDetails);
      } else {
        out.table(columns, rows);
      }
      long totalBytes =
          models.stream().mapToLong(model -> model.descriptor().sizeBytes().orElse(0L)).sum();
      out.hint(models.size() + " cached models · " + CliOutput.humanBytes(totalBytes));
    }
  }

  @Command(
      name = "show",
      aliases = "inspect",
      description = "Show model metadata, provenance, requirements, and local status.",
      mixinStandardHelpOptions = true)
  static final class ShowCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(paramLabel = "MODEL", description = "Alias, source ID, or exact coordinate.")
    private String selector;

    @Option(names = "--coordinates", description = "Also print dependency declarations.")
    private boolean coordinates;

    @Option(
        names = {"-v", "--details"},
        description = "Show model capabilities and supported backends.")
    private boolean details;

    @Override
    public Integer call() {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      Path cachePath = ModelJarCache.artifactPath(descriptor, parent.cacheDirectory());
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        Map<String, Object> value = descriptorMap(descriptor, cachePath);
        if (coordinates) {
          value.put("dependencyDeclarations", dependencyDeclarations(descriptor, true));
        }
        out.json(value);
      } else if (out.format() == CliOutput.Format.PLAIN) {
        descriptorMap(descriptor, cachePath)
            .forEach((key, value) -> out.line(key + "=" + Objects.toString(value, "")));
      } else {
        renderHuman(descriptor, cachePath, out, details);
        if (coordinates) {
          printDeclarations(descriptor, out, true, List.of());
        } else {
          out.hint("Run 'modeljars coordinates " + descriptor.alias() + "' for build declarations.");
        }
      }
      return 0;
    }

    private static void renderHuman(
        ModelJarDescriptor descriptor, Path cachePath, CliOutput out, boolean details) {
      out.line(descriptor.name().orElse(descriptor.alias()));
      descriptor.description().ifPresent(out::hint);

      Map<String, Object> identity = new LinkedHashMap<>();
      identity.put("Alias", descriptor.alias());
      identity.put("Coordinate", descriptor.markerCoordinate());
      identity.put("Status", Files.isRegularFile(cachePath) ? "cached" : "not pulled");
      identity.put("Architecture", descriptor.architecture());
      identity.put("Format", descriptor.format().toUpperCase(Locale.ROOT));
      identity.put("Quantization", descriptor.quantization());
      descriptor.dimensions().parameterCount().ifPresent(value -> identity.put("Parameters", humanParameters(value)));
      descriptor.dimensions().contextLength().ifPresent(value -> identity.put("Context", value + " tokens"));
      descriptor.dimensions().embeddingLength().ifPresent(value -> identity.put("Embedding", value + " dimensions"));
      descriptor.sizeBytes().ifPresent(value -> identity.put("Download", CliOutput.humanBytes(value)));
      if (details) {
        identity.put("Capabilities", capabilities(descriptor));
        identity.put("Backends", backends(descriptor));
      }
      out.section("Model");
      out.properties(identity);

      Map<String, Object> provenance = new LinkedHashMap<>();
      provenance.put("Source", descriptor.sourceId());
      descriptor.sourceUri().ifPresent(value -> provenance.put("Source URL", value));
      descriptor.downloadUri().ifPresent(value -> provenance.put("Download URL", value));
      descriptor.revision().ifPresent(value -> provenance.put("Revision", value));
      descriptor.sha256().ifPresent(value -> provenance.put("SHA-256", value));
      descriptor.license().ifPresent(value -> provenance.put("License", value));
      out.section("Provenance");
      out.properties(provenance);

      Map<String, Object> local = new LinkedHashMap<>();
      local.put("Cache path", cachePath);
      if (Files.isRegularFile(cachePath)) {
        local.put("Modified", MODIFIED_TIME.format(modified(cachePath)));
      }
      int context = Math.min(4096, descriptor.dimensions().contextLength().orElse(4096));
      descriptor
          .estimateMemory(context, KvCachePrecision.FLOAT16)
          .ifPresent(
              estimate ->
                  local.put(
                      "Memory floor",
                      CliOutput.humanBytes(estimate.minimumBytes())
                          + " at "
                          + context
                          + " tokens (runtime overhead excluded)"));
      out.section("Local");
      out.properties(local);
    }
  }

  @Command(
      name = "pull",
      description = "Download and verify a model in the shared runtime cache.",
      mixinStandardHelpOptions = true)
  static final class PullCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(paramLabel = "MODEL", description = "Alias, source ID, or exact coordinate.")
    private String selector;

    @Option(names = {"-q", "--quiet"}, description = "Print only the installed model path.")
    private boolean quiet;

    @Override
    public Integer call() {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      Path destination = ModelJarCache.artifactPath(descriptor, parent.cacheDirectory());
      Consumer<String> progress = quiet ? ignored -> {} : parent.err()::hint;
      Path artifact = parent.installer.install(descriptor, destination, progress).toAbsolutePath().normalize();
      CliOutput out = parent.out();
      if (quiet) {
        out.line(artifact.toString());
      } else if (out.format() == CliOutput.Format.JSON) {
        out.json(
            Map.of(
                "alias", descriptor.alias(),
                "coordinate", descriptor.markerCoordinate().toString(),
                "sha256", descriptor.sha256().orElse(""),
                "path", artifact.toString()));
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("coordinate=" + descriptor.markerCoordinate());
        out.line("sha256=" + descriptor.sha256().orElse(""));
        out.line("path=" + artifact);
      } else {
        out.success("Model ready: " + descriptor.alias());
        out.properties(
            Map.of(
                "Coordinate", descriptor.markerCoordinate(),
                "Path", artifact,
                "Size", descriptor.sizeBytes().map(CliOutput::humanBytes).orElse("unknown")));
      }
      return 0;
    }
  }

  @Command(
      name = "remove",
      aliases = "rm",
      description = "Remove a model from the local cache.",
      mixinStandardHelpOptions = true)
  static final class RemoveCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(paramLabel = "MODEL", description = "Exact alias, source ID, or coordinate.")
    private String selector;

    @Option(names = {"-f", "--force"}, description = "Succeed when the model is not cached.")
    private boolean force;

    @Override
    public Integer call() throws IOException {
      ModelJarDescriptor descriptor = parent.resolveExact(selector);
      Path root = parent.cacheDirectory();
      Path artifact = ModelJarCache.artifactPath(descriptor, root);
      if (!artifact.startsWith(root)) {
        throw new IllegalStateException("Resolved cache path is outside the configured cache");
      }
      if (Files.isSymbolicLink(artifact)) {
        throw new IllegalStateException("Refusing to remove a symbolic link from the model cache");
      }
      if (!Files.isRegularFile(artifact)) {
        if (force) {
          return 0;
        }
        throw new IllegalArgumentException("Model is not present in the local cache: " + descriptor.alias());
      }
      Files.delete(artifact);
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        out.json(Map.of("alias", descriptor.alias(), "removed", true, "path", artifact.toString()));
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("removed=" + descriptor.alias());
        out.line("path=" + artifact);
      } else {
        out.success("Removed " + descriptor.alias());
        out.hint(artifact.toString());
      }
      return 0;
    }
  }

  @Command(
      name = "coordinates",
      aliases = {"coords", "snippet", "dependency", "deps"},
      description = "Print copy-ready Maven, Gradle, or other dependency snippets for a model.",
      footer = {
        "Examples:",
        "  modeljars snippet qwen3_0_6b_q4_0 --tool maven",
        "  modeljars snippet qwen3_0_6b_q4_0 --tool gradle-kotlin"
      },
      mixinStandardHelpOptions = true)
  static final class CoordinatesCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(paramLabel = "MODEL", description = "Alias, source ID, or exact coordinate.")
    private String selector;

    @Option(
        names = {"-t", "--tool"},
        paramLabel = "TOOL",
        description = "Select a build tool; repeat for more than one.")
    private List<DependencyCoordinates.Tool> tools = new ArrayList<>();

    @Option(names = "--marker-only", description = "Omit the ModelJars runtime dependency.")
    private boolean markerOnly;

    @Override
    public Integer call() {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      CliOutput out = parent.out();
      List<DependencyCoordinates.Tool> selected =
          tools.isEmpty()
              ? List.of(
                  DependencyCoordinates.Tool.MAVEN,
                  DependencyCoordinates.Tool.GRADLE,
                  DependencyCoordinates.Tool.GRADLE_KOTLIN)
              : List.copyOf(tools);
      if (out.format() == CliOutput.Format.JSON) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("alias", descriptor.alias());
        value.put("cached", parent.cached(descriptor));
        value.put("coordinate", descriptor.markerCoordinate().toString());
        Map<String, String> declarations = dependencyDeclarations(descriptor, !markerOnly);
        declarations.keySet().retainAll(selected.stream().map(Object::toString).toList());
        value.put("declarations", declarations);
        out.json(value);
      } else {
        printDeclarations(descriptor, out, !markerOnly, selected);
      }
      return 0;
    }
  }

  @Command(
      name = "info",
      aliases = {"system", "env"},
      description = "Show local hardware, runtime, backend, and cache capabilities.",
      mixinStandardHelpOptions = true)
  static final class InfoCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Override
    public Integer call() {
      SystemCapabilities.Snapshot snapshot = parent.systemProbe.detect();
      CacheSummary cache = cacheSummary(parent.descriptors(), parent.cacheDirectory());
      Map<String, Long> capabilityCounts =
          parent.descriptors().stream()
              .flatMap(descriptor -> descriptor.capabilities().stream())
              .sorted()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      value -> value, LinkedHashMap::new, java.util.stream.Collectors.counting()));
      if (parent.out().format() == CliOutput.Format.JSON) {
        parent.out().json(toMap(snapshot, cache, capabilityCounts, parent.descriptors().size()));
      } else if (parent.out().format() == CliOutput.Format.PLAIN) {
        flatten(toMap(snapshot, cache, capabilityCounts, parent.descriptors().size()), "")
            .forEach((key, value) -> parent.out().line(key + '=' + value));
      } else {
        renderHuman(snapshot, cache, capabilityCounts, parent.descriptors().size(), parent.out());
      }
      return 0;
    }

    private static void renderHuman(
        SystemCapabilities.Snapshot snapshot,
        CacheSummary cache,
        Map<String, Long> capabilityCounts,
        int catalogSize,
        CliOutput out) {
      out.line(BANNER);
      out.line("ModelJars local capabilities");
      Map<String, Object> host = new LinkedHashMap<>();
      host.put("Operating system", snapshot.operatingSystem());
      host.put("Architecture", snapshot.architecture());
      host.put("Processor", snapshot.processor());
      host.put("CPU cores", snapshot.physicalCores() + " physical / " + snapshot.logicalCores() + " logical");
      host.put("SIMD", snapshot.simd().isEmpty() ? "not discovered" : String.join(", ", snapshot.simd()));
      host.put(
          "Memory",
          CliOutput.humanBytes(snapshot.totalMemoryBytes())
              + " total / "
              + CliOutput.humanBytes(snapshot.freeMemoryBytes())
              + " free");
      host.put("CLI runtime", snapshot.javaRuntime());
      out.section("Host");
      out.properties(host);

      List<List<CliOutput.Cell>> devices =
          snapshot.graphicsDevices().stream()
              .map(
                  device ->
                      List.of(
                          CliOutput.Cell.text(device.name()),
                          CliOutput.Cell.text(
                              device
                                  .dedicatedMemoryBytes()
                                  .map(CliOutput::humanBytes)
                                  .orElse("shared / unknown"))))
              .toList();
      out.section("Graphics");
      if (devices.isEmpty()) {
        out.hint("  No graphics device was discovered.");
      } else {
        int deviceWidth =
            Math.max(
                "DEVICE".length(),
                snapshot.graphicsDevices().stream()
                    .mapToInt(device -> device.name().length())
                    .max()
                    .orElse(0));
        out.table(
            List.of(
                CliOutput.Column.left("DEVICE", deviceWidth, deviceWidth),
                CliOutput.Column.right("MEMORY", 16, 20)),
            devices);
      }

      out.section("Inference backends");
      int backendWidth =
          Math.max(
              "BACKEND".length(),
              snapshot.backends().stream()
                  .mapToInt(backend -> backend.name().length())
                  .max()
                  .orElse(0));
      out.table(
          List.of(
              CliOutput.Column.left("BACKEND", backendWidth, backendWidth),
              CliOutput.Column.left("STATUS", 11, 11)),
          snapshot.backends().stream()
              .map(
                  backend ->
                      List.of(
                          CliOutput.Cell.text(backend.name()),
                          new CliOutput.Cell(
                              backend.status().name().toLowerCase(Locale.ROOT),
                              switch (backend.status()) {
                                case READY -> CliOutput.Tone.SUCCESS;
                                case ELIGIBLE -> CliOutput.Tone.WARNING;
                                case DETECTED -> CliOutput.Tone.INFO;
                                case UNAVAILABLE -> CliOutput.Tone.MUTED;
                              })))
              .toList(),
          snapshot.backends().stream()
              .map(
                  backend ->
                      List.of(
                          CliOutput.Detail.text("WORKLOADS", backend.workloads()),
                          new CliOutput.Detail(
                              "DETAIL",
                              new CliOutput.Cell(backend.detail(), CliOutput.Tone.MUTED))))
              .toList());

      Map<String, Object> catalog = new LinkedHashMap<>();
      catalog.put("Qualified models", catalogSize);
      capabilityCounts.forEach((name, count) -> catalog.put(name, count));
      catalog.put("Cached models", cache.count());
      catalog.put("Cached weights", CliOutput.humanBytes(cache.bytes()));
      catalog.put("Cache directory", cache.directory());
      out.section("Catalog and cache");
      out.properties(catalog);
      out.hint(
          "Detected GPU hardware is inventory only; backend status is the authoritative usability signal.");
    }

    private static Map<String, Object> toMap(
        SystemCapabilities.Snapshot snapshot,
        CacheSummary cache,
        Map<String, Long> capabilityCounts,
        int catalogSize) {
      Map<String, Object> host = new LinkedHashMap<>();
      host.put("operatingSystem", snapshot.operatingSystem());
      host.put("architecture", snapshot.architecture());
      host.put("processor", snapshot.processor());
      host.put("physicalCores", snapshot.physicalCores());
      host.put("logicalCores", snapshot.logicalCores());
      host.put("totalMemoryBytes", snapshot.totalMemoryBytes());
      host.put("freeMemoryBytes", snapshot.freeMemoryBytes());
      host.put("simd", snapshot.simd());
      host.put("nativeExecutable", snapshot.nativeExecutable());
      host.put("javaRuntime", snapshot.javaRuntime());

      List<Map<String, Object>> graphics =
          snapshot.graphicsDevices().stream()
              .map(
                  device -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", device.name());
                    value.put("dedicatedMemoryBytes", device.dedicatedMemoryBytes().orElse(null));
                    return value;
                  })
              .toList();
      List<Map<String, Object>> backends =
          snapshot.backends().stream()
              .map(
                  backend -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", backend.name());
                    value.put("status", backend.status().name().toLowerCase(Locale.ROOT));
                    value.put("workloads", backend.workloads());
                    value.put("detail", backend.detail());
                    return value;
                  })
              .toList();
      Map<String, Object> catalog = new LinkedHashMap<>();
      catalog.put("qualifiedModels", catalogSize);
      catalog.put("capabilities", capabilityCounts);
      catalog.put("cachedModels", cache.count());
      catalog.put("cachedBytes", cache.bytes());
      catalog.put("cacheDirectory", cache.directory().toString());

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("host", host);
      result.put("graphics", graphics);
      result.put("backends", backends);
      result.put("catalog", catalog);
      return result;
    }

    private static Map<String, String> flatten(Map<String, Object> values, String prefix) {
      Map<String, String> flattened = new LinkedHashMap<>();
      values.forEach(
          (key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + '.' + key;
            if (value instanceof Map<?, ?> nested) {
              Map<String, Object> converted = new LinkedHashMap<>();
              nested.forEach((nestedKey, nestedValue) -> converted.put(nestedKey.toString(), nestedValue));
              flattened.putAll(flatten(converted, path));
            } else {
              flattened.put(path, Objects.toString(value, ""));
            }
          });
      return flattened;
    }
  }

  @Command(
      name = "cache-dir",
      description = "Print the configured runtime cache root.",
      mixinStandardHelpOptions = true)
  static final class CacheDirectoryCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Override
    public Integer call() {
      parent.out().line(parent.cacheDirectory().toString());
      return 0;
    }
  }

  @Command(name = "version", description = "Print the CLI version.")
  static final class VersionCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Override
    public Integer call() {
      parent.out().line(version());
      return 0;
    }
  }

  static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {version()};
    }
  }

  @FunctionalInterface
  interface ArtifactInstaller {
    Path install(
        ModelJarDescriptor descriptor, Path destination, Consumer<String> progressReporter);
  }

  private record CachedModel(ModelJarDescriptor descriptor, Path path) {}

  private record CacheSummary(int count, long bytes, Path directory) {}

  private static Instant modified(Path path) {
    try {
      FileTime modified = Files.getLastModifiedTime(path);
      return modified.toInstant();
    } catch (IOException ignored) {
      return Instant.EPOCH;
    }
  }

  private static CacheSummary cacheSummary(
      List<ModelJarDescriptor> descriptors, Path cacheDirectory) {
    int count = 0;
    long bytes = 0;
    for (ModelJarDescriptor descriptor : descriptors) {
      Path path = ModelJarCache.artifactPath(descriptor, cacheDirectory);
      if (Files.isRegularFile(path)) {
        count++;
        try {
          bytes = Math.addExact(bytes, Files.size(path));
        } catch (IOException | ArithmeticException ignored) {
          bytes = Math.addExact(bytes, descriptor.sizeBytes().orElse(0L));
        }
      }
    }
    return new CacheSummary(count, bytes, cacheDirectory);
  }

  private static Map<String, String> dependencyDeclarations(
      ModelJarDescriptor descriptor, boolean includeRuntime) {
    Map<String, String> declarations = new LinkedHashMap<>();
    for (DependencyCoordinates.Tool tool : DependencyCoordinates.Tool.values()) {
      declarations.put(tool.toString(), declaration(descriptor, tool, includeRuntime));
    }
    return declarations;
  }

  private static String declaration(
      ModelJarDescriptor descriptor,
      DependencyCoordinates.Tool tool,
      boolean includeRuntime) {
    String marker = DependencyCoordinates.render(descriptor.markerCoordinate(), tool);
    if (!includeRuntime || version().equals("development")) {
      return marker;
    }
    ModelJarCoordinate runtime =
        new ModelJarCoordinate(
            "org.modeljars", "modeljars", version(), Optional.empty(), "jar");
    return DependencyCoordinates.render(runtime, tool) + System.lineSeparator() + marker;
  }

  private static void printDeclarations(
      ModelJarDescriptor descriptor,
      CliOutput out,
      boolean includeRuntime,
      List<DependencyCoordinates.Tool> requested) {
    List<DependencyCoordinates.Tool> tools =
        requested.isEmpty() ? List.of(DependencyCoordinates.Tool.values()) : requested;
    for (DependencyCoordinates.Tool tool : tools) {
      out.section(tool.toString());
      out.line(declaration(descriptor, tool, includeRuntime));
    }
    if (includeRuntime && version().equals("development")) {
      out.hint("Development build: runtime dependency omitted because no release version is embedded.");
    }
  }
}
