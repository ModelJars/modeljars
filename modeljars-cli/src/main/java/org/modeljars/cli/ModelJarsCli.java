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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
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
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.modeljars.ModelInstallProgress;
import org.modeljars.ModelJarCache;
import org.modeljars.ModelJarCoordinate;
import org.modeljars.ModelJarDescriptor;
import org.modeljars.ModelJarException;
import org.modeljars.ModelJarInstaller;
import org.modeljars.ModelJarRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.shell.jline3.PicocliJLineCompleter;

/** Standalone command line interface for discovering, caching, and trying qualified ModelJars. */
@Command(
    name = "modeljars",
    description = "Discover, inspect, cache, and run qualified ModelJars.",
    mixinStandardHelpOptions = true,
    versionProvider = ModelJarsCli.VersionProvider.class,
    sortOptions = false,
    subcommands = {
      ModelJarsCli.SearchCommand.class,
      ModelJarsCli.ListCommand.class,
      ModelJarsCli.ShowCommand.class,
      ModelJarsCli.PullCommand.class,
      ModelJarsCli.RemoveCommand.class,
      ModelJarsCli.AliasCommand.class,
      ModelJarsCli.RunCommand.class,
      ModelJarsCli.EmbedCommand.class,
      ModelJarsCli.CoordinatesCommand.class,
      ModelJarsCli.ContributeCommand.class,
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
  private final ContributionService contributionService;
  private final Clock clock;
  private final Map<String, String> generatedAliases;
  private final ModelAliasStore aliases;
  private final ModelInteractions interactions;

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
      names = "--progress",
      description = "Progress rendering: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
      defaultValue = "auto",
      scope = ScopeType.INHERIT)
  private PullProgressRenderer.Mode progressMode;

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
  private Terminal activeTerminal;
  private InputStream input;

  ModelJarsCli(ModelJarRegistry registry, ArtifactInstaller installer) {
    this(
        registry,
        installer,
        new SystemCapabilities()::detect,
        new HuggingFaceContributionService(),
        Clock.systemUTC());
  }

  ModelJarsCli(ModelJarRegistry registry, ArtifactInstaller installer, Clock clock) {
    this(
        registry,
        installer,
        new SystemCapabilities()::detect,
        new HuggingFaceContributionService(),
        clock);
  }

  ModelJarsCli(
      ModelJarRegistry registry,
      ArtifactInstaller installer,
      SystemCapabilities.Probe systemProbe) {
    this(registry, installer, systemProbe, new HuggingFaceContributionService(), Clock.systemUTC());
  }

  ModelJarsCli(
      ModelJarRegistry registry,
      ArtifactInstaller installer,
      SystemCapabilities.Probe systemProbe,
      ContributionService contributionService) {
    this(registry, installer, systemProbe, contributionService, Clock.systemUTC());
  }

  ModelJarsCli(
      ModelJarRegistry registry,
      ArtifactInstaller installer,
      SystemCapabilities.Probe systemProbe,
      ContributionService contributionService,
      Clock clock) {
    this(
        registry,
        installer,
        systemProbe,
        contributionService,
        clock,
        ModelAliasStore.defaults(),
        new DefaultModelInteractions());
  }

  ModelJarsCli(
      ModelJarRegistry registry,
      ArtifactInstaller installer,
      SystemCapabilities.Probe systemProbe,
      ContributionService contributionService,
      Clock clock,
      ModelAliasStore aliases,
      ModelInteractions interactions) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.systemProbe = Objects.requireNonNull(systemProbe, "systemProbe");
    this.contributionService = Objects.requireNonNull(contributionService, "contributionService");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.generatedAliases = GeneratedModelAliases.from(registry.descriptors());
    this.aliases = Objects.requireNonNull(aliases, "aliases");
    this.interactions = Objects.requireNonNull(interactions, "interactions");
  }

  /**
   * Runs the standalone CLI.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    configureLibraryLogging();
    ModelJarRegistry registry = RemoteCatalogRegistry.loadDefault();
    ArtifactInstaller installer =
        (descriptor, destination, progress) ->
            ModelJarInstaller.reportingProgressTo(registry, progress)
                .install(descriptor, destination);
    int status =
        new ModelJarsCli(registry, installer).launch(args, System.in, System.out, System.err, true);
    if (status != 0) {
      System.exit(status);
    }
  }

  private static void configureLibraryLogging() {
    Logger.getLogger("").setLevel(Level.WARNING);
    for (java.util.logging.Handler handler : Logger.getLogger("").getHandlers()) {
      handler.setLevel(Level.WARNING);
    }
    Logger.getLogger("org.modeljars").setLevel(Level.WARNING);
    Logger.getLogger("com.integrallis").setLevel(Level.WARNING);
  }

  @Override
  public Integer call() {
    commandSpec.commandLine().usage(commandSpec.commandLine().getOut());
    return 0;
  }

  int run(String[] args, PrintStream standardOutput, PrintStream standardError) {
    Objects.requireNonNull(args, "args");
    prepare(InputStream.nullInputStream(), standardOutput, standardError);
    return commandLine().execute(args);
  }

  int launch(
      String[] args,
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal) {
    return launch(
        args, standardInput, standardOutput, standardError, systemTerminal, defaultHistoryPath());
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
        : runOneShot(args, standardInput, standardOutput, standardError, systemTerminal);
  }

  private int runOneShot(
      String[] args,
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal) {
    prepare(standardInput, standardOutput, standardError);
    if (!systemTerminal) {
      return commandLine().execute(args);
    }
    try (Terminal terminal =
        TerminalBuilder.builder()
            .system(true)
            .systemOutput(TerminalBuilder.SystemOutput.SysErr)
            .streams(standardInput, standardError)
            .dumb(true)
            .build()) {
      activeTerminal = terminal;
      if (terminal.getWidth() > 0) {
        detectedWidth = terminal.getWidth();
      }
      return commandLine().execute(args);
    } catch (IOException ignored) {
      activeTerminal = null;
      return commandLine().execute(args);
    } finally {
      activeTerminal = null;
    }
  }

  private int runInteractive(
      InputStream standardInput,
      PrintStream standardOutput,
      PrintStream standardError,
      boolean systemTerminal,
      Path history) {
    Objects.requireNonNull(standardInput, "standardInput");
    Objects.requireNonNull(history, "history");
    prepare(standardInput, standardOutput, standardError);
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
                  .systemOutput(TerminalBuilder.SystemOutput.SysOut)
                  .streams(standardInput, standardOutput)
                  .dumb(true)
                  .build()
              : new DumbTerminal(standardInput, standardOutput)) {
        activeTerminal = terminal;
        if (terminal.getWidth() > 0) {
          detectedWidth = terminal.getWidth();
        }
        DefaultParser parser = new DefaultParser();
        LineReaderBuilder readerBuilder =
            LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .completer(
                    (reader, line, candidates) -> {
                      new PicocliJLineCompleter(completionCommand.getCommandSpec())
                          .complete(reader, line, candidates);
                      modelCompleter().complete(reader, line, candidates);
                    })
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
            runOneShot(
                parsed.words().toArray(String[]::new),
                standardInput,
                standardOutput,
                standardError,
                false);
          } catch (UserInterruptException ignored) {
            standardOutput.println();
          } catch (EndOfFileException ignored) {
            standardOutput.println();
            return 0;
          }
        }
      }
    } catch (IOException exception) {
      standardError.println(
          "Error: unable to open interactive terminal: " + exception.getMessage());
      return 2;
    } finally {
      activeTerminal = null;
    }
  }

  private void prepare(
      InputStream standardInput, PrintStream standardOutput, PrintStream standardError) {
    input = Objects.requireNonNull(standardInput, "standardInput");
    output = Objects.requireNonNull(standardOutput, "standardOutput");
    error = Objects.requireNonNull(standardError, "standardError");
    outputFormat = CliOutput.Format.TABLE;
    colorMode = CliOutput.ColorMode.AUTO;
    progressMode = PullProgressRenderer.Mode.AUTO;
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
          error.println(
              "Error: " + Objects.requireNonNullElse(reported.getMessage(), reported.toString()));
          error.println("Run 'modeljars <command> --help' for usage.");
          return 2;
        });
    return commandLine;
  }

  private CliOutput out() {
    return new CliOutput(output, outputFormat, colorMode, width());
  }

  private PullProgressRenderer pullProgress(boolean quiet) {
    PullProgressRenderer.Mode mode = quiet ? PullProgressRenderer.Mode.OFF : progressMode;
    boolean color =
        switch (colorMode) {
          case ALWAYS -> true;
          case NEVER -> false;
          case AUTO ->
              activeTerminal != null
                  && activeTerminal.getType() != null
                  && !activeTerminal.getType().toLowerCase(Locale.ROOT).startsWith("dumb")
                  && System.getenv("NO_COLOR") == null;
        };
    return new PullProgressRenderer(mode, activeTerminal, error, color, width());
  }

  private int width() {
    if (requestedWidth != null) {
      return Math.max(40, requestedWidth);
    }
    if (activeTerminal != null && activeTerminal.getWidth() > 0) {
      return Math.max(40, activeTerminal.getWidth());
    }
    return detectedWidth;
  }

  private Path cacheDirectory() {
    return cacheDirectory.toAbsolutePath().normalize();
  }

  private List<ModelJarDescriptor> descriptors() {
    return registry.descriptors();
  }

  private ModelArgumentCompleter modelCompleter() {
    return new ModelArgumentCompleter(
        descriptors().stream().map(ModelJarDescriptor::alias).toList(),
        this::selectorAliases,
        name ->
            descriptors().stream()
                .filter(descriptor -> descriptor.alias().equals(name))
                .findFirst()
                .map(this::cached)
                .orElse(false));
  }

  private Map<String, String> selectorAliases() {
    Map<String, String> names = new LinkedHashMap<>(aliases.aliases());
    generatedAliases.forEach(names::put);
    return Map.copyOf(names);
  }

  private String expandAlias(String selector) {
    if (selector == null) {
      return null;
    }
    return selectorAliases().getOrDefault(selector.trim(), selector.trim());
  }

  private String shortName(ModelJarDescriptor descriptor) {
    return generatedAliases.entrySet().stream()
        .filter(entry -> entry.getValue().equals(descriptor.alias()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(descriptor.alias());
  }

  private List<String> alternateNames(ModelJarDescriptor descriptor) {
    return selectorAliases().entrySet().stream()
        .filter(entry -> entry.getValue().equals(descriptor.alias()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  private ModelJarDescriptor resolve(String selector) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException("Model selector must not be blank");
    }
    String original = selector.trim();
    String requested = expandAlias(original);
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
        descriptors().stream()
            .filter(descriptor -> descriptor.sourceId().equals(requested))
            .toList();
    if (sourceMatches.size() == 1) {
      return sourceMatches.getFirst();
    }
    if (sourceMatches.size() > 1) {
      throw new IllegalArgumentException(
          "Source matches multiple variants; use an alias or exact marker coordinate: "
              + requested);
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
    String expanded = expandAlias(requested);
    if (!descriptor.alias().equals(requested)
        && !descriptor.alias().equals(expanded)
        && !descriptor.markerCoordinate().toString().equals(requested)
        && !descriptor.sourceId().equals(requested)) {
      throw new IllegalArgumentException(
          "This command requires an exact alias, source, or coordinate: " + selector);
    }
    return descriptor;
  }

  private boolean cached(ModelJarDescriptor descriptor) {
    Path artifact = ModelJarCache.artifactPath(descriptor, cacheDirectory());
    return ModelJarCache.isComplete(descriptor, artifact);
  }

  private boolean matchesQuery(ModelJarDescriptor descriptor, String query) {
    if (query.isEmpty()) {
      return true;
    }
    String searchable =
        Stream.of(
                descriptor.alias(),
                shortName(descriptor),
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
                Stream.concat(
                        Stream.of(token), SEARCH_ALIASES.getOrDefault(token, List.of()).stream())
                    .anyMatch(searchable::contains));
  }

  private Map<String, Object> descriptorMap(ModelJarDescriptor descriptor, Path cachePath) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("shortName", shortName(descriptor));
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
    values.put("publishedAt", descriptor.catalogPublishedAt().map(Instant::toString).orElse(null));
    values.put("backends", descriptor.backendSupport());
    values.put("dimensions", dimensionsMap(descriptor.dimensions()));
    values.put("status", ModelJarCache.isComplete(descriptor, cachePath) ? "cached" : "not_pulled");
    values.put("cachePath", cachePath.toString());
    return values;
  }

  private boolean isNew(ModelJarDescriptor descriptor) {
    Instant now = clock.instant();
    Instant threshold = now.minus(Duration.ofHours(48));
    return descriptor
        .catalogPublishedAt()
        .map(publishedAt -> !publishedAt.isBefore(threshold) && !publishedAt.isAfter(now))
        .orElse(false);
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

  private static void requireCapability(
      ModelJarDescriptor descriptor, Set<String> accepted, String operation) {
    if (descriptor.capabilities().stream().noneMatch(accepted::contains)) {
      throw new IllegalArgumentException(
          descriptor.alias() + " is not qualified for " + operation + " interactions");
    }
  }

  private static Map<String, Object> chatMetricsMap(ModelInteractions.ChatMetrics metrics) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("loadMillis", metrics.loadMillis());
    values.put("ttftMillis", metrics.ttftMillis());
    values.put("generationMillis", metrics.generationMillis());
    values.put("promptTokens", metrics.promptTokens());
    values.put("completionTokens", metrics.completionTokens());
    values.put("tokensPerSecond", metrics.tokensPerSecond());
    return values;
  }

  private static Map<String, Object> chatMetricsHuman(ModelInteractions.ChatMetrics metrics) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Load time", formatMillis(metrics.loadMillis()));
    values.put("TTFT", formatMillis(metrics.ttftMillis()));
    values.put("Generation time", formatMillis(metrics.generationMillis()));
    values.put("Prompt tokens", metrics.promptTokens());
    values.put("Completion tokens", metrics.completionTokens());
    values.put("Throughput", String.format(Locale.ROOT, "%.1f tok/s", metrics.tokensPerSecond()));
    return values;
  }

  private static Map<String, Object> embeddingMetricsMap(ModelInteractions.EmbeddingResult result) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("loadMillis", result.loadMillis());
    values.put("embeddingMillis", result.embeddingMillis());
    values.put("dimensions", result.vector().length);
    values.put("vectorNorm", result.vectorNorm());
    return values;
  }

  private static Map<String, Object> embeddingMetricsHuman(
      ModelInteractions.EmbeddingResult result) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("Load time", formatMillis(result.loadMillis()));
    values.put("Embedding time", formatMillis(result.embeddingMillis()));
    values.put("Dimensions", result.vector().length);
    values.put("Vector norm", String.format(Locale.ROOT, "%.6f", result.vectorNorm()));
    return values;
  }

  private static String formatMillis(long millis) {
    if (millis < 1_000) {
      return millis + " ms";
    }
    return String.format(Locale.ROOT, "%.2f s", millis / 1_000.0);
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
        Optional.ofNullable(System.getenv("COLUMNS")).flatMap(ModelJarsCli::positiveInteger);
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
      aliases = {"available", "models"},
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
            case ALIAS -> Comparator.comparing(parent::shortName);
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
              .filter(descriptor -> parent.matchesQuery(descriptor, terms))
              .filter(descriptor -> matches(capability, descriptor.capabilities()))
              .filter(descriptor -> matches(architecture, descriptor.architecture()))
              .filter(descriptor -> matches(quantization, descriptor.quantization()))
              .filter(descriptor -> matches(modelFormat, descriptor.format()))
              .filter(
                  descriptor ->
                      backend == null
                          || descriptor.supportsBackend(backend.toLowerCase(Locale.ROOT)))
              .filter(descriptor -> !installed || parent.cached(descriptor))
              .filter(
                  descriptor ->
                      !fitsMemory
                          || descriptor
                              .sizeBytes()
                              .map(size -> size <= availableMemory)
                              .orElse(false))
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
                    descriptor -> {
                      Map<String, Object> value =
                          parent.descriptorMap(
                              descriptor,
                              ModelJarCache.artifactPath(descriptor, parent.cacheDirectory()));
                      value.put("new", parent.isNew(descriptor));
                      return value;
                    })
                .toList());
        return;
      }
      if (out.format() == CliOutput.Format.PLAIN) {
        out.line(
            "SHORT_NAME\tALIAS\tNEW\tCAPABILITIES\tARCHITECTURE\tQUANTIZATION\tSIZE_BYTES\tSTATUS\tCOORDINATE");
        matches.forEach(
            descriptor ->
                out.line(
                    String.join(
                        "\t",
                        parent.shortName(descriptor),
                        descriptor.alias(),
                        Boolean.toString(parent.isNew(descriptor)),
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
              matches.stream().map(parent::shortName).mapToInt(String::length).max().orElse(0));
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
      columns.add(CliOutput.Column.left("NEW", 3, 3));
      columns.add(CliOutput.Column.left("ARCH", architectureWidth, architectureWidth));
      columns.add(CliOutput.Column.left("QUANT", quantizationWidth, quantizationWidth));
      columns.add(CliOutput.Column.right("SIZE", sizeWidth, sizeWidth));
      columns.add(CliOutput.Column.left("STATUS", 9, 9));

      List<List<CliOutput.Cell>> rows =
          matches.stream()
              .map(
                  descriptor -> {
                    List<CliOutput.Cell> row = new ArrayList<>();
                    boolean recent = parent.isNew(descriptor);
                    row.add(
                        new CliOutput.Cell(
                            parent.shortName(descriptor),
                            recent ? CliOutput.Tone.WARNING : CliOutput.Tone.NORMAL));
                    row.add(new CliOutput.Cell(recent ? "NEW" : "", CliOutput.Tone.WARNING));
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

    @Option(
        names = {"-v", "--details"},
        description = "Show capabilities for each model.")
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
              .filter(model -> ModelJarCache.isComplete(model.descriptor(), model.path()))
              .sorted(Comparator.comparing(model -> parent.shortName(model.descriptor())))
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
                          parent.descriptorMap(model.descriptor(), model.path());
                      value.put("modified", modified(model.path()).toString());
                      return value;
                    })
                .toList());
        return;
      }
      if (out.format() == CliOutput.Format.PLAIN) {
        out.line("SHORT_NAME\tALIAS\tSIZE_BYTES\tMODIFIED\tPATH\tCOORDINATE");
        models.forEach(
            model ->
                out.line(
                    String.join(
                        "\t",
                        parent.shortName(model.descriptor()),
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
              .map(model -> parent.shortName(model.descriptor()).length())
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
                    row.add(CliOutput.Cell.text(parent.shortName(model.descriptor())));
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
                      fields.add(CliOutput.Detail.text("CATALOG ID", model.descriptor().alias()));
                      if (details) {
                        fields.add(
                            CliOutput.Detail.text(
                                "CAPABILITIES", capabilities(model.descriptor())));
                        fields.add(CliOutput.Detail.text("BACKENDS", backends(model.descriptor())));
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

    @Parameters(
        paramLabel = "MODEL",
        description = "Short name, catalog ID, custom alias, source ID, or exact coordinate.")
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
        Map<String, Object> value = parent.descriptorMap(descriptor, cachePath);
        if (coordinates) {
          value.put("dependencyDeclarations", dependencyDeclarations(descriptor, true));
        }
        out.json(value);
      } else if (out.format() == CliOutput.Format.PLAIN) {
        parent
            .descriptorMap(descriptor, cachePath)
            .forEach((key, value) -> out.line(key + "=" + Objects.toString(value, "")));
      } else {
        renderHuman(
            descriptor,
            cachePath,
            out,
            details,
            parent.shortName(descriptor),
            parent.alternateNames(descriptor));
        if (coordinates) {
          printDeclarations(descriptor, out, true, List.of());
        } else {
          out.hint(
              "Run 'modeljars coordinates " + descriptor.alias() + "' for build declarations.");
        }
      }
      return 0;
    }

    private static void renderHuman(
        ModelJarDescriptor descriptor,
        Path cachePath,
        CliOutput out,
        boolean details,
        String shortName,
        List<String> alternateNames) {
      out.line(descriptor.name().orElse(descriptor.alias()));
      descriptor.description().ifPresent(out::hint);

      Map<String, Object> identity = new LinkedHashMap<>();
      identity.put("Short name", shortName);
      identity.put("Catalog ID", descriptor.alias());
      List<String> customNames =
          alternateNames.stream().filter(name -> !name.equals(shortName)).toList();
      if (!customNames.isEmpty()) {
        identity.put("Custom aliases", String.join(", ", customNames));
      }
      identity.put("Coordinate", descriptor.markerCoordinate());
      identity.put(
          "Status", ModelJarCache.isComplete(descriptor, cachePath) ? "cached" : "not pulled");
      identity.put("Architecture", descriptor.architecture());
      identity.put("Format", descriptor.format().toUpperCase(Locale.ROOT));
      identity.put("Quantization", descriptor.quantization());
      descriptor
          .dimensions()
          .parameterCount()
          .ifPresent(value -> identity.put("Parameters", humanParameters(value)));
      descriptor
          .dimensions()
          .contextLength()
          .ifPresent(value -> identity.put("Context", value + " tokens"));
      descriptor
          .dimensions()
          .embeddingLength()
          .ifPresent(value -> identity.put("Embedding", value + " dimensions"));
      descriptor
          .sizeBytes()
          .ifPresent(value -> identity.put("Download", CliOutput.humanBytes(value)));
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
      if (ModelJarCache.isComplete(descriptor, cachePath)) {
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

    @Parameters(
        paramLabel = "MODEL",
        description = "Short name, catalog ID, custom alias, source ID, or exact coordinate.")
    private String selector;

    @Option(
        names = {"-q", "--quiet"},
        description = "Print only the installed model path.")
    private boolean quiet;

    @Override
    public Integer call() {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      Path destination = ModelJarCache.artifactPath(descriptor, parent.cacheDirectory());
      Path artifact;
      PullProgressRenderer progress = parent.pullProgress(quiet);
      try (progress) {
        artifact =
            parent
                .installer
                .install(descriptor, destination, progress)
                .toAbsolutePath()
                .normalize();
      }
      CliOutput out = parent.out();
      if (quiet) {
        out.line(artifact.toString());
      } else if (out.format() == CliOutput.Format.JSON) {
        out.json(
            Map.of(
                "alias", descriptor.alias(),
                "coordinate", descriptor.markerCoordinate().toString(),
                "cached", progress.completionSource() == ModelInstallProgress.Source.CACHE,
                "elapsedSeconds", progress.elapsedSeconds(),
                "sha256", descriptor.sha256().orElse(""),
                "sizeBytes", descriptor.sizeBytes().orElse(-1L),
                "path", artifact.toString()));
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("coordinate=" + descriptor.markerCoordinate());
        out.line("sha256=" + descriptor.sha256().orElse(""));
        out.line("path=" + artifact);
      } else {
        boolean cached = progress.completionSource() == ModelInstallProgress.Source.CACHE;
        String elapsed = formatElapsed(progress.elapsedSeconds());
        out.success(
            cached
                ? descriptor.alias() + " already cached and verified in " + elapsed
                : descriptor.alias() + " ready in " + elapsed);
        out.hint(
            "  "
                + descriptor.sizeBytes().map(CliOutput::humanBytes).orElse("unknown size")
                + " · SHA-256 verified");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("Path", displayPath(artifact));
        details.put("Coordinate", descriptor.markerCoordinate());
        out.properties(details);
      }
      return 0;
    }

    private static String formatElapsed(double seconds) {
      if (seconds < 60) {
        return String.format(Locale.ROOT, "%.1fs", seconds);
      }
      int minutes = (int) (seconds / 60);
      return String.format(Locale.ROOT, "%dm %.1fs", minutes, seconds - minutes * 60);
    }

    private static String displayPath(Path path) {
      String home = System.getProperty("user.home");
      String value = path.toString();
      if (home != null && !home.isBlank() && value.startsWith(home + java.io.File.separator)) {
        return "~" + value.substring(home.length());
      }
      return value;
    }
  }

  @Command(
      name = "remove",
      aliases = {"rm", "delete"},
      description = "Remove a model from the local cache.",
      mixinStandardHelpOptions = true)
  static final class RemoveCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(
        paramLabel = "MODEL",
        description = "Exact short name, catalog ID, custom alias, source ID, or coordinate.")
    private String selector;

    @Option(
        names = {"-f", "--force"},
        description = "Succeed when the model is not cached.")
    private boolean force;

    @Override
    public Integer call() throws IOException {
      ModelJarDescriptor descriptor = parent.resolveExact(selector);
      Path root = parent.cacheDirectory().toAbsolutePath().normalize();
      Path artifact = ModelJarCache.artifactPath(descriptor, root);
      if (!artifact.startsWith(root)) {
        throw new IllegalStateException("Resolved cache path is outside the configured cache");
      }
      Path removedPath =
          descriptor.files().isEmpty() ? artifact : ModelJarCache.bundlePath(descriptor, root);
      if (!ModelJarCache.isComplete(descriptor, artifact)) {
        if (force) {
          return 0;
        }
        throw new IllegalArgumentException(
            "Model is not present in the local cache: " + descriptor.alias());
      }
      requireNoSymbolicLinks(root, removedPath);
      if (descriptor.files().isEmpty()) {
        Files.delete(artifact);
      } else {
        removeBundle(descriptor, removedPath);
      }
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        out.json(
            Map.of("alias", descriptor.alias(), "removed", true, "path", removedPath.toString()));
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("removed=" + descriptor.alias());
        out.line("path=" + removedPath);
      } else {
        out.success("Removed " + descriptor.alias());
        out.hint(removedPath.toString());
      }
      return 0;
    }

    private static void requireNoSymbolicLinks(Path root, Path target) {
      Path current = root;
      if (Files.isSymbolicLink(current)) {
        throw new IllegalStateException(
            "Refusing to remove through a symbolic link in the model cache");
      }
      for (Path part : root.relativize(target)) {
        current = current.resolve(part);
        if (Files.isSymbolicLink(current)) {
          throw new IllegalStateException(
              "Refusing to remove through a symbolic link in the model cache");
        }
      }
    }

    private static void removeBundle(ModelJarDescriptor descriptor, Path bundle)
        throws IOException {
      Set<Path> expectedFiles = new java.util.LinkedHashSet<>();
      Set<Path> expectedDirectories = new java.util.LinkedHashSet<>();
      expectedDirectories.add(bundle);
      for (var file : descriptor.files()) {
        Path expected = bundle.resolve(file.path()).normalize();
        if (!expected.startsWith(bundle)) {
          throw new IllegalStateException("Resolved model file is outside its cache bundle");
        }
        expectedFiles.add(expected);
        Path directory = expected.getParent();
        while (directory != null && directory.startsWith(bundle)) {
          expectedDirectories.add(directory);
          if (directory.equals(bundle)) {
            break;
          }
          directory = directory.getParent();
        }
      }

      List<Path> contents;
      try (var paths = Files.walk(bundle)) {
        contents = paths.toList();
      }
      for (Path path : contents) {
        if (Files.isSymbolicLink(path)) {
          throw new IllegalStateException(
              "Refusing to remove a symbolic link from the model cache");
        }
        if (Files.isDirectory(path)) {
          if (!expectedDirectories.contains(path)) {
            throw new IllegalStateException(
                "Refusing to remove unexpected cache directory: " + path);
          }
        } else if (!Files.isRegularFile(path) || !expectedFiles.contains(path)) {
          throw new IllegalStateException("Refusing to remove unexpected cache file: " + path);
        }
      }
      if (!expectedFiles.stream().allMatch(Files::isRegularFile)) {
        throw new IllegalStateException("Model cache bundle is incomplete: " + bundle);
      }

      for (Path path : expectedFiles) {
        Files.delete(path);
      }
      List<Path> directories = new java.util.ArrayList<>(expectedDirectories);
      directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
      for (Path path : directories) {
        Files.delete(path);
      }
    }
  }

  @Command(
      name = "alias",
      aliases = "nickname",
      description = "Show automatic short names and manage custom model aliases.",
      mixinStandardHelpOptions = true,
      subcommands = {
        AliasCommand.SetCommand.class,
        AliasCommand.ListCommand.class,
        AliasCommand.RemoveCommand.class
      })
  static final class AliasCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
      spec.commandLine().usage(spec.commandLine().getOut());
      return 0;
    }

    @Command(
        name = "set",
        description = "Assign an optional custom alias to an exact catalog model.",
        mixinStandardHelpOptions = true)
    static final class SetCommand implements Callable<Integer> {
      @ParentCommand private AliasCommand command;

      @Parameters(index = "0", paramLabel = "NAME", description = "Short shell-friendly name.")
      private String name;

      @Parameters(index = "1", paramLabel = "MODEL", description = "Catalog model selector.")
      private String selector;

      @Override
      public Integer call() {
        ModelJarsCli parent = command.parent;
        ModelJarDescriptor descriptor = parent.resolve(selector);
        Set<String> reserved =
            Stream.concat(
                    parent.descriptors().stream().map(ModelJarDescriptor::alias),
                    parent.generatedAliases.keySet().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        parent.aliases.set(name, descriptor.alias(), reserved);
        CliOutput out = parent.out();
        if (out.format() == CliOutput.Format.JSON) {
          out.json(Map.of("name", name, "model", descriptor.alias()));
        } else if (out.format() == CliOutput.Format.PLAIN) {
          out.line(name + "=" + descriptor.alias());
        } else {
          out.success(name + " → " + descriptor.alias());
          out.hint("Saved in " + parent.aliases.path());
        }
        return 0;
      }
    }

    @Command(
        name = "list",
        aliases = "ls",
        description = "List automatic short names and configured custom aliases.",
        mixinStandardHelpOptions = true)
    static final class ListCommand implements Callable<Integer> {
      @ParentCommand private AliasCommand command;

      @Override
      public Integer call() {
        ModelJarsCli parent = command.parent;
        Map<String, String> configured = parent.aliases.aliases();
        CliOutput out = parent.out();
        if (out.format() == CliOutput.Format.JSON) {
          out.json(Map.of("automatic", parent.generatedAliases, "custom", configured));
        } else if (out.format() == CliOutput.Format.PLAIN) {
          parent.generatedAliases.forEach(
              (name, model) -> out.line("automatic\t" + name + "=" + model));
          configured.forEach((name, model) -> out.line("custom\t" + name + "=" + model));
        } else {
          List<AliasListing> listed = new ArrayList<>();
          parent.generatedAliases.forEach(
              (name, model) -> listed.add(new AliasListing(name, model, "automatic")));
          configured.forEach(
              (name, model) ->
                  listed.add(
                      new AliasListing(
                          name,
                          model,
                          parent.generatedAliases.containsKey(name)
                              ? "custom (shadowed)"
                              : "custom")));
          int nameWidth =
              Math.max(
                  "NAME".length(),
                  listed.stream().map(AliasListing::name).mapToInt(String::length).max().orElse(0));
          int modelWidth =
              Math.max(
                  "MODEL".length(),
                  listed.stream()
                      .map(AliasListing::model)
                      .mapToInt(String::length)
                      .max()
                      .orElse(0));
          int sourceWidth =
              Math.max(
                  "SOURCE".length(),
                  listed.stream()
                      .map(AliasListing::source)
                      .mapToInt(String::length)
                      .max()
                      .orElse(0));
          List<List<CliOutput.Cell>> rows =
              listed.stream()
                  .map(
                      entry ->
                          List.of(
                              CliOutput.Cell.text(entry.name()),
                              CliOutput.Cell.text(entry.model()),
                              CliOutput.Cell.text(entry.source())))
                  .toList();
          out.table(
              List.of(
                  CliOutput.Column.left("NAME", nameWidth, nameWidth),
                  CliOutput.Column.left("MODEL", modelWidth, modelWidth),
                  CliOutput.Column.left("SOURCE", sourceWidth, sourceWidth)),
              rows);
        }
        return 0;
      }

      private record AliasListing(String name, String model, String source) {}
    }

    @Command(
        name = "remove",
        aliases = {"rm", "delete"},
        description = "Remove a custom model alias.",
        mixinStandardHelpOptions = true)
    static final class RemoveCommand implements Callable<Integer> {
      @ParentCommand private AliasCommand command;

      @Parameters(paramLabel = "NAME", description = "Configured custom alias.")
      private String name;

      @Override
      public Integer call() {
        ModelJarsCli parent = command.parent;
        if (!parent.aliases.remove(name)) {
          throw new IllegalArgumentException("No custom model alias is configured: " + name);
        }
        CliOutput out = parent.out();
        if (out.format() == CliOutput.Format.JSON) {
          out.json(Map.of("name", name, "removed", true));
        } else if (out.format() == CliOutput.Format.PLAIN) {
          out.line("removed=" + name);
        } else {
          out.success("Removed custom alias " + name);
        }
        return 0;
      }
    }
  }

  @Command(
      name = "run",
      aliases = "chat",
      description = "Run a qualified chat model locally through the ModelJars Java API.",
      footer = {
        "Examples:",
        "  modeljars run qwen 'Name one JVM language.'",
        "  modeljars run qwen"
      },
      mixinStandardHelpOptions = true)
  static final class RunCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(
        index = "0",
        paramLabel = "MODEL",
        description = "Short name, catalog ID, or custom alias.")
    private String selector;

    @Parameters(
        index = "1..*",
        arity = "0..*",
        paramLabel = "PROMPT",
        description = "Prompt text; omit for an interactive chat.")
    private List<String> prompt = List.of();

    @Option(names = "--max-tokens", defaultValue = "128", description = "Maximum output tokens.")
    private int maxTokens;

    @Option(names = "--temperature", defaultValue = "0", description = "Sampling temperature.")
    private float temperature;

    @Option(names = "--seed", description = "Deterministic sampling seed.")
    private Long seed;

    @Override
    public Integer call() throws IOException {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      requireCapability(
          descriptor, Set.of("chat", "generation", "text-generation", "tool-calling"), "chat");
      ModelInteractions.ChatOptions options =
          new ModelInteractions.ChatOptions(maxTokens, temperature, seed);
      try (ModelInteractions.ChatSession session =
          parent.interactions.openChat(descriptor, parent.cacheDirectory())) {
        if (prompt.isEmpty()) {
          return interactive(session, descriptor, options);
        }
        String text = String.join(" ", prompt).strip();
        ModelInteractions.ChatResult result =
            generate(
                session,
                List.of(new ModelInteractions.ChatTurn(ModelInteractions.Role.USER, text)),
                options,
                text);
        render(result, text, descriptor, false);
        return 0;
      }
    }

    private int interactive(
        ModelInteractions.ChatSession session,
        ModelJarDescriptor descriptor,
        ModelInteractions.ChatOptions options)
        throws IOException {
      if (parent.out().format() != CliOutput.Format.TABLE) {
        throw new IllegalArgumentException("Interactive chat requires table output");
      }
      List<ModelInteractions.ChatTurn> history = new ArrayList<>();
      LineReader reader =
          parent.activeTerminal == null
              ? null
              : LineReaderBuilder.builder()
                  .terminal(parent.activeTerminal)
                  .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                  .build();
      BufferedReader buffered =
          reader == null
              ? new BufferedReader(new InputStreamReader(parent.input, StandardCharsets.UTF_8))
              : null;
      parent.out().hint("Chatting with " + descriptor.alias() + " · /clear resets · /bye exits");
      while (true) {
        String line;
        try {
          line = reader == null ? buffered.readLine() : reader.readLine(">>> ");
        } catch (EndOfFileException ignored) {
          return 0;
        }
        if (line == null || line.strip().equalsIgnoreCase("/bye")) {
          return 0;
        }
        String text = line.strip();
        if (text.isEmpty()) {
          continue;
        }
        if (text.equalsIgnoreCase("/clear")) {
          history.clear();
          session.clear();
          parent.out().hint("Conversation cleared.");
          continue;
        }
        history.add(new ModelInteractions.ChatTurn(ModelInteractions.Role.USER, text));
        ModelInteractions.ChatResult result = generate(session, history, options, text);
        render(result, text, descriptor, true);
        if (!result.text().isBlank()) {
          history.add(
              new ModelInteractions.ChatTurn(ModelInteractions.Role.ASSISTANT, result.text()));
        }
      }
    }

    private ModelInteractions.ChatResult generate(
        ModelInteractions.ChatSession session,
        List<ModelInteractions.ChatTurn> history,
        ModelInteractions.ChatOptions options,
        String promptText) {
      CliOutput out = parent.out();
      boolean stream = out.format() == CliOutput.Format.TABLE;
      if (stream) {
        out.section("Prompt");
        out.line(promptText);
        out.section("Response");
      }
      ModelInteractions.ChatResult result =
          session.generate(history, options, stream ? parent.output::print : ignored -> {});
      if (stream) {
        parent.output.println();
      }
      return result;
    }

    private void render(
        ModelInteractions.ChatResult result,
        String promptText,
        ModelJarDescriptor descriptor,
        boolean interactive) {
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model", descriptor.alias());
        value.put("prompt", promptText);
        value.put("response", result.text());
        value.put("metrics", chatMetricsMap(result.metrics()));
        out.json(value);
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("model=" + descriptor.alias());
        out.line("prompt=" + promptText.replace("\n", "\\n"));
        out.line("response=" + result.text().replace("\n", "\\n"));
        chatMetricsMap(result.metrics())
            .forEach((key, value) -> out.line(key + "=" + Objects.toString(value)));
      } else {
        out.section("Metrics");
        out.properties(chatMetricsHuman(result.metrics()));
        if (!interactive) {
          out.hint("Executed locally through ModelJars and Models; no external inference server.");
        }
      }
    }
  }

  @Command(
      name = "embed",
      aliases = "embedding",
      description = "Create an embedding locally through the ModelJars Java API.",
      footer = "Example: modeljars embed embeddinggemma 'Public transit schedule'",
      mixinStandardHelpOptions = true)
  static final class EmbedCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(
        index = "0",
        paramLabel = "MODEL",
        description = "Short name, catalog ID, or custom alias.")
    private String selector;

    @Parameters(index = "1..*", arity = "1..*", paramLabel = "TEXT", description = "Text to embed.")
    private List<String> text;

    @Override
    public Integer call() {
      ModelJarDescriptor descriptor = parent.resolve(selector);
      requireCapability(
          descriptor, Set.of("embedding", "embeddings", "text-embedding"), "embedding");
      String inputText = String.join(" ", text).strip();
      ModelInteractions.EmbeddingResult result =
          parent.interactions.embed(descriptor, parent.cacheDirectory(), inputText);
      List<Float> vector = new ArrayList<>(result.vector().length);
      for (float value : result.vector()) {
        vector.add(value);
      }
      CliOutput out = parent.out();
      if (out.format() == CliOutput.Format.JSON) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model", descriptor.alias());
        value.put("text", inputText);
        value.put("vector", vector);
        value.put("metrics", embeddingMetricsMap(result));
        out.json(value);
      } else if (out.format() == CliOutput.Format.PLAIN) {
        out.line("model=" + descriptor.alias());
        out.line("text=" + inputText.replace("\n", "\\n"));
        out.line("vector=" + vector);
        embeddingMetricsMap(result)
            .forEach((key, value) -> out.line(key + "=" + Objects.toString(value)));
      } else {
        out.section("Input");
        out.line(inputText);
        out.section("Embedding");
        out.line(vector.toString());
        out.section("Metrics");
        out.properties(embeddingMetricsHuman(result));
        out.hint("Executed locally through ModelJars and Models; no external inference server.");
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

    @Parameters(
        paramLabel = "MODEL",
        description = "Short name, catalog ID, custom alias, source ID, or exact coordinate.")
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
      name = "contribute",
      aliases = {"submit-model"},
      description = "Pin and prepare a model candidate submission from Hugging Face.",
      mixinStandardHelpOptions = true)
  static final class ContributeCommand implements Callable<Integer> {
    @ParentCommand private ModelJarsCli parent;

    @Parameters(
        index = "0",
        paramLabel = "OWNER/REPOSITORY",
        description = "Hugging Face repository or URL.")
    private String source;

    @Option(
        names = "--revision",
        defaultValue = "main",
        paramLabel = "REVISION",
        description = "Revision to resolve to an immutable commit (default: ${DEFAULT-VALUE}).")
    private String revision;

    @Option(
        names = "--file",
        paramLabel = "PATH",
        description = "Select a repository file; repeat for an explicit artifact file set.")
    private List<String> files = new ArrayList<>();

    @Option(
        names = "--license",
        paramLabel = "SPDX",
        description = "Override or supply the upstream SPDX license identifier.")
    private String license;

    @Option(
        names = "--domain",
        split = ",",
        paramLabel = "DOMAIN",
        description = "Intended catalog domain; repeat or comma-separate values.")
    private List<String> domains = new ArrayList<>();

    @Option(
        names = "--capability",
        split = ",",
        paramLabel = "CAPABILITY",
        description = "Override upstream capability hints; repeat or comma-separate values.")
    private List<String> capabilities = new ArrayList<>();

    @Option(
        names = "--output-file",
        paramLabel = "FILE",
        description = "Write the candidate issue body to this new file.")
    private Path outputFile;

    @Override
    public Integer call() throws Exception {
      ContributionDraft draft =
          parent.contributionService.prepare(
              new ContributionRequest(
                  source, revision, files, Optional.ofNullable(license), domains, capabilities));
      Path destination =
          Optional.ofNullable(outputFile)
              .orElseGet(
                  () ->
                      Path.of(
                          "modeljars-submission-"
                              + draft
                                  .repository()
                                  .toLowerCase(Locale.ROOT)
                                  .replaceAll("[^a-z0-9]+", "-")
                              + ".md"))
              .toAbsolutePath()
              .normalize();
      Path parentDirectory = destination.getParent();
      if (parentDirectory != null && !Files.isDirectory(parentDirectory)) {
        throw new IllegalArgumentException("Output directory does not exist: " + parentDirectory);
      }
      Files.writeString(
          destination,
          draft.markdown(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);

      String submit =
          "gh issue create --repo ModelJars/modeljars --title "
              + shellQuote(draft.title())
              + " --body-file "
              + shellQuote(destination.toString());
      if (parent.out().format() == CliOutput.Format.JSON) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("repository", draft.repository());
        value.put("revision", draft.revision());
        value.put("format", draft.format());
        value.put("files", draft.files().size());
        value.put("outputFile", destination.toString());
        value.put("submitCommand", submit);
        parent.out().json(value);
      } else if (parent.out().format() == CliOutput.Format.PLAIN) {
        parent.out().line(destination.toString());
        parent.out().line(submit);
      } else {
        parent.out().success("Candidate submission prepared: " + draft.name());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("Repository", draft.repository());
        facts.put("Revision", draft.revision());
        facts.put("Format", draft.format());
        facts.put("Files", draft.files().size());
        facts.put("Issue body", destination);
        parent.out().properties(facts);
        parent.out().section("Submit to ModelJars");
        parent.out().line(submit);
        parent
            .out()
            .hint(
                "The issue starts candidate intake; Models compatibility and controlled qualification remain maintainer-reviewed gates.");
      }
      return 0;
    }

    private static String shellQuote(String value) {
      return '\'' + value.replace("'", "'\\''") + '\'';
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
      host.put(
          "CPU cores",
          snapshot.physicalCores() + " physical / " + snapshot.logicalCores() + " logical");
      host.put(
          "SIMD",
          snapshot.simd().isEmpty() ? "not discovered" : String.join(", ", snapshot.simd()));
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
              nested.forEach(
                  (nestedKey, nestedValue) -> converted.put(nestedKey.toString(), nestedValue));
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
        ModelJarDescriptor descriptor,
        Path destination,
        Consumer<ModelInstallProgress> progressReporter);
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
      if (ModelJarCache.isComplete(descriptor, path)) {
        count++;
        try {
          bytes = Math.addExact(bytes, installedSize(descriptor, path, cacheDirectory));
        } catch (IOException ignored) {
          bytes = Math.addExact(bytes, declaredSize(descriptor));
        }
      }
    }
    return new CacheSummary(count, bytes, cacheDirectory);
  }

  private static long installedSize(
      ModelJarDescriptor descriptor, Path primaryArtifact, Path cacheDirectory) throws IOException {
    if (descriptor.files().isEmpty()) {
      return Files.size(primaryArtifact);
    }
    Path bundle = ModelJarCache.bundlePath(descriptor, cacheDirectory);
    long bytes = 0;
    for (var file : descriptor.files()) {
      bytes = Math.addExact(bytes, Files.size(bundle.resolve(file.path())));
    }
    return bytes;
  }

  private static long declaredSize(ModelJarDescriptor descriptor) {
    if (descriptor.files().isEmpty()) {
      return descriptor.sizeBytes().orElse(0L);
    }
    long bytes = 0;
    for (var file : descriptor.files()) {
      bytes = Math.addExact(bytes, file.sizeBytes());
    }
    return bytes;
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
      ModelJarDescriptor descriptor, DependencyCoordinates.Tool tool, boolean includeRuntime) {
    String marker = DependencyCoordinates.render(descriptor.markerCoordinate(), tool);
    if (!includeRuntime || version().equals("development")) {
      return marker;
    }
    ModelJarCoordinate runtime =
        new ModelJarCoordinate("org.modeljars", "modeljars", version(), Optional.empty(), "jar");
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
      out.hint(
          "Development build: runtime dependency omitted because no release version is embedded.");
    }
  }
}
