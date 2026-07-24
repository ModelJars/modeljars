import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  buildBenchmarkSummary,
  buildInferenceRows,
  buildRagRows,
} from "./benchmark-view.js";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

async function json(relativePath) {
  return JSON.parse(await readFile(path.join(repositoryRoot, relativePath), "utf8"));
}

test("joins inference evidence to catalog names and preserves qualification status", async () => {
  const [benchmarks, catalog] = await Promise.all([
    json("catalog/benchmarks.json"),
    json("catalog/models.json"),
  ]);

  const rows = buildInferenceRows(benchmarks, catalog.models);

  assert.equal(rows.length, 5);
  assert.equal(rows[0].modelName, "Qwen3 0.6B GGUF Q4_0");
  assert.equal(rows[0].status, "Profiled");
  assert.equal(rows[0].decodeVsLlamaCpp, "59.4%");
  assert.equal(rows[0].decodeVsOllama, "125.7%");
  assert.equal(rows[3].status, "Validated candidate");
  assert.match(rows[3].evidence.url, /\/blob\/[a-f0-9]{40}\//);
  assert.match(rows[3].evidence.sha256, /^[a-f0-9]{64}$/);
  assert.equal(rows[4].modelName, "SQLCoder-7B-2 GGUF Q5_K_M");
  assert.equal(rows[4].modelsBackend, "Models Rust/FFM");
  assert.equal(rows[4].decodeVsLlamaCpp, "92.8%");
  assert.equal(rows[4].decodeVsOllama, "93.1%");
});

test("summarizes prompt and decode ratios from every measured model", async () => {
  const benchmarks = await json("catalog/benchmarks.json");

  assert.deepEqual(buildBenchmarkSummary(benchmarks), {
    modelCount: 5,
    prefillVsLlamaCpp: "38.9%",
    prefillVsOllama: "37.7%",
    decodeVsLlamaCpp: "58.8%",
    decodeVsOllama: "92.4%",
  });
});

test("keeps local privacy and hosted API costs explicit in RAG rows", async () => {
  const benchmarks = await json("catalog/benchmarks.json");

  const rows = buildRagRows(benchmarks);
  const local = rows.find((row) => row.id === "qwen3-1.7b-pure-java");
  const openAi = rows.find((row) => row.id === "openai-gpt-5.4-nano");

  assert.equal(local.execution, "Local in-process");
  assert.equal(local.dataEgress, "No");
  assert.equal(local.cost, "Local compute");
  assert.equal(openAi.execution, "Hosted API");
  assert.equal(openAi.dataEgress, "Yes");
  assert.equal(openAi.cost, "$0.0724");
  assert.equal(openAi.strictQuality, "100.0%");
  assert.equal(openAi.auditedSemanticQuality, "100.0%");
});
