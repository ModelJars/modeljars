import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { PUBLISHER_RAG_POLICY, validatePublisherReference } from "./rag-publisher-reference-gate.mjs";

function fixture(change = () => {}) {
  // Synthetic receipt exercises the publication gate, never model qualification evidence.
  const modelId = "prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0";
  const artifactSha256 = "53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3";
  const candidate = {
    modelId, artifactSha256, artifactSizeBytes: 5946648928, backend: "rust-ffm", backendVersion: "models@test",
    failures: [], environment: { hostname: "same-host", osName: "Linux", architecture: "amd64", cpuModel: "test", availableProcessors: 16, totalMemoryBytes: 64 },
    settings: { workload: "general", corpusSha256: "b".repeat(64), caseIds: ["test"], promptTemplate: "chatml-no-think", retrievalTopK: 1, maxOutputTokens: 64, contextLength: 2048, threads: 16, groundingPolicy: "test", minimumRetrievalScore: 2,
      generationControls: { temperature: 0, topK: 1, topP: 1, seed: 42, repetitionPenalty: 1, stopSequences: [] } },
    summary: { totalAttempts: 27, successfulAttempts: 27, correctAnswerRate: 1, abstentionAccuracy: 1, p50DecodeTokensPerSecond: 80, endToEndMillis: {p95:150} },
    runs: Array.from({length:27}, (_, i) => ({caseId:"test", promptSha256:"c".repeat(64), grounding:{decision:i<9?"MODEL_ANSWER":"EXTRACTIVE_FALLBACK"}, evaluation:{correct:true}})),
  };
  const comparator = structuredClone(candidate);
  comparator.backend = "llama.cpp";
  comparator.backendVersion = "PrismML-Eng/llama.cpp@e8fd209c975c80aa7fb36a8423571ff3b1b7e955";
  comparator.summary.p50DecodeTokensPerSecond = 100;
  comparator.summary.endToEndMillis.p95 = 100;
  const qualification = { qualified:true, verdict:"QUALIFIED", comparisons:[{comparatorBackend:"llama.cpp", qualified:true, minimumDecodeThroughputRatio:.8, maximumEndToEndLatencyRatio:1.5, decodeThroughputRatio:.8, endToEndLatencyRatio:1.5}] };
  change(candidate, comparator, qualification);
  const files = new Map();
  const store = (path, report) => {
    const bytes = Buffer.from(JSON.stringify(report)); files.set(path, bytes);
    return {path, sha256:createHash("sha256").update(bytes).digest("hex")};
  };
  const input = store("evidence/models.json",candidate), reference = store("evidence/publisher.json",comparator);
  const verdict = store("evidence/verdict.json",{schemaVersion:1, policyId:PUBLISHER_RAG_POLICY, candidate:input, comparators:[reference], qualification});
  return {entry:{modelId, artifactSha256, artifactSizeBytes:5946648928, backend:"rust-ffm", backendVersion:"models@test", policyVersion:PUBLISHER_RAG_POLICY, report:input.path, reportSha256:input.sha256, qualificationEvidence:{report:verdict.path,reportSha256:verdict.sha256}},
    loadReport:async ({report}) => files.get(report)};
}

test("publisher admission checks the hashed verdict and both raw reports at exact thresholds", async () => {
  const f=fixture(); await validatePublisherReference(f.entry,"d".repeat(40),f.loadReport);
});

for (const [name, change, message] of [
  ["unpinned reference",(c,r)=>{r.backendVersion="upstream"},/unpinned/],
  ["different host",(c,r)=>{r.environment.hostname="other"},/host differs/],
  ["different generation",(c,r)=>{r.settings.generationControls.seed=9},/generation differs/],
  ["different prompt",(c,r)=>{r.runs[0].promptSha256="e".repeat(64)},/prompt coverage/],
  ["incomplete attempts",c=>{c.runs.pop()},/incomplete/],
  ["fallback-only quality",c=>{c.runs.forEach(r=>r.grounding.decision="EXTRACTIVE_FALLBACK")},/contribution/],
  ["incorrect model answers",c=>{c.runs[0].evaluation.correct=false},/contribution/],
  ["failed verdict",(c,r,q)=>{q.qualified=false},/did not qualify/],
  ["lower throughput",c=>{c.summary.p50DecodeTokensPerSecond=79.99},/relative performance/],
  ["invalid denominator",(c,r)=>{r.summary.p50DecodeTokensPerSecond=0},/relative performance/],
  ["higher latency",c=>{c.summary.endToEndMillis.p95=150.01},/relative performance/],
  ["weaker policy",(c,r,q)=>{q.comparisons[0].minimumDecodeThroughputRatio=.45},/verdict ratios/],
]) test(`publisher gate rejects ${name}`, async () => {
  const f=fixture(change);await assert.rejects(validatePublisherReference(f.entry,"d".repeat(40),f.loadReport),message);
});

test("publisher receipts cannot omit provenance, swap the artifact or traverse paths", async () => {
  for (const update of [{qualificationEvidence:undefined},{artifactSha256:"f".repeat(64)},{qualificationEvidence:{report:"../verdict.json",reportSha256:"f".repeat(64)}}]) {
    const f=fixture();Object.assign(f.entry,update);
    await assert.rejects(validatePublisherReference(f.entry,"d".repeat(40),f.loadReport));
  }
});

test("publisher receipts reject corrupt bytes and candidate rebinding", async () => {
  const f=fixture();
  await assert.rejects(validatePublisherReference(f.entry,"d".repeat(40),async()=>Buffer.from("{}")),/digest mismatch/);
  f.entry.reportSha256="f".repeat(64);
  await assert.rejects(validatePublisherReference(f.entry,"d".repeat(40),f.loadReport),/candidate binding/);
});

test("absent stop sequences retain the Models default semantics", async () => {
  const f=fixture((c,r)=>{delete c.settings.generationControls.stopSequences; delete r.settings.generationControls.stopSequences;});
  await validatePublisherReference(f.entry,"d".repeat(40),f.loadReport);
});
