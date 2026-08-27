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
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelInstallProgress;

class PullProgressRendererTest {

  @Test
  void rendersStableProgressWithoutControlCharactersWhenNoTerminalIsAvailable() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AtomicLong clock = new AtomicLong();
    try (var progress =
        new PullProgressRenderer(
            PullProgressRenderer.Mode.AUTO,
            null,
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            false,
            80,
            clock::get)) {
      progress.accept(downloadStarted(400));
      clock.addAndGet(1_000_000_000L);
      progress.accept(new ModelInstallProgress.DownloadAdvanced("example", 100, 400));
      progress.accept(
          new ModelInstallProgress.VerificationStarted(
              "example", Path.of("model.gguf"), 400, ModelInstallProgress.Source.DOWNLOAD));
      clock.addAndGet(1_000_000_000L);
      progress.accept(new ModelInstallProgress.VerificationAdvanced("example", 400, 400));
      progress.accept(
          new ModelInstallProgress.Completed(
              "example", Path.of("model.gguf"), 400, ModelInstallProgress.Source.DOWNLOAD));
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Downloading example (400 B)"), output);
    assertTrue(output.contains("Download 25% · 100 B / 400 B"), output);
    assertTrue(output.contains("Verifying SHA-256 for example"), output);
    assertTrue(output.contains("Verify 100% · 400 B / 400 B"), output);
    assertFalse(output.contains("\u001B["), output);
  }

  @Test
  void rendersAColorizedAnimatedLiveRegionOnACapableTerminal() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AtomicLong clock = new AtomicLong();
    try (var terminal = terminal(bytes, 100, "xterm-256color");
        var progress =
            new PullProgressRenderer(
                PullProgressRenderer.Mode.BAR,
                terminal,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                true,
                100,
                clock::get)) {
      progress.accept(downloadStarted(400));
      clock.addAndGet(1_000_000_000L);
      progress.accept(new ModelInstallProgress.DownloadAdvanced("example", 200, 400));
      progress.renderNow();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Downloading"), output);
    assertTrue(output.contains("example"), output);
    assertTrue(output.contains("50%"), output);
    assertTrue(output.contains("200 B/400 B"), output);
    assertTrue(output.contains("\u001B["), output);
  }

  @Test
  void showsRetriesWithoutLosingTheLiveDownloadState() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AtomicLong clock = new AtomicLong();
    try (var terminal = terminal(bytes, 80, "xterm");
        var progress =
            new PullProgressRenderer(
                PullProgressRenderer.Mode.BAR,
                terminal,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                false,
                80,
                clock::get)) {
      progress.accept(downloadStarted(400));
      progress.accept(
          new ModelInstallProgress.Retrying("example", 200, 400, 2, 5, 2_000, "connection reset"));
      clock.addAndGet(1_000_000_000L);
      progress.accept(new ModelInstallProgress.DownloadAdvanced("example", 300, 400));
      progress.renderNow();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(
        output.contains("Download interrupted at 200 B: connection reset; retrying in 2s (2/5)"),
        output);
    assertTrue(output.contains("75%"), output);
  }

  @Test
  void tracksCompletionWithoutRenderingWhenProgressIsOff() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AtomicLong clock = new AtomicLong();
    PullProgressRenderer progress =
        new PullProgressRenderer(
            PullProgressRenderer.Mode.OFF,
            null,
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            false,
            80,
            clock::get);

    progress.accept(downloadStarted(400));
    clock.addAndGet(1_000_000_000L);
    progress.accept(
        new ModelInstallProgress.Completed(
            "example", Path.of("model.gguf"), 400, ModelInstallProgress.Source.CACHE));

    assertTrue(bytes.toString(StandardCharsets.UTF_8).isEmpty());
    assertEquals(1.0, progress.elapsedSeconds());
    assertEquals(ModelInstallProgress.Source.CACHE, progress.completionSource());
    progress.close();
  }

  private static ModelInstallProgress.DownloadStarted downloadStarted(long totalBytes) {
    return new ModelInstallProgress.DownloadStarted(
        "example",
        URI.create("https://example.test/model.gguf"),
        Path.of("model.gguf"),
        0,
        totalBytes);
  }

  private static DumbTerminal terminal(ByteArrayOutputStream output, int width, String type)
      throws Exception {
    DumbTerminal terminal =
        new DumbTerminal(
            "progress-test",
            type,
            new ByteArrayInputStream(new byte[0]),
            output,
            StandardCharsets.UTF_8);
    terminal.setSize(new Size(width, 24));
    return terminal;
  }
}
