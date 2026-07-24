import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  decodeRatio,
  formatCostPer1k,
  formatDuration,
  validateBenchmarkCatalog,
} from "./benchmark-data.js";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

async function json(relativePath) {
  return JSON.parse(await readFile(path.join(repositoryRoot, relativePath), "utf8"));
}

test("benchmark metadata joins only known catalog models and carries all engine controls", async () => {
  const [benchmarks, catalog] = await Promise.all([
    json("catalog/benchmarks.json"),
    json("catalog/models.json"),
  ]);

  const validated = validateBenchmarkCatalog(benchmarks, catalog.models);

  assert.equal(validated.inferenceComparisons.length, 4);
  assert.equal(validated.ragComparison.rows.length, 6);
  for (const comparison of validated.inferenceComparisons) {
    assert.deepEqual(Object.keys(comparison.engines).sort(), [
      "llama.cpp",
      "ollama",
      "pure-java",
    ]);
  }
});

test("derives model-specific Java ratios instead of storing presentation values", async () => {
  const benchmarks = await json("catalog/benchmarks.json");
  const qwen = benchmarks.inferenceComparisons.find(
    (comparison) => comparison.modelId === "qwen3_0_6b_q4_0",
  );

  assert.equal(decodeRatio(qwen, "llama.cpp").toFixed(1), "59.4");
  assert.equal(decodeRatio(qwen, "ollama").toFixed(1), "125.7");
});

test("formats developer-facing latency and API cost without hiding null local cost", () => {
  assert.equal(formatDuration(440.936), "441 ms");
  assert.equal(formatDuration(5347.006), "5.35 s");
  assert.equal(formatCostPer1k(null), "Local compute");
  assert.equal(formatCostPer1k(0.072389), "$0.0724");
});
