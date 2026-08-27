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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Best-effort, bounded host discovery for inference-relevant capabilities. */
final class SystemCapabilities {
  private static final int MAX_PROBE_OUTPUT_BYTES = 1_048_576;

  enum Status {
    READY,
    ELIGIBLE,
    DETECTED,
    UNAVAILABLE
  }

  record GraphicsDevice(String name, Optional<Long> dedicatedMemoryBytes) {
    GraphicsDevice {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("graphics device name must not be blank");
      }
      dedicatedMemoryBytes = dedicatedMemoryBytes.filter(value -> value > 0);
    }
  }

  record Backend(String name, Status status, String workloads, String detail) {}

  record Snapshot(
      String operatingSystem,
      String architecture,
      String processor,
      int physicalCores,
      int logicalCores,
      long totalMemoryBytes,
      long freeMemoryBytes,
      List<String> simd,
      List<GraphicsDevice> graphicsDevices,
      boolean nativeExecutable,
      String javaRuntime,
      List<Backend> backends) {
    Snapshot {
      simd = List.copyOf(simd);
      graphicsDevices = List.copyOf(graphicsDevices);
      backends = List.copyOf(backends);
    }
  }

  @FunctionalInterface
  interface Probe {
    Snapshot detect();
  }

  private final CommandRunner commands;

  SystemCapabilities() {
    this(new CommandRunner(Duration.ofSeconds(4)));
  }

  SystemCapabilities(CommandRunner commands) {
    this.commands = commands;
  }

  Snapshot detect() {
    String osName = System.getProperty("os.name", "unknown");
    String osVersion = System.getProperty("os.version", "unknown");
    String architecture = normalizeArchitecture(System.getProperty("os.arch", "unknown"));
    int logicalCores = Runtime.getRuntime().availableProcessors();
    String platform = osName.toLowerCase(Locale.ROOT);
    String cpuInfo = platform.contains("linux") ? read(Path.of("/proc/cpuinfo")) : "";
    String macHardware =
        platform.contains("mac")
            ? commands.run("/usr/sbin/system_profiler", "SPHardwareDataType").orElse("")
            : "";

    String processor = processor(platform, cpuInfo, macHardware);
    int physicalCores = physicalCores(platform, cpuInfo, macHardware, logicalCores);
    List<String> simd = simd(platform, architecture, cpuInfo);
    List<GraphicsDevice> graphics = graphicsDevices(platform);
    long[] memory = memory();
    boolean nativeExecutable = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    String javaRuntime =
        nativeExecutable
            ? "GraalVM native executable"
            : System.getProperty("java.runtime.name", "Java")
                + " "
                + System.getProperty("java.runtime.version", "unknown");
    List<Backend> backends = backends(platform, architecture, graphics);

    return new Snapshot(
        displayOperatingSystem(osName, osVersion),
        architecture,
        processor,
        physicalCores,
        logicalCores,
        memory[0],
        memory[1],
        simd,
        graphics,
        nativeExecutable,
        javaRuntime,
        backends);
  }

  private String processor(String platform, String cpuInfo, String macHardware) {
    if (platform.contains("mac")) {
      return commands
          .run("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string")
          .or(() -> valueForOptionalLabel(macHardware, "Chip:"))
          .or(() -> valueForOptionalLabel(macHardware, "Processor Name:"))
          .or(() -> commands.run("/usr/sbin/sysctl", "-n", "hw.model"))
          .orElse("unknown");
    }
    if (platform.contains("linux")) {
      return firstCpuInfoValue(cpuInfo, "model name")
          .or(() -> firstCpuInfoValue(cpuInfo, "hardware"))
          .or(() -> firstCpuInfoValue(cpuInfo, "model"))
          .orElse("unknown");
    }
    if (platform.contains("win")) {
      return Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER"))
          .filter(value -> !value.isBlank())
          .orElse("unknown");
    }
    return "unknown";
  }

  private int physicalCores(String platform, String cpuInfo, String macHardware, int logicalCores) {
    if (platform.contains("mac")) {
      return commands
          .run("/usr/sbin/sysctl", "-n", "hw.physicalcpu")
          .flatMap(SystemCapabilities::parsePositiveInt)
          .or(
              () ->
                  valueForOptionalLabel(macHardware, "Total Number of Cores:")
                      .flatMap(SystemCapabilities::parsePositiveInt))
          .orElse(logicalCores);
    }
    if (platform.contains("linux")) {
      Set<String> coreIds = new LinkedHashSet<>();
      String physicalId = "0";
      for (String rawLine : cpuInfo.lines().toList()) {
        String line = rawLine.strip();
        if (line.isEmpty()) {
          physicalId = "0";
        } else if (line.startsWith("physical id")) {
          physicalId = valueAfterColon(line).orElse("0");
        } else if (line.startsWith("core id")) {
          Optional<String> coreId = valueAfterColon(line);
          if (coreId.isPresent()) {
            coreIds.add(physicalId + ':' + coreId.orElseThrow());
          }
        }
      }
      if (!coreIds.isEmpty()) {
        return coreIds.size();
      }
      return firstCpuInfoValue(cpuInfo, "cpu cores")
          .flatMap(SystemCapabilities::parsePositiveInt)
          .orElse(logicalCores);
    }
    return logicalCores;
  }

  private List<String> simd(String platform, String architecture, String cpuInfo) {
    if (architecture.equals("aarch64") && platform.contains("mac")) {
      return List.of("NEON (128-bit)");
    }
    String features = cpuInfo;
    if (platform.contains("mac") && architecture.equals("x86_64")) {
      features =
          commands.run("/usr/sbin/sysctl", "-n", "machdep.cpu.features").orElse("")
              + ' '
              + commands.run("/usr/sbin/sysctl", "-n", "machdep.cpu.leaf7_features").orElse("");
    }
    String normalized = ' ' + features.toLowerCase(Locale.ROOT).replace('\n', ' ') + ' ';
    List<String> result = new ArrayList<>();
    addFeature(result, normalized, "avx512f", "AVX-512");
    addFeature(result, normalized, "avx2", "AVX2");
    addFeature(result, normalized, " avx ", "AVX");
    addFeature(result, normalized, "sse4_2", "SSE4.2");
    addFeature(result, normalized, " asimd ", "NEON (128-bit)");
    addFeature(result, normalized, " sve ", "SVE");
    if (result.isEmpty() && architecture.equals("aarch64")) {
      result.add("NEON (architecture baseline)");
    } else if (result.isEmpty() && architecture.equals("x86_64")) {
      result.add("SSE2 (architecture baseline)");
    }
    return List.copyOf(result);
  }

  private List<GraphicsDevice> graphicsDevices(String platform) {
    if (platform.contains("mac")) {
      return commands
          .run("/usr/sbin/system_profiler", "SPDisplaysDataType")
          .map(SystemCapabilities::parseMacGraphics)
          .orElseGet(List::of);
    }
    if (platform.contains("linux")) {
      List<GraphicsDevice> nvidia =
          commands
              .run("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")
              .map(SystemCapabilities::parseNvidiaGraphics)
              .orElseGet(List::of);
      if (!nvidia.isEmpty()) {
        return nvidia;
      }
      return commands.run("lspci").map(SystemCapabilities::parsePciGraphics).orElseGet(List::of);
    }
    if (platform.contains("win")) {
      return commands
          .run(
              "powershell.exe",
              "-NoProfile",
              "-NonInteractive",
              "-Command",
              "Get-CimInstance Win32_VideoController | ForEach-Object { "
                  + "\"$($_.Name)`t$($_.AdapterRAM)\" }")
          .map(SystemCapabilities::parseWindowsGraphics)
          .orElseGet(List::of);
    }
    return List.of();
  }

  private static List<Backend> backends(
      String platform, String architecture, List<GraphicsDevice> graphics) {
    boolean commonCpu = architecture.equals("aarch64") || architecture.equals("x86_64");
    List<Backend> result = new ArrayList<>();
    result.add(
        new Backend(
            "GGUF / CPU",
            commonCpu ? Status.READY : Status.UNAVAILABLE,
            "generation, embeddings",
            "ModelJars Java 25 runtime; SIMD is selected automatically"));
    result.add(
        new Backend(
            "Native kernels",
            commonCpu ? Status.ELIGIBLE : Status.UNAVAILABLE,
            "generation, embeddings",
            commonCpu
                ? "Models probes the bundled Rust FFM backend at runtime"
                : "No native-kernel artifact is published for this architecture"));

    boolean appleSilicon = platform.contains("mac") && architecture.equals("aarch64");
    result.add(
        new Backend(
            "Apple Foundation Models",
            appleSilicon ? Status.ELIGIBLE : Status.UNAVAILABLE,
            "system text generation",
            appleSilicon
                ? "Platform eligible; backend-apple verifies Apple Intelligence availability"
                : "Requires macOS on Apple silicon"));

    result.add(
        new Backend(
            "GPU model offload",
            graphics.isEmpty() ? Status.UNAVAILABLE : Status.DETECTED,
            "not yet supported",
            graphics.isEmpty()
                ? "No graphics device was discovered"
                : "Hardware detected; current ModelJars GGUF inference is CPU-based"));
    return List.copyOf(result);
  }

  private static long[] memory() {
    java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
    if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
      return new long[] {extended.getTotalMemorySize(), extended.getFreeMemorySize()};
    }
    return new long[] {-1, -1};
  }

  static List<GraphicsDevice> parseMacGraphics(String output) {
    List<GraphicsDevice> result = new ArrayList<>();
    for (String line : output.lines().toList()) {
      String value = valueForLabel(line, "Chipset Model:");
      if (!value.isEmpty()) {
        result.add(new GraphicsDevice(value, Optional.empty()));
      }
    }
    return List.copyOf(result);
  }

  static List<GraphicsDevice> parseNvidiaGraphics(String output) {
    List<GraphicsDevice> result = new ArrayList<>();
    for (String line :
        output.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
      String[] fields = line.split(",", 2);
      Optional<Long> memory =
          fields.length == 2
              ? parsePositiveLong(fields[1].strip()).map(mebibytes -> mebibytes * 1024L * 1024L)
              : Optional.empty();
      result.add(new GraphicsDevice(fields[0].strip(), memory));
    }
    return List.copyOf(result);
  }

  static List<GraphicsDevice> parsePciGraphics(String output) {
    return output
        .lines()
        .map(String::strip)
        .filter(
            line -> {
              String normalized = line.toLowerCase(Locale.ROOT);
              return normalized.contains("vga compatible controller")
                  || normalized.contains("3d controller")
                  || normalized.contains("display controller");
            })
        .map(
            line -> {
              int separator = line.indexOf(": ");
              String name = separator >= 0 ? line.substring(separator + 2) : line;
              return new GraphicsDevice(name, Optional.empty());
            })
        .toList();
  }

  static List<GraphicsDevice> parseWindowsGraphics(String output) {
    List<GraphicsDevice> result = new ArrayList<>();
    for (String line :
        output.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
      String[] fields = line.split("\\t", 2);
      Optional<Long> memory =
          fields.length == 2 ? parsePositiveLong(fields[1].strip()) : Optional.empty();
      result.add(new GraphicsDevice(fields[0].strip(), memory));
    }
    return List.copyOf(result);
  }

  private static Optional<String> firstCpuInfoValue(String cpuInfo, String key) {
    return cpuInfo
        .lines()
        .map(String::strip)
        .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT)))
        .map(SystemCapabilities::valueAfterColon)
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static Optional<String> valueAfterColon(String line) {
    int separator = line.indexOf(':');
    if (separator < 0 || separator == line.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(line.substring(separator + 1).strip()).filter(value -> !value.isEmpty());
  }

  private static String valueForLabel(String line, String label) {
    String stripped = line.strip();
    return stripped.startsWith(label) ? stripped.substring(label.length()).strip() : "";
  }

  private static Optional<String> valueForOptionalLabel(String output, String label) {
    return output
        .lines()
        .map(line -> valueForLabel(line, label))
        .filter(value -> !value.isEmpty())
        .findFirst();
  }

  private static Optional<Integer> parsePositiveInt(String value) {
    try {
      int parsed = Integer.parseInt(value.strip());
      return parsed > 0 ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Long> parsePositiveLong(String value) {
    try {
      long parsed = Long.parseLong(value.strip());
      return parsed > 0 ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static void addFeature(
      List<String> result, String normalizedFeatures, String token, String label) {
    String normalizedToken = token.startsWith(" ") ? token : ' ' + token + ' ';
    if (normalizedFeatures.contains(normalizedToken) && !result.contains(label)) {
      result.add(label);
    }
  }

  private static String normalizeArchitecture(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "amd64", "x86_64" -> "x86_64";
      case "aarch64", "arm64" -> "aarch64";
      default -> normalized;
    };
  }

  private static String displayOperatingSystem(String name, String version) {
    String displayName = name.toLowerCase(Locale.ROOT).contains("mac") ? "macOS" : name;
    return displayName + ' ' + version;
  }

  private static String read(Path path) {
    try {
      return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    } catch (IOException ignored) {
      return "";
    }
  }

  static final class CommandRunner {
    private final Duration timeout;

    CommandRunner(Duration timeout) {
      this.timeout = timeout;
    }

    Optional<String> run(String... command) {
      Process process;
      try {
        process = new ProcessBuilder(Arrays.asList(command)).redirectErrorStream(true).start();
      } catch (IOException | SecurityException ignored) {
        return Optional.empty();
      }
      AtomicReference<byte[]> captured = new AtomicReference<>();
      AtomicReference<IOException> readFailure = new AtomicReference<>();
      Thread outputReader =
          Thread.ofPlatform()
              .daemon()
              .name("modeljars-capability-probe")
              .start(
                  () -> {
                    try {
                      captured.set(readBounded(process.getInputStream()));
                    } catch (IOException failure) {
                      readFailure.set(failure);
                    }
                  });
      try {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(1, TimeUnit.SECONDS);
          join(outputReader);
          return Optional.empty();
        }
        join(outputReader);
        byte[] bytes = captured.get();
        if (process.exitValue() != 0 || readFailure.get() != null || bytes == null) {
          return Optional.empty();
        }
        return Optional.of(new String(bytes, StandardCharsets.UTF_8).strip())
            .filter(value -> !value.isEmpty());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int remaining = MAX_PROBE_OUTPUT_BYTES;
      int count;
      while ((count = stream.read(buffer)) >= 0) {
        if (remaining > 0) {
          int stored = Math.min(count, remaining);
          captured.write(buffer, 0, stored);
          remaining -= stored;
        }
      }
      return captured.toByteArray();
    }

    private static void join(Thread outputReader) throws InterruptedException {
      outputReader.join(1000);
      if (outputReader.isAlive()) {
        outputReader.interrupt();
      }
    }
  }
}
