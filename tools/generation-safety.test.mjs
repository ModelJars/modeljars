import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  GENERATION_SAFETY_SCHEMA_VERSION,
  REPETITION_LOOP_METHOD,
  validateGenerationSafety,
} from "./generation-safety.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = async (relativePath) =>
  JSON.parse(await readFile(path.join(repositoryRoot, relativePath), "utf8"));

const GEMMA4 = "ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m";

async function fixtures() {
  const catalog = await read("catalog/models.json");
  const profiles = await read("catalog/model-profiles.json");
  const gemma4 = catalog.models.find((model) => model.id === GEMMA4);
  // Gemma 4 26B's profile publishes temperature 1, top-p 0.95, top-k 64 (generation_config.json).
  const measurement = {
    modelId: GEMMA4,
    artifactSha256: gemma4.sha256,
    backend: "pure-java",
    modelsVersion: "0.3.41",
    modelsRevision: "a".repeat(40),
    workload: "general",
    sampling: { temperature: 1, topP: 0.95, topK: 64 },
    detector: { maxSpan: 32, minRepeats: 4, minLoopTokens: 16 },
    generations: 27,
    stops: 3,
    stopRate: 3 / 27,
    report: "benchmark-results/generation-safety/gemma4.json",
    reportSha256: "b".repeat(64),
  };
  const document = (entries) => ({
    schemaVersion: GENERATION_SAFETY_SCHEMA_VERSION,
    generatedAt: "2026-09-16T00:00:00Z",
    repetitionLoopMethod: REPETITION_LOOP_METHOD,
    entries,
  });
  return { catalog, profiles, measurement, document };
}

test("the committed manifest carries the documented method and no measured values yet", async () => {
  const { catalog, profiles } = await fixtures();
  const committed = await read("catalog/generation-safety.json");
  assert.equal(validateGenerationSafety(committed, catalog, profiles), true);
  // Absent by default: values come only from measured runs, none of which has been recorded.
  assert.deepEqual(committed.entries, []);
  assert.match(REPETITION_LOOP_METHOD.status, /measured/);
  assert.match(REPETITION_LOOP_METHOD.counter, /repetitionLoopStops\(\)/);
});

test("accepts a measurement taken at the model's documented generation profile", async () => {
  const { catalog, profiles, measurement, document } = await fixtures();
  assert.equal(validateGenerationSafety(document([measurement]), catalog, profiles), true);
});

test("rejects a measurement whose sampling differs from the documented profile", async () => {
  const { catalog, profiles, measurement, document } = await fixtures();
  const greedy = { ...measurement, sampling: { ...measurement.sampling, temperature: 0 } };
  assert.throws(
    () => validateGenerationSafety(document([greedy]), catalog, profiles),
    /temperature 0 is not the documented 1/,
  );
  const missing = { ...measurement, sampling: { temperature: 1, topP: 0.95 } };
  assert.throws(
    () => validateGenerationSafety(document([missing]), catalog, profiles),
    /topK is documented as 64 but was not applied/,
  );
});

test("rejects a measurement for a model whose profile documents no sampling", async () => {
  const { catalog, profiles, measurement, document } = await fixtures();
  const qwen = catalog.models.find((model) => model.id === "qwen3_8b_q4_k_m");
  const entry = { ...measurement, modelId: qwen.id, artifactSha256: qwen.sha256 };
  assert.throws(
    () => validateGenerationSafety(document([entry]), catalog, profiles),
    /documents no sampling values/,
  );
});

test("rejects inconsistent counts, a disabled detector, and a pre-detector Models version", async () => {
  const { catalog, profiles, measurement, document } = await fixtures();
  const invalid = [
    [{ stopRate: 0.5 }, /stopRate must equal stops \/ generations/],
    [{ stops: 28 }, /stops must be an integer from 0 to generations/],
    [{ generations: 0 }, /generations must be a positive integer/],
    [{ detector: { maxSpan: 0, minRepeats: 0, minLoopTokens: 0 } }, /detector must be enabled/],
    [{ modelsVersion: "0.3.40" }, /Models 0\.3\.41 or later/],
    [{ artifactSha256: "c".repeat(64) }, /artifactSha256 does not match/],
    [{ reportSha256: "nope" }, /reportSha256/],
  ];
  for (const [change, pattern] of invalid) {
    assert.throws(
      () => validateGenerationSafety(document([{ ...measurement, ...change }]), catalog, profiles),
      pattern,
      JSON.stringify(change),
    );
  }
  assert.throws(
    () => validateGenerationSafety(document([measurement, measurement]), catalog, profiles),
    /duplicated/,
  );
  assert.throws(
    () =>
      validateGenerationSafety(
        { ...document([]), repetitionLoopMethod: { status: "edited" } },
        catalog,
        profiles,
      ),
    /repetitionLoopMethod does not match/,
  );
});

test("generation-safety measurements stay outside every marker identity input", async () => {
  const planner = await readFile(path.join(repositoryRoot, "tools/model-publications.mjs"), "utf8");
  const workflow = await readFile(
    path.join(repositoryRoot, ".github/workflows/model-artifacts.yml"),
    "utf8",
  );
  assert.doesNotMatch(planner, /generation-safety/);
  assert.doesNotMatch(workflow, /generation-safety/);
});
