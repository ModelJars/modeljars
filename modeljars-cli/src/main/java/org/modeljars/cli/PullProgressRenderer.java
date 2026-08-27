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

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jline.terminal.Terminal;
import org.jline.utils.Display;
import org.modeljars.ModelInstallProgress;

/** Renders structured installer events as either a live terminal region or stable text. */
final class PullProgressRenderer implements Consumer<ModelInstallProgress>, AutoCloseable {
  private static final String RESET = "\u001B[0m";
  private static final String CYAN = "\u001B[36m";
  private static final String GREEN = "\u001B[32m";
  private static final String YELLOW = "\u001B[33m";
  private static final String DIM = "\u001B[2m";
  private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
  private static final String[] UNICODE_SPINNER = {
    "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
  };
  private static final String[] ASCII_SPINNER = {"|", "/", "-", "\\"};

  enum Mode {
    AUTO,
    BAR,
    PLAIN,
    OFF;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private enum Phase {
    DOWNLOAD,
    VERIFY
  }

  private final Mode mode;
  private final Terminal terminal;
  private final PrintStream fallback;
  private final boolean color;
  private final boolean unicode;
  private final int configuredWidth;
  private final LongSupplier ticker;
  private final Display display;
  private final ScheduledExecutorService animator;
  private final Object displayLock = new Object();

  private volatile Snapshot snapshot;
  private volatile boolean closed;
  private volatile boolean rendered;
  private long operationStartedNanos = -1;
  private long operationCompletedNanos = -1;
  private long lastSampleNanos = -1;
  private long lastSampleBytes;
  private double bytesPerSecond;
  private int nextPlainDownloadPercentage = 25;
  private int nextPlainVerificationPercentage = 25;
  private ModelInstallProgress.Source completionSource;

  PullProgressRenderer(
      Mode requestedMode, Terminal terminal, PrintStream fallback, boolean color, int width) {
    this(requestedMode, terminal, fallback, color, width, System::nanoTime);
  }

