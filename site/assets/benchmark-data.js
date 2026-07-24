const ENGINE_IDS = ["pure-java", "llama.cpp", "ollama"];
const METRICS = [
  "p95TtftMillis",
  "p95TpotMillis",
  "prefillTokensPerSecond",
  "decodeTokensPerSecond",
  "peakRssBytes",
];

function requireObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  return value;
}

function requireText(value, label) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${label} must be non-blank text`);
  }
  return value;
}

function requireMetric(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error(`${label} must be a finite non-negative number`);
  }
  return value;
}

function requireFraction(value, label) {
  requireMetric(value, label);
  if (value > 1) throw new Error(`${label} must not exceed 1`);
}

function requireEvidence(value, label) {
  const evidence = requireObject(value, label);
  const url = new URL(requireText(evidence.url, `${label}.url`));
  if (url.protocol !== "https:") throw new Error(`${label}.url must use HTTPS`);
  if (!/^[a-f0-9]{64}$/.test(requireText(evidence.sha256, `${label}.sha256`))) {
    throw new Error(`${label}.sha256 must be a lowercase SHA-256`);
  }
}

export function validateBenchmarkCatalog(value, models) {
  const document = requireObject(value, "benchmark catalog");
  if (document.schemaVersion !== 1) throw new Error("benchmark catalog must use schemaVersion 1");
  if (!Array.isArray(document.inferenceComparisons) || !document.inferenceComparisons.length) {
    throw new Error("benchmark catalog must contain inference comparisons");
  }
  const modelIds = new Set((models || []).map((model) => model.id));
  const comparisonIds = new Set();

  for (const comparison of document.inferenceComparisons) {
    requireText(comparison.id, "inference comparison id");
    if (comparisonIds.has(comparison.id)) throw new Error(`duplicate comparison: ${comparison.id}`);
    comparisonIds.add(comparison.id);
    if (!modelIds.has(comparison.modelId)) {
      throw new Error(`unknown benchmark modelId: ${comparison.modelId}`);
    }
    if (!/^[a-f0-9]{64}$/.test(comparison.artifactSha256 || "")) {
      throw new Error(`invalid artifact SHA-256: ${comparison.id}`);
    }
    const engines = requireObject(comparison.engines, `${comparison.id}.engines`);
    if (
      Object.keys(engines).length !== ENGINE_IDS.length ||
      !ENGINE_IDS.every((engine) => engines[engine])
    ) {
      throw new Error(`${comparison.id} must contain pure-java, llama.cpp, and ollama`);
    }
    for (const engine of ENGINE_IDS) {
      const metrics = requireObject(engines[engine], `${comparison.id}.${engine}`);
      for (const metric of METRICS) {
        requireMetric(metrics[metric], `${comparison.id}.${engine}.${metric}`);
      }
    }
    requireEvidence(comparison.evidence, `${comparison.id}.evidence`);
  }

  const rag = requireObject(document.ragComparison, "ragComparison");
  if (!Array.isArray(rag.rows) || !rag.rows.length) {
    throw new Error("ragComparison must contain rows");
  }
  const ragIds = new Set();
  for (const row of rag.rows) {
    requireText(row.id, "RAG row id");
    if (ragIds.has(row.id)) throw new Error(`duplicate RAG row: ${row.id}`);
    ragIds.add(row.id);
    if (row.catalogModelId !== null && !modelIds.has(row.catalogModelId)) {
      throw new Error(`unknown RAG catalogModelId: ${row.catalogModelId}`);
    }
    for (const metric of [
      "p95RetrievalMillis",
      "p95TtftMillis",
      "p95TpotMillis",
      "p95EndToEndMillis",
      "decodeTokensPerSecond",
    ]) {
      requireMetric(row[metric], `${row.id}.${metric}`);
    }
    requireFraction(row.strictQuality, `${row.id}.strictQuality`);
    requireFraction(row.auditedSemanticQuality, `${row.id}.auditedSemanticQuality`);
    if (typeof row.dataEgress !== "boolean") {
      throw new Error(`${row.id}.dataEgress must be boolean`);
    }
    if (row.apiCostPer1kUsd !== null) {
      requireMetric(row.apiCostPer1kUsd, `${row.id}.apiCostPer1kUsd`);
    }
    requireEvidence(row.evidence, `${row.id}.evidence`);
  }
  return document;
}

export function decodeRatio(comparison, denominator) {
  return throughputRatio(comparison, denominator, "decodeTokensPerSecond", "decode");
}

export function prefillRatio(comparison, denominator) {
  return throughputRatio(comparison, denominator, "prefillTokensPerSecond", "prefill");
}

function throughputRatio(comparison, denominator, metric, label) {
  const engines = requireObject(comparison?.engines, "comparison.engines");
  const pureJava = requireMetric(
    engines["pure-java"]?.[metric],
    `pure-java ${label} throughput`,
  );
  const reference = requireMetric(
    engines[denominator]?.[metric],
    `${denominator} ${label} throughput`,
  );
  if (reference === 0) throw new Error(`${denominator} ${label} throughput must be positive`);
  return (pureJava / reference) * 100;
}

export function formatDuration(value) {
  requireMetric(value, "duration");
  return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(2)} s`;
}

export function formatCostPer1k(value) {
  if (value === null || value === undefined) return "Local compute";
  requireMetric(value, "API cost");
  return `$${value < 0.1 ? value.toFixed(4) : value.toFixed(3)}`;
}
