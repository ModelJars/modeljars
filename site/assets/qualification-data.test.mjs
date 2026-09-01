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
  workload: "general",
  corpusSha256: "d".repeat(64),
  promptTemplate: "chatml",
  groundingPolicy: "trusted-provenance-clause-anchors-extractive-fallback-v4",
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
  modelAnswerCorrectRate: 1,
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
  policyVersion: "production-rag-model-contribution-v5",
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
  assert.equal(validated.entries[0].workload, "general");
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
  assert.throws(
    () =>
      validateQualificationCatalog(
        {
          ...document,
          entries: [
            {
              ...qualification,
              modelAnswerRate: 0,
              modelAnswerCorrectRate: 0,
            },
          ],
        },
        [model],
      ),
    /model-answer contribution policy/,
  );
  assert.throws(
    () =>
      validateQualificationCatalog(
        {
          ...document,
          entries: [{ ...qualification, workload: undefined }],
        },
        [model],
      ),
    /workload/,
  );
});

test("treats embedding equivalence evidence as a qualification", () => {
  const embedder = {
    id: "qwen_qwen3_embedding_0_6b_gguf_q8_0",
    embeddingQualifications: [
      { qualified: true, useCaseTier: "SEMANTIC_SEARCH", minimumOracleCosine: 0.9995 },
    ],
  };

  const qualification = primaryQualification(embedder);

  assert.equal(qualification.useCaseTier, "SEMANTIC_SEARCH");
  assert.equal(qualificationLabel(qualification), "Semantic search");
});

test("selects and labels tool-calling conformance evidence", () => {
  const toolModel = {
    id: "cactus_compute_needle2_cact_cq2_mixed",
    toolQualifications: [
      {
        qualified: true,
        useCaseTier: "TOOL_CALLING",
        expectedArgumentAccuracy: 0.9189189189,
      },
    ],
  };

  const selected = primaryQualification(toolModel);

  assert.equal(selected.useCaseTier, "TOOL_CALLING");
  assert.equal(qualificationLabel(selected), "Tool calling");
});

test("selects and labels reranking evidence", () => {
  const model = {
    rerankingQualifications: [
      {
        qualified: true,
        useCaseTier: "SECOND_STAGE_RERANKING",
        maximumSameArtifactOracleLogitDelta: 0.036392,
      },
    ],
  };

  const selected = primaryQualification(model);

  assert.equal(selected.useCaseTier, "SECOND_STAGE_RERANKING");
  assert.equal(qualificationLabel(selected), "Second-stage reranking");
});

test("normalizes the published nested tool-calling evidence shape", () => {
  const toolModel = {
    id: "cactus_compute_needle2_cact_cq2_mixed",
    toolQualifications: [
      {
        backend: "pure-java",
        suite: { id: "needle2-upstream-playground-v1" },
        generation: { promptTemplate: "needle2" },
        summary: {
          qualified: true,
          verdict: "PASS",
          attempts: 14,
          passed: 12,
          structuredOutputRate: 1,
          toolSelectionExactRate: 1,
          expectedArgumentAccuracy: 0.9189189189,
        },
        useCaseTier: "TOOL_CALLING",
      },
    ],
  };

  const selected = primaryQualification(toolModel);

  assert.equal(selected.qualified, true);
  assert.equal(selected.workload, "needle2-upstream-playground-v1");
  assert.equal(selected.promptTemplate, "needle2");
  assert.equal(selected.structuredOutputRate, 1);
  assert.equal(selected.expectedArgumentAccuracy, 0.9189189189);
  assert.equal(qualificationLabel(selected), "Tool calling");
});

test("prefers RAG evidence when a model carries both", () => {
  const both = {
    ragQualifications: [{ qualified: true, useCaseTier: "GUARDED_RAG" }],
    embeddingQualifications: [{ qualified: true, useCaseTier: "SEMANTIC_SEARCH" }],
  };

  assert.equal(primaryQualification(both).useCaseTier, "GUARDED_RAG");
});

test("reports a model with no evidence of either kind as unevaluated", () => {
  assert.equal(primaryQualification({ id: "bare" }), null);
  assert.equal(qualificationLabel(null), "Not evaluated");
});
