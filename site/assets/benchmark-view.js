import {
  decodeRatio,
  formatCostPer1k,
  formatDuration,
  prefillRatio,
} from "./benchmark-data.js";
import { formatBytes } from "./resource-profile.js";

const EXECUTION_LABELS = {
  "local-in-process": "Local in-process",
  "local-server": "Local server",
  "hosted-api": "Hosted API",
};

function average(values) {
  if (!values.length) throw new Error("Cannot average an empty benchmark set");
  return values.reduce((total, value) => total + value, 0) / values.length;
}

function formatPercent(value) {
  return `${value.toFixed(1)}%`;
}

function formatRate(value) {
  return `${value.toFixed(1)} tok/s`;
}

function formatEngine(engine) {
  if (engine === "pure-java") return "Models pure Java";
  if (engine === "openai") return "OpenAI";
  if (engine === "anthropic") return "Anthropic";
  if (engine === "deepseek") return "DeepSeek";
  return engine;
}

function formatEngineMetrics(metrics) {
  return {
    ttft: formatDuration(metrics.p95TtftMillis),
    tpot: formatDuration(metrics.p95TpotMillis),
    prefill: formatRate(metrics.prefillTokensPerSecond),
    decode: formatRate(metrics.decodeTokensPerSecond),
    peakRss: formatBytes(metrics.peakRssBytes),
    performanceTier: metrics.performanceTier,
  };
}

export function buildInferenceRows(benchmarks, models) {
  const modelsById = new Map(models.map((model) => [model.id, model]));
  return benchmarks.inferenceComparisons.map((comparison) => {
    const model = modelsById.get(comparison.modelId);
    if (!model) throw new Error(`Unknown benchmark model: ${comparison.modelId}`);
    return {
      id: comparison.id,
      modelId: model.id,
      modelName: model.name,
      modelUrl: `/models/${encodeURIComponent(model.id)}/`,
      status: comparison.status === "candidate" ? "Validated candidate" : "Profiled",
      statusId: comparison.status,
      artifactSha256: comparison.artifactSha256,
      engines: Object.fromEntries(
        Object.entries(comparison.engines).map(([engine, metrics]) => [
          engine,
          formatEngineMetrics(metrics),
        ]),
      ),
      prefillVsLlamaCpp: formatPercent(prefillRatio(comparison, "llama.cpp")),
      prefillVsOllama: formatPercent(prefillRatio(comparison, "ollama")),
      decodeVsLlamaCpp: formatPercent(decodeRatio(comparison, "llama.cpp")),
      decodeVsOllama: formatPercent(decodeRatio(comparison, "ollama")),
      evidence: comparison.evidence,
    };
  });
}

export function buildBenchmarkSummary(benchmarks) {
  const comparisons = benchmarks.inferenceComparisons;
  return {
    modelCount: comparisons.length,
    prefillVsLlamaCpp: formatPercent(
      average(comparisons.map((comparison) => prefillRatio(comparison, "llama.cpp"))),
    ),
    prefillVsOllama: formatPercent(
      average(comparisons.map((comparison) => prefillRatio(comparison, "ollama"))),
    ),
    decodeVsLlamaCpp: formatPercent(
      average(comparisons.map((comparison) => decodeRatio(comparison, "llama.cpp"))),
    ),
    decodeVsOllama: formatPercent(
      average(comparisons.map((comparison) => decodeRatio(comparison, "ollama"))),
    ),
  };
}

export function buildRagRows(benchmarks) {
  return benchmarks.ragComparison.rows.map((row) => ({
    id: row.id,
    catalogModelId: row.catalogModelId,
    execution: EXECUTION_LABELS[row.execution] || row.execution,
    engine: formatEngine(row.engine),
    model: row.model,
    retrieval: formatDuration(row.p95RetrievalMillis),
    ttft: formatDuration(row.p95TtftMillis),
    tpot: formatDuration(row.p95TpotMillis),
    endToEnd: formatDuration(row.p95EndToEndMillis),
    decode: formatRate(row.decodeTokensPerSecond),
    strictQuality: formatPercent(row.strictQuality * 100),
    auditedSemanticQuality: formatPercent(row.auditedSemanticQuality * 100),
    dataEgress: row.dataEgress ? "Yes" : "No",
    cost: formatCostPer1k(row.apiCostPer1kUsd),
    costDetail:
      row.uncachedApiCostPer1kUsd === undefined
        ? null
        : `${formatPercent(row.cacheReadInputFraction * 100)} cached input; ` +
          `${formatCostPer1k(row.uncachedApiCostPer1kUsd)} without cache`,
    evidence: row.evidence,
  }));
}
