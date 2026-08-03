import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { validateQualificationSmokeGate } from "./qualification-smoke-gate.mjs";

const modelId = "gemma4_26b_q4_k_m";
const backend = "rust-ffm";
const artifactSha256 = "a".repeat(64);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function catalog() {
  return { schemaVersion: 2, models: [{ id: modelId }] };
}

function qualifications(entries) {
  return { schemaVersion: 1, modelsRevision: "b".repeat(40), entries };
}

function entry(overrides = {}) {
  return {
    modelId,
    backend,
    backendVersion: "models@revision",
    artifactSha256,
    artifactSizeBytes: 42,
    workload: "general",
    promptTemplate: "gemma4",
    report: "benchmark-results/tuned.json",
    reportSha256: "c".repeat(64),
    verdict: "QUALIFIED",
    qualified: true,
    ...overrides,
  };
}

function defaultReport(overrides = {}) {
  return {
    backend,
    modelId,
    artifactSha256,
    settings: {
      warmups: 0,
      iterations: 1,
      generationControls: { promptCache: "longest-common-prefix" },
    },
    summary: {
      totalAttempts: 9,
      successfulAttempts: 9,
      correctAnswerRate: 1,
      abstentionAccuracy: 1,
    },
    failures: [],
    backendDiagnostics: {
      environment: { "native-quantized-decode": "false" },
    },
    ...overrides,
  };
}

function withSmoke(qualification, report) {
  const bytes = Buffer.from(JSON.stringify(report));
  return {
    bytes,
    qualification: {
      ...qualification,
      defaultConfigurationSmoke: {
        configuration: "library-defaults",
        backend,
        artifactSha256,
        report: "benchmark-results/run/default-correctness/models-rust-ffm.json",
        reportSha256: createHash("sha256").update(bytes).digest("hex"),
        totalAttempts: 9,
        successfulAttempts: 9,
        tuningSystemProperties: [],
      },
    },
  };
}

test("grandfathers unchanged qualification evidence", async () => {
  const current = entry();
  const checked = await validateQualificationSmokeGate({
    previousQualifications: qualifications([current]),
    currentQualifications: qualifications([current]),
    currentCatalog: catalog(),
  });
  assert.deepEqual(checked, []);
});

test("validates newly added default smoke even when tuned evidence is unchanged", async () => {
  const report = defaultReport();
  const { qualification, bytes } = withSmoke(entry(), report);
  const checked = await validateQualificationSmokeGate({
    previousQualifications: qualifications([entry()]),
    currentQualifications: qualifications([qualification]),
    currentCatalog: catalog(),
    loadReport: async () => bytes,
  });
  assert.deepEqual(checked, [`${modelId}/${backend}`]);
});

test("requires default smoke for a newly qualified model/backend pair", async () => {
  await assert.rejects(
    validateQualificationSmokeGate({
      previousQualifications: qualifications([]),
      currentQualifications: qualifications([entry()]),
      currentCatalog: catalog(),
    }),
    /defaultConfigurationSmoke is required/,
  );
});

test("accepts immutable cached RAG evidence produced with library defaults", async () => {
  const report = defaultReport();
  const { qualification, bytes } = withSmoke(entry(), report);
  const checked = await validateQualificationSmokeGate({
    previousQualifications: qualifications([]),
    currentQualifications: qualifications([qualification]),
    currentCatalog: catalog(),
    loadReport: async () => bytes,
  });
  assert.deepEqual(checked, [`${modelId}/${backend}`]);
});

test("rejects smoke evidence that enabled the tuned native decode path", async () => {
  const report = defaultReport({
    backendDiagnostics: {
      environment: { "native-quantized-decode": "true" },
    },
  });
  const { qualification, bytes } = withSmoke(entry(), report);
  await assert.rejects(
    validateQualificationSmokeGate({
      previousQualifications: qualifications([]),
      currentQualifications: qualifications([qualification]),
      currentCatalog: catalog(),
      loadReport: async () => bytes,
    }),
    /enabled models.native.quantizedDecode/,
  );
});

test("rejects a default run with a generation failure", async () => {
  const report = defaultReport({
    summary: {
      totalAttempts: 9,
      successfulAttempts: 8,
      correctAnswerRate: 1,
      abstentionAccuracy: 1,
    },
    failures: [{ message: "batched-prefill kernel rejected projection" }],
  });
  const { qualification, bytes } = withSmoke(entry(), report);
  await assert.rejects(
    validateQualificationSmokeGate({
      previousQualifications: qualifications([]),
      currentQualifications: qualifications([qualification]),
      currentCatalog: catalog(),
      loadReport: async () => bytes,
    }),
    /pass every attempt/,
  );
});

test("runs the smoke gate before planning changed model publications", async () => {
  const workflow = await readFile(
    path.join(repositoryRoot, ".github/workflows/model-artifacts.yml"),
    "utf8",
  );
  const gate = workflow.indexOf("node tools/qualification-smoke-gate.mjs");
  const plan = workflow.indexOf("node tools/plan-model-publications.mjs", gate);
  assert.ok(gate > 0);
  assert.ok(plan > gate);
  assert.match(workflow, /--verify-remote/);
  assert.match(workflow, /catalog\/qualifications\.json/);
});
