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
  assert.equal(validated.ragComparison.rows.length, 7);
  assert.deepEqual(
    validated.ragComparison.rows.find((row) => row.id === "smollm2-360m-rust-ffm"),
    {
      id: "smollm2-360m-rust-ffm",
      catalogModelId: "smollm2_360m_instruct_q8_0",
      execution: "local-in-process",
      engine: "rust-ffm",
      model: "SmolLM2 360M Instruct Q8_0",
      p95RetrievalMillis: 1.7501794,
      p95TtftMillis: 552.8256513,
      p95TpotMillis: 23.125619260465115,
      p95EndToEndMillis: 1934.9650668,
      decodeTokensPerSecond: 43.91685822892476,
      strictQuality: 0.6666666666666666,
      auditedSemanticQuality: 1,
      dataEgress: false,
      apiCostPer1kUsd: null,
      evidence: {
        url: "https://github.com/integrallis/models/blob/a25820ad842f347b5193433de87e12d86558e72e/benchmark-results/certified-20260724/rag/native-q8-prefix-cache/smollm2-360m-q8_0/smollm2-360m-rust-ffm-prefix-cache-grounded.json",
        sha256: "879f95209b4a25a3047affe492b679e56addf7bf361baaa28643879d525178b3",
      },
    },
  );
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
