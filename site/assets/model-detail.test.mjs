import assert from "node:assert/strict";
import test from "node:test";

import {
  artifactDownloadBytes,
  artifactManifest,
  embeddingJavaSnippet,
  gradleSnippet,
  javaSnippet,
  mavenSnippet,
  modelIdFromPath,
  qualificationSummary,
  resourceMemoryNote,
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
    'implementation("org.modeljars:modeljars:0.1.28")\n' +
      'implementation("org.modeljars.huggingface:qwen.qwen3.q4_k_m:3.0.0-q4_k_m.1")',
  );
  assert.match(mavenSnippet(coordinate), /<groupId>org\.modeljars<\/groupId>/);
  assert.match(mavenSnippet(coordinate), /<artifactId>modeljars<\/artifactId>/);
  assert.match(mavenSnippet(coordinate), /<groupId>org\.modeljars\.huggingface<\/groupId>/);
  assert.match(mavenSnippet(coordinate), /<artifactId>qwen\.qwen3\.q4_k_m<\/artifactId>/);
  assert.match(mavenSnippet(coordinate), /<version>3\.0\.0-q4_k_m\.1<\/version>/);
});

test("renders path-free Java loading from the generated catalog reference", () => {
  const snippet = javaSnippet("qwen3_0_6b_q4_0");

  assert.match(
    snippet,
    /import static org\.modeljars\.catalog\.Qwen3_0_6b_Q4_0\.MODEL;/,
  );
  assert.match(snippet, /runtime\.chatTemplate\(\)\.render/);
  assert.match(snippet, /ModelPrompt prompt/);
  assert.match(snippet, /InferencePipeline pipeline = runtime\.pipeline\(\)/);
  assert.match(snippet, /ModelJars\.openRuntime\(MODEL\)/);
  assert.doesNotMatch(snippet, /Path|ModelJarInstaller|PureJavaBackend/);
});

test("renders qualified embedding loading without paths or pooling guesses", () => {
  const snippet = embeddingJavaSnippet("ggml_org_embeddinggemma_300m_gguf_q8_0");

  assert.match(
    snippet,
    /import static org\.modeljars\.catalog\.Ggml_Org_Embeddinggemma_300m_Gguf_Q8_0\.MODEL;/,
  );
  assert.match(snippet, /ModelJars\.openEmbedding\(MODEL\)/);
  assert.match(snippet, /embeddings\.embed/);
  assert.doesNotMatch(snippet, /Path|Pooling|PureJavaBackend/);
});

test("describes the complete artifact manifest rather than only the primary weight", () => {
  const model = {
    downloadUri: "https://huggingface.co/acme/model/resolve/abc/model.safetensors",
    sizeBytes: 988_097_824,
    sha256: "f".repeat(64),
    files: [
      { path: "config.json", role: "model-configuration", sizeBytes: 659, sha256: "a".repeat(64) },
      { path: "model.safetensors", role: "model-weights", sizeBytes: 988_097_824, sha256: "f".repeat(64) },
      { path: "tokenizer.json", role: "tokenizer", sizeBytes: 7_031_645, sha256: "b".repeat(64) },
    ],
  };

  assert.equal(artifactManifest(model).length, 3);
  assert.equal(artifactDownloadBytes(model), 995_130_128);
  assert.deepEqual(artifactManifest(model).map(({ path }) => path), [
    "config.json",
    "model.safetensors",
    "tokenizer.json",
  ]);
});

test("does not claim embedding-only models allocate a generation KV cache", () => {
  assert.match(resourceMemoryNote({ capabilities: ["text-generation"] }), /KV cache/);
  assert.doesNotMatch(resourceMemoryNote({ capabilities: ["semantic-search"] }), /KV cache/);
  assert.match(resourceMemoryNote({ capabilities: ["semantic-search"] }), /embedding working buffers/i);
});

test("summarizes exact RAG qualification evidence without hiding fallbacks", () => {
  const summary = qualificationSummary({
    qualified: true,
    useCaseTier: "GUARDED_RAG",
    backend: "llama.cpp",
    backendVersion: "b10012-c71854292",
    workload: "coding",
    promptTemplate: "chatml",
    groundingPolicy: "trusted-provenance-clause-anchors-extractive-fallback-v4",
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
  assert.equal(summary.workload, "coding");
  assert.equal(summary.promptTemplate, "chatml");
  assert.equal(
    summary.groundingPolicy,
    "trusted-provenance-clause-anchors-extractive-fallback-v4",
  );
  assert.equal(summary.rawQuality, "67.0%");
  assert.equal(summary.finalQuality, "100.0%");
  assert.equal(summary.fallbackRate, "33.0%");
  assert.equal(summary.decode, "50.0 tok/s");
  assert.equal(summary.evidenceSha256, "b".repeat(64));
});

const embeddingQualification = {
  qualified: true,
  useCaseTier: "SEMANTIC_SEARCH",
  backend: "pure-java",
  backendVersion: "models-0.3.0",
  workload: "oracle-equivalence-v1",
  probes: 8,
  embeddingDimension: 1024,
  pooling: "last-token",
  normalized: true,
  oracleBackend: "llama.cpp",
  oracleVersion: "6ea215d17",
  minimumOracleCosine: 0.9995014497617521,
  meanOracleCosine: 0.999647,
  maxNormDeviation: 2.7e-9,
  reportUri: "https://github.com/integrallis/models/blob/fa26f46/report.json",
  reportSha256: "4764fbbf682254e8664d09e1ef4e8a61d816303c9c155a29f1be022f79b357be",
};

test("summarizes embedding evidence without RAG latency fields", () => {
  // Embedding evidence has no TTFT, decode rate or answer rates. Formatting them threw and
  // took down the whole catalog render.
  const summary = qualificationSummary(embeddingQualification);

  assert.equal(summary.label, "Semantic search");
  assert.equal(summary.qualified, true);
  assert.equal(summary.probes, 8);
  assert.equal(summary.oracle, "llama.cpp 6ea215d17");
  assert.equal(summary.ttft, null);
  assert.equal(summary.decode, null);
});

test("summarizes tool conformance without inventing RAG metrics", () => {
  const summary = qualificationSummary({
    qualified: true,
    useCaseTier: "TOOL_CALLING",
    backend: "pure-java",
    backendVersion: "models@" + "c".repeat(40),
    workload: "needle2-upstream-playground-v1",
    promptTemplate: "needle2",
    attempts: 14,
    passed: 12,
    structuredOutputRate: 1,
    toolSelectionExactRate: 1,
    schemaValidityRate: 1,
    declaredArgumentsOnlyRate: 1,
    expectedArgumentAccuracy: 0.9189189189,
    refusalAccuracy: 1,
    p95EndToEndMillis: 53195,
    reportUri: "https://github.com/integrallis/models/blob/" + "d".repeat(40) + "/report.json",
    reportSha256: "e".repeat(64),
  });

  assert.equal(summary.label, "Tool calling");
  assert.equal(summary.passed, 12);
  assert.equal(summary.selection, "100.0%");
  assert.equal(summary.arguments, "91.9%");
  assert.equal(summary.endToEnd, "53.20 s");
  assert.equal(summary.rawQuality, null);
  assert.equal(summary.evidenceSha256, "e".repeat(64));
});