  PullProgressRenderer(
      Mode requestedMode,
      Terminal terminal,
      PrintStream fallback,
      boolean color,
      int width,
      LongSupplier ticker) {
    this.terminal = terminal;
    this.fallback = Objects.requireNonNull(fallback, "fallback");
    this.color = color;
    this.configuredWidth = Math.max(40, width);
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.mode = effectiveMode(Objects.requireNonNull(requestedMode, "requestedMode"), terminal);
    this.unicode = supportsUnicode(terminal);
    this.display = this.mode == Mode.BAR ? new Display(terminal, false) : null;
    this.animator = this.mode == Mode.BAR ? animator() : null;
    if (animator != null) {
      animator.scheduleAtFixedRate(this::renderSafely, 0, 100, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void accept(ModelInstallProgress progress) {
    Objects.requireNonNull(progress, "progress");
    if (closed) {
      return;
    }
    long now = ticker.getAsLong();
    if (operationStartedNanos < 0) {
      operationStartedNanos = now;
    }

    if (progress instanceof ModelInstallProgress.DownloadStarted started) {
      beginPhase(
          new Snapshot(
              Phase.DOWNLOAD, started.alias(), started.completedBytes(), started.totalBytes(), now),
          now);
      nextPlainDownloadPercentage = 25;
      if (mode == Mode.PLAIN) {
        fallback.printf(
            Locale.ROOT,
            "Downloading %s (%s)%n",
            started.alias(),
            CliOutput.humanBytes(started.totalBytes()));
      } else {
        renderNow();
      }
    } else if (progress instanceof ModelInstallProgress.DownloadAdvanced advanced) {
      advance(
          Phase.DOWNLOAD, advanced.alias(), advanced.completedBytes(), advanced.totalBytes(), now);
      if (mode == Mode.PLAIN) {
        nextPlainDownloadPercentage =
            printPlainProgress("Download", advanced, nextPlainDownloadPercentage);
      }
    } else if (progress instanceof ModelInstallProgress.Retrying retrying) {
      String warning =
          "Download interrupted at "
              + CliOutput.humanBytes(retrying.completedBytes())
              + ": "
              + conciseReason(retrying.reason())
              + "; retrying in "
              + formatDelay(retrying.delayMillis())
              + " ("
              + retrying.attempt()
              + "/"
              + retrying.maximumAttempts()
              + ")";
      if (mode == Mode.PLAIN) {
        fallback.println("Warning: " + warning);
      } else if (mode == Mode.BAR) {
        printAbove(warning);
      }
    } else if (progress instanceof ModelInstallProgress.VerificationStarted started) {
      beginPhase(new Snapshot(Phase.VERIFY, started.alias(), 0, started.totalBytes(), now), now);
      nextPlainVerificationPercentage = 25;
      if (mode == Mode.PLAIN) {
        fallback.println("Verifying SHA-256 for " + started.alias());
      } else {
        renderNow();
      }
    } else if (progress instanceof ModelInstallProgress.VerificationAdvanced advanced) {
      advance(
          Phase.VERIFY, advanced.alias(), advanced.completedBytes(), advanced.totalBytes(), now);
      if (mode == Mode.PLAIN) {
        nextPlainVerificationPercentage =
            printPlainProgress("Verify", advanced, nextPlainVerificationPercentage);
      }
    } else if (progress instanceof ModelInstallProgress.Completed completed) {
      completionSource = completed.source();
      operationCompletedNanos = now;
      snapshot =
          new Snapshot(
              Phase.VERIFY, completed.alias(), completed.totalBytes(), completed.totalBytes(), now);
      if (mode == Mode.BAR) {
        renderNow();
        clearDisplay();
      }
    }
  }

  double elapsedSeconds() {
    long started = operationStartedNanos;
    if (started < 0) {
      return 0;
    }
    long completed = operationCompletedNanos >= 0 ? operationCompletedNanos : ticker.getAsLong();
    return Math.max(0, completed - started) / 1_000_000_000.0;
  }

  ModelInstallProgress.Source completionSource() {
    return completionSource;
  }

  void renderNow() {
    if (mode != Mode.BAR || snapshot == null || closed) {
      return;
    }
    synchronized (displayLock) {
      Snapshot current = snapshot;
      int width = terminal.getWidth() > 0 ? terminal.getWidth() : configuredWidth;
      int height = terminal.getHeight() > 0 ? terminal.getHeight() : 24;
      display.resize(height, width);
      display.updateAnsi(lines(current, width, ticker.getAsLong()), 0);
      terminal.flush();
      rendered = true;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (animator != null) {
      animator.shutdownNow();
    }
    clearDisplay();
  }

  private void beginPhase(Snapshot next, long now) {
    snapshot = next;
    lastSampleNanos = now;
    lastSampleBytes = next.completedBytes();
    bytesPerSecond = 0;
  }

  private void advance(Phase phase, String alias, long completed, long total, long now) {
    Snapshot current = snapshot;
    if (current == null || current.phase() != phase) {
      beginPhase(new Snapshot(phase, alias, completed, total, now), now);
      return;
    }
    long elapsed = now - lastSampleNanos;
    long bytes = completed - lastSampleBytes;
    if (elapsed > 0 && bytes >= 0) {
      double sample = bytes * 1_000_000_000.0 / elapsed;
      bytesPerSecond = bytesPerSecond == 0 ? sample : bytesPerSecond * 0.8 + sample * 0.2;
    }
    lastSampleNanos = now;
    lastSampleBytes = completed;
    snapshot = new Snapshot(phase, alias, completed, total, current.phaseStartedNanos());
  }

  private int printPlainProgress(
      String label, ModelInstallProgress.DownloadAdvanced progress, int nextPercentage) {
    int percentage = percentage(progress.completedBytes(), progress.totalBytes());
    if (percentage >= nextPercentage) {
      fallback.printf(
          Locale.ROOT,
          "  %s %d%% · %s / %s%n",
          label,
          percentage,
          CliOutput.humanBytes(progress.completedBytes()),
          CliOutput.humanBytes(progress.totalBytes()));
      return percentage / 25 * 25 + 25;
    }
    return nextPercentage;
  }

  private int printPlainProgress(
      String label, ModelInstallProgress.VerificationAdvanced progress, int nextPercentage) {
    int percentage = percentage(progress.completedBytes(), progress.totalBytes());
    if (percentage >= nextPercentage) {
      fallback.printf(
          Locale.ROOT,
          "  %s %d%% · %s / %s%n",
          label,
          percentage,
          CliOutput.humanBytes(progress.completedBytes()),
          CliOutput.humanBytes(progress.totalBytes()));
      return percentage / 25 * 25 + 25;
    }
    return nextPercentage;
  }

  private List<String> lines(Snapshot current, int width, long now) {
    String[] frames = unicode ? UNICODE_SPINNER : ASCII_SPINNER;
    int frame = Math.floorMod((now - current.phaseStartedNanos()) / FRAME_NANOS, frames.length);
    String verb = current.phase() == Phase.DOWNLOAD ? "Downloading" : "Verifying";
    String spinner = paint(CYAN, frames[frame]);
    String alias = truncate(current.alias(), Math.max(8, width - verb.length() - 5));
    String heading = "  " + spinner + " " + paint(CYAN, verb) + "  " + alias;

    int percent = percentage(current.completedBytes(), current.totalBytes());
    String amounts =
        CliOutput.humanBytes(current.completedBytes())
            + "/"
            + CliOutput.humanBytes(current.totalBytes());
    String speed = bytesPerSecond > 0 ? CliOutput.humanBytes((long) bytesPerSecond) + "/s" : "—";
    String eta = eta(current);
    String suffix = String.format(Locale.ROOT, "%3d%%  %s", percent, amounts);
    if (width >= 72) {
      suffix += "  " + speed;
      if (!eta.isEmpty()) {
        suffix += "  ETA " + eta;
      }
    }

    int barWidth = Math.min(32, width - suffix.length() - 7);
    String detail;
    if (barWidth >= 10) {
      int filled = (int) Math.round(barWidth * percent / 100.0);
      String full = unicode ? "█" : "#";
      String empty = unicode ? "░" : "-";
      String bar =
          full.repeat(Math.min(barWidth, filled)) + empty.repeat(Math.max(0, barWidth - filled));
      detail = "    " + paint(GREEN, bar) + "  " + paint(DIM, suffix);
    } else {
      detail = "    " + paint(DIM, suffix);
    }
    return List.of(heading, detail);
  }

  private String eta(Snapshot current) {
    if (current.phase() != Phase.DOWNLOAD || bytesPerSecond <= 0) {
      return "";
    }
    long remaining = Math.max(0, current.totalBytes() - current.completedBytes());
    return formatSeconds((long) Math.ceil(remaining / bytesPerSecond));
  }

  private void printAbove(String warning) {
    synchronized (displayLock) {
      clearDisplayLocked();
      terminal.writer().println(paint(YELLOW, "⚠ " + warning));
      terminal.flush();
      display.reset();
      rendered = false;
    }
  }

  private void clearDisplay() {
    if (mode != Mode.BAR) {
      return;
    }
    synchronized (displayLock) {
      clearDisplayLocked();
    }
  }

  private void clearDisplayLocked() {
    if (rendered) {
      display.updateAnsi(List.of(), 0);
      terminal.flush();
      display.reset();
      rendered = false;
    }
  }

  private void renderSafely() {
    try {
      renderNow();
    } catch (RuntimeException ignored) {
      // A terminal resize or shutdown must not interrupt a model download.
    }
  }

  private String paint(String ansi, String value) {
    return color ? ansi + value + RESET : value;
  }

  private static ScheduledExecutorService animator() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread thread = new Thread(task, "modeljars-pull-progress");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static Mode effectiveMode(Mode requested, Terminal terminal) {
    boolean capable =
        terminal != null
            && terminal.getType() != null
            && !terminal.getType().toLowerCase(Locale.ROOT).startsWith("dumb");
    if (requested == Mode.AUTO) {
      return capable ? Mode.BAR : Mode.PLAIN;
    }
    return requested == Mode.BAR && !capable ? Mode.PLAIN : requested;
  }

  private static boolean supportsUnicode(Terminal terminal) {
    return terminal != null && terminal.encoding().newEncoder().canEncode("⠹█░");
  }

  private static int percentage(long completed, long total) {
    return total == 0 ? 100 : (int) Math.min(100, completed * 100.0 / total);
  }

  private static String truncate(String value, int width) {
    if (value.length() <= width) {
      return value;
    }
    return width <= 1 ? "…" : value.substring(0, width - 1) + "…";
  }

  private static String conciseReason(String reason) {
    String singleLine = reason.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    return truncate(singleLine, 96);
  }

  private static String formatDelay(long millis) {
    if (millis < 1000) {
      return millis + "ms";
    }
    return formatSeconds((long) Math.ceil(millis / 1000.0));
  }

  private static String formatSeconds(long seconds) {
    if (seconds < 60) {
      return seconds + "s";
    }
    return (seconds / 60) + "m " + String.format(Locale.ROOT, "%02ds", seconds % 60);
  }

  private record Snapshot(
      Phase phase, String alias, long completedBytes, long totalBytes, long phaseStartedNanos) {}
}
