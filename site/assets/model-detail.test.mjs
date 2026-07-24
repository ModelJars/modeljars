import assert from "node:assert/strict";
import test from "node:test";

import {
  gradleSnippet,
  mavenSnippet,
  modelIdFromPath,
  qualificationSummary,
} from "./model-detail.js";

const coordinate = "org.modeljars.huggingface:qwen.qwen3.q4_k_m:3.0.0-q4_k_m.1";

test("extracts generated model route identifiers", () => {
  assert.equal(modelIdFromPath("/models/qwen3_8b_q4_k_m/"), "qwen3_8b_q4_k_m");
  assert.equal(modelIdFromPath("/models/qwen3_8b_q4_k_m/index.html"), "qwen3_8b_q4_k_m");
  assert.equal(modelIdFromPath("/plugins"), null);
});

test("renders build-tool snippets from marker coordinates", () => {
  assert.equal(
    gradleSnippet(coordinate),
    'runtimeOnly("org.modeljars.huggingface:qwen.qwen3.q4_k_m:3.0.0-q4_k_m.1")',
  );
  assert.match(mavenSnippet(coordinate), /<groupId>org\.modeljars\.huggingface<\/groupId>/);
  assert.match(mavenSnippet(coordinate), /<artifactId>qwen\.qwen3\.q4_k_m<\/artifactId>/);
  assert.match(mavenSnippet(coordinate), /<version>3\.0\.0-q4_k_m\.1<\/version>/);
});

test("summarizes exact RAG qualification evidence without hiding fallbacks", () => {
  const summary = qualificationSummary({
    qualified: true,
    useCaseTier: "GUARDED_RAG",
    backend: "llama.cpp",
    backendVersion: "b10012-c71854292",
    attempts: 27,
    p95TtftMillis: 640,
    p95TpotMillis: 20,
    p95EndToEndMillis: 1800,
    p50DecodeTokensPerSecond: 50,
    peakRssBytes: 1500000000,
    rawCorrectAnswerRate: 0.67,
    correctAnswerRate: 1,
    extractiveFallbackRate: 0.33,
    reportUri: "https://github.com/integrallis/models/blob/" + "a".repeat(40) + "/report.json",
    reportSha256: "b".repeat(64),
  });

  assert.equal(summary.label, "Guarded RAG");
  assert.equal(summary.rawQuality, "67.0%");
  assert.equal(summary.finalQuality, "100.0%");
  assert.equal(summary.fallbackRate, "33.0%");
  assert.equal(summary.decode, "50.0 tok/s");
  assert.equal(summary.evidenceSha256, "b".repeat(64));
});
