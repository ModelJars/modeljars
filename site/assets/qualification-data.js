import { formatDuration } from "./benchmark-data.js";
import { formatBytes } from "./resource-profile.js";

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

function requireInteger(value, label, minimum = 0) {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`${label} must be an integer >= ${minimum}`);
  }
  return value;
}

function requireMetric(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error(`${label} must be a finite non-negative number`);
  }
  return value;
}

function requireRate(value, label) {
  requireMetric(value, label);
  if (value > 1) throw new Error(`${label} must not exceed 1`);
  return value;
}

function requireSha256(value, label) {
  if (!/^[a-f0-9]{64}$/.test(requireText(value, label))) {
    throw new Error(`${label} must be a lowercase SHA-256`);
  }
  return value;
}

function useCaseTier(entry) {
  if (!entry.qualified) return "UNQUALIFIED";
  return entry.rawCorrectAnswerRate >= 0.9 && entry.modelAnswerRate >= 0.9
    ? "GENERATIVE_RAG"
    : "GUARDED_RAG";
}

function reportUri(revision, path) {
  const report = requireText(path, "qualification report");
  if (report.startsWith("/") || report.split("/").some((part) => !part || part === "." || part === "..")) {
    throw new Error("qualification report must be a normalized relative path");
  }
  return new URL(`https://github.com/integrallis/models/blob/${revision}/${report}`).href;
}

export function validateQualificationCatalog(value, models) {
  const document = requireObject(value, "qualification catalog");
  if (document.schemaVersion !== 1) {
    throw new Error("qualification catalog must use schemaVersion 1");
  }
  if (document.status === "pending" && Array.isArray(document.entries) && !document.entries.length) {
    return {
      ...document,
      targetQualifiedModels: 25,
      qualifiedModels: 0,
      rejectedModels: 0,
    };
  }

  requireText(document.generatedAt, "generatedAt");
  if (Number.isNaN(Date.parse(document.generatedAt))) {
    throw new Error("generatedAt must be an ISO-8601 instant");
  }
  requireText(document.policyVersion, "policyVersion");
  if (!/^[a-f0-9]{40}$/.test(document.modelsRevision || "")) {
    throw new Error("modelsRevision must be a 40-character Git commit");
  }
  requireInteger(document.targetQualifiedModels, "targetQualifiedModels", 1);
  requireInteger(document.qualifiedModels, "qualifiedModels");
  requireInteger(document.rejectedModels, "rejectedModels");
  if (!Array.isArray(document.entries)) {
    throw new Error("qualification catalog must contain entries");
  }

  const modelsById = new Map((models || []).map((model) => [model.id, model]));
  const ids = new Set();
  const entries = document.entries.map((raw) => {
    const entry = requireObject(raw, "qualification entry");
    const modelId = requireText(entry.modelId, "qualification modelId");
    if (ids.has(modelId)) throw new Error(`duplicate qualification modelId: ${modelId}`);
    ids.add(modelId);
    const model = modelsById.get(modelId);
    if (!model) throw new Error(`unknown qualification modelId: ${modelId}`);
    requireText(entry.model, `${modelId}.model`);
    requireText(entry.backend, `${modelId}.backend`);
    requireText(entry.backendVersion, `${modelId}.backendVersion`);
    requireSha256(entry.artifactSha256, `${modelId}.artifactSha256`);
    if (entry.artifactSha256 !== model.sha256) {
      throw new Error(`artifact SHA-256 mismatch for ${modelId}`);
    }
    requireInteger(entry.artifactSizeBytes, `${modelId}.artifactSizeBytes`, 1);
    if (entry.artifactSizeBytes !== model.sizeBytes) {
      throw new Error(`artifact size mismatch for ${modelId}`);
    }
    requireSha256(entry.reportSha256, `${modelId}.reportSha256`);
    requireText(entry.performanceTier, `${modelId}.performanceTier`);
    requireText(entry.verdict, `${modelId}.verdict`);
    if (typeof entry.qualified !== "boolean") {
      throw new Error(`${modelId}.qualified must be boolean`);
    }
    requireInteger(entry.attempts, `${modelId}.attempts`, 1);
    for (const metric of [
      "p95RetrievalMillis",
      "p95TtftMillis",
      "p95TpotMillis",
      "p95EndToEndMillis",
      "p50PrefillTokensPerSecond",
      "p50DecodeTokensPerSecond",
      "peakRssBytes",
    ]) {
      requireMetric(entry[metric], `${modelId}.${metric}`);
    }
    for (const rate of [
      "correctAnswerRate",
      "rawCorrectAnswerRate",
      "abstentionAccuracy",
      "modelAnswerRate",
      "extractiveFallbackRate",
    ]) {
      requireRate(entry[rate], `${modelId}.${rate}`);
    }
    requireObject(entry.environment, `${modelId}.environment`);
    return {
      ...entry,
      reportUri: reportUri(document.modelsRevision, entry.report),
      modelsRevision: document.modelsRevision,
      policyVersion: document.policyVersion,
      useCaseTier: useCaseTier(entry),
    };
  });

  const qualified = entries.filter((entry) => entry.qualified).length;
  if (qualified !== document.qualifiedModels) {
    throw new Error("qualifiedModels does not match qualification entries");
  }
  if (entries.length - qualified !== document.rejectedModels) {
    throw new Error("rejectedModels does not match qualification entries");
  }
  return { ...document, entries };
}

export function primaryQualification(model) {
  const entries = Array.isArray(model?.ragQualifications) ? model.ragQualifications : [];
  return entries.find((entry) => entry.qualified) || entries[0] || null;
}

export function qualificationLabel(qualification) {
  if (!qualification?.qualified) return qualification ? "Evaluated" : "Not evaluated";
  if (qualification.useCaseTier === "GENERATIVE_RAG") return "Generative RAG";
  return "Guarded RAG";
}

function percent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

export function buildQualificationRows(qualifications, models) {
  const modelsById = new Map(models.map((model) => [model.id, model]));
  return qualifications.entries
    .map((entry) => {
      const model = modelsById.get(entry.modelId);
      if (!model) throw new Error(`Unknown qualification model: ${entry.modelId}`);
      return {
        ...entry,
        modelName: model.name,
        modelUrl: `/models/${encodeURIComponent(model.id)}/`,
        useCase: qualificationLabel(entry),
        retrieval: formatDuration(entry.p95RetrievalMillis),
        ttft: formatDuration(entry.p95TtftMillis),
        tpot: formatDuration(entry.p95TpotMillis),
        endToEnd: formatDuration(entry.p95EndToEndMillis),
        decode: `${entry.p50DecodeTokensPerSecond.toFixed(1)} tok/s`,
        rawQuality: percent(entry.rawCorrectAnswerRate),
        finalQuality: percent(entry.correctAnswerRate),
        fallbackRate: percent(entry.extractiveFallbackRate),
        peakRss: formatBytes(entry.peakRssBytes),
        evidence: { url: entry.reportUri, sha256: entry.reportSha256 },
      };
    })
    .sort(
      (left, right) =>
        Number(right.qualified) - Number(left.qualified) ||
        left.modelName.localeCompare(right.modelName),
    );
}
