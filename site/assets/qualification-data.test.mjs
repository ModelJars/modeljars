import assert from "node:assert/strict";
import test from "node:test";

import {
  buildQualificationRows,
  primaryQualification,
  qualificationLabel,
  validateQualificationCatalog,
} from "./qualification-data.js";

const sha = "a".repeat(64);
const reportSha = "b".repeat(64);
const revision = "c".repeat(40);
const model = {
  id: "qwen3_0_6b_q4_0",
  name: "Qwen3 0.6B Q4_0",
  sha256: sha,
  sizeBytes: 429496729,
  ragQualifications: [],
};
const qualification = {
  modelId: model.id,
  model: model.name,
  backend: "llama.cpp",
  backendVersion: "b10012-c71854292",
  artifactSha256: sha,
  artifactSizeBytes: model.sizeBytes,
  report: "benchmark-results/certified-20260724/rag/launch-campaign-v2/qwen.json",
  reportSha256: reportSha,
  performanceTier: "PRODUCTION_READY",
  verdict: "QUALIFIED",
  qualified: true,
  attempts: 27,
  p95RetrievalMillis: 4,
  p95TtftMillis: 364.905,
  p95TpotMillis: 13.338,
  p95EndToEndMillis: 860.1,
  p50PrefillTokensPerSecond: 458.32,
  p50DecodeTokensPerSecond: 101.569,
  peakRssBytes: 1275252736,
  correctAnswerRate: 1,
  rawCorrectAnswerRate: 1,
  abstentionAccuracy: 1,
  modelAnswerRate: 1,
  extractiveFallbackRate: 0,
  environment: {
    hostname: "qualification-host",
    osName: "Linux",
    osVersion: "6.8",
    architecture: "amd64",
    cpuModel: "AMD EPYC Milan",
    availableProcessors: 8,
    totalMemoryBytes: 32857444352,
    maxHeapBytes: 8589934592,
    javaVersion: "25.0.3",
    javaVendor: "Eclipse Adoptium",
    vmName: "OpenJDK 64-Bit Server VM",
  },
};
const document = {
  schemaVersion: 1,
  generatedAt: "2026-07-24T06:00:00Z",
  policyVersion: "trusted-citation-lexical-entailment-extractive-fallback-v2",
  modelsRevision: revision,
  targetQualifiedModels: 25,
  qualifiedModels: 1,
  rejectedModels: 0,
  entries: [qualification],
};

test("validates immutable qualification evidence and joins exact artifacts", () => {
  const validated = validateQualificationCatalog(document, [model]);
  assert.equal(validated.entries.length, 1);
  assert.equal(validated.entries[0].reportUri,
    `https://github.com/integrallis/models/blob/${revision}/${qualification.report}`);
  assert.equal(validated.entries[0].useCaseTier, "GENERATIVE_RAG");
});

test("builds transparent production RAG rows", () => {
  const rows = buildQualificationRows(validateQualificationCatalog(document, [model]), [model]);
  assert.equal(rows[0].modelName, model.name);
  assert.equal(rows[0].useCase, "Generative RAG");
  assert.equal(rows[0].ttft, "365 ms");
  assert.equal(rows[0].decode, "101.6 tok/s");
  assert.equal(rows[0].rawQuality, "100.0%");
  assert.equal(rows[0].fallbackRate, "0.0%");
  assert.equal(rows[0].evidence.sha256, reportSha);
});

test("selects and labels qualified embedded model evidence", () => {
  const embedded = {
    ...qualification,
    reportUri: `https://github.com/integrallis/models/blob/${revision}/${qualification.report}`,
    useCaseTier: "GUARDED_RAG",
  };
  const selected = primaryQualification({ ...model, ragQualifications: [embedded] });
  assert.equal(selected, embedded);
  assert.equal(qualificationLabel(selected), "Guarded RAG");
  assert.equal(primaryQualification(model), null);
});

test("rejects artifact mismatches and false qualification counts", () => {
  assert.throws(
    () =>
      validateQualificationCatalog(
        { ...document, entries: [{ ...qualification, artifactSha256: "d".repeat(64) }] },
        [model],
      ),
    /artifact SHA-256 mismatch/,
  );
  assert.throws(
    () => validateQualificationCatalog({ ...document, qualifiedModels: 0 }, [model]),
    /qualifiedModels/,
  );
});
