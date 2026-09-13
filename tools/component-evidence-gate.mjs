#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const SHA256 = /^[a-f0-9]{64}$/;
const COMMIT = /^[a-f0-9]{40}$/;
const COMPONENT_POLICY = "activated-adapter-component-v1";
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const IMMUTABLE_MODELS_REPORT =
  /^https:\/\/raw\.githubusercontent\.com\/integrallis\/models\/([a-f0-9]{40})\/.+$/;

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function comparePath(left, right) {
  return left.path < right.path ? -1 : left.path > right.path ? 1 : 0;
}

function bundleSha256(files) {
  const identity = [...files]
    .sort(comparePath)
    .map((file) => `${file.path}\t${file.sizeBytes}\t${file.sha256}\n`)
    .join("");
  return createHash("sha256").update(identity, "utf8").digest("hex");
}

function requireDocuments(qualifications, models) {
  if (
    qualifications === null ||
    typeof qualifications !== "object" ||
    qualifications.schemaVersion !== 1 ||
    !Array.isArray(qualifications.entries)
  ) {
    throw new Error(
      "Component qualifications must use schemaVersion 1 and contain entries",
    );
  }
  if (qualifications.policyVersion !== COMPONENT_POLICY) {
    throw new Error(`Component qualifications must use ${COMPONENT_POLICY}`);
  }
  if (!COMMIT.test(qualifications.modelsRevision ?? "")) {
    throw new Error("Component qualifications must declare a lowercase Models revision");
  }
  if (!COMMIT.test(qualifications.evidenceRevision ?? "")) {
    throw new Error("Component qualifications must declare a lowercase evidence revision");
  }
  if (
    !UTC_TIMESTAMP.test(qualifications.generatedAt ?? "") ||
    Number.isNaN(Date.parse(qualifications.generatedAt))
  ) {
    throw new Error("Component qualifications must declare a UTC generatedAt timestamp");
  }
  if (
    models === null ||
    typeof models !== "object" ||
    models.schemaVersion !== 2 ||
    !Array.isArray(models.models)
  ) {
    throw new Error("Physical models must use schemaVersion 2 and contain models");
  }
  const qualified = qualifications.entries.filter(
    (entry) => entry?.qualified === true,
  ).length;
  if (
    qualifications.qualifiedModels !== undefined &&
    qualifications.qualifiedModels !== qualified
  ) {
    throw new Error("Component qualifiedModels count does not match entries");
  }
  if (
    qualifications.rejectedModels !== undefined &&
    qualifications.rejectedModels !== qualifications.entries.length - qualified
  ) {
    throw new Error("Component rejectedModels count does not match entries");
  }
}

function requireArtifactIdentity(entry, model) {
  if (!model.capabilities?.includes("composition-component")) {
    throw new Error(
      `${entry.modelId} must declare the composition-component capability`,
    );
  }
  if (
    !model.features?.includes("multi-file-artifact") ||
    !model.features?.includes("activated-lora-adapter") ||
    !Array.isArray(model.files) ||
    model.files.length < 2
  ) {
    throw new Error(
      `${entry.modelId} must be an activated multi-file adapter component`,
    );
  }
  const filesByPath = new Map(model.files.map((file) => [file.path, file]));
  const requiredFiles = new Map([
    ["adapter_model.safetensors", "adapter-weights"],
    ["models-activated-lora.json", "adapter-configuration"],
    ["NOTICE", "attribution-notice"],
    ["LICENSE", "license"],
  ]);
  if (
    [...requiredFiles].some(
      ([path, role]) => filesByPath.get(path)?.role !== role,
    )
  ) {
    throw new Error(
      `${entry.modelId} must include adapter weights, runtime metadata, attribution notice, and license`,
    );
  }
  if (
    entry.artifactSha256 !== model.sha256 ||
    entry.artifactSizeBytes !== model.sizeBytes
  ) {
    throw new Error(`${entry.modelId} qualification artifact identity is stale`);
  }
  const expectedFiles = [...model.files].sort(comparePath);
  const actualFiles = Array.isArray(entry.artifactFiles)
    ? [...entry.artifactFiles].sort(comparePath)
    : [];
  if (canonicalJson(actualFiles) !== canonicalJson(expectedFiles)) {
    throw new Error(
      `${entry.modelId} qualification must bind the complete runtime file list`,
    );
  }
  const expectedBundleSize = expectedFiles.reduce(
    (total, file) => total + file.sizeBytes,
    0,
  );
  if (
    entry.artifactBundleSizeBytes !== expectedBundleSize ||
    entry.artifactBundleSha256 !== bundleSha256(expectedFiles)
  ) {
    throw new Error(`${entry.modelId} qualification bundle identity is stale`);
  }
}

function requireTaskCorrectness(modelId, task) {
  if (task === null || typeof task !== "object") {
    throw new Error(`${modelId} taskCorrectness must be an object`);
  }
  const measuredFalseCallRate = task.falseToolCalls / task.irrelevanceAttempts;
  if (
    task?.pass !== true ||
    task.attempts !== 300 ||
    task.syntaxValidityRate !== 1 ||
    task.schemaValidityRate !== 1 ||
    !(task.selectionExactRate >= 0.85) ||
    !(task.selectionExactRate >= task.baseSelectionExactRate) ||
    task.irrelevanceAttempts !== 100 ||
    !(task.falseToolCalls >= 0 && task.falseToolCalls <= 5) ||
    !(task.falseToolCallRate >= 0 && task.falseToolCallRate <= 0.05) ||
    Math.abs(task.falseToolCallRate - measuredFalseCallRate) > 1e-12
  ) {
    throw new Error(
      `${modelId} taskCorrectness does not meet the fixed task-correctness thresholds`,
    );
  }
}

function requireReport(entry, report, model, base, modelsRevision) {
  if (report?.schemaVersion !== 1 || report.evaluation?.qualified !== true) {
    throw new Error(`${entry.modelId} component report must be qualified schemaVersion 1`);
  }
  if (
    report.implementation?.runtime !== "java" ||
    report.implementation.publicApiExercised !== true ||
    report.implementation.externalInference !== false
  ) {
    throw new Error(
      `${entry.modelId} evidence must exercise the Java production runtime through its public API`,
    );
  }
  if (report.implementation.modelsRevision !== modelsRevision) {
    throw new Error(`${entry.modelId} report Models revision does not match`);
  }
  const artifact = report.evaluation.artifact;
  if (
    artifact?.modelId !== model.id ||
    artifact.sha256 !== entry.artifactSha256 ||
    artifact.sizeBytes !== entry.artifactSizeBytes ||
    canonicalJson(
      [...(artifact.files ?? [])].sort(comparePath),
    ) !==
      canonicalJson(
        [...entry.artifactFiles].sort(comparePath),
      ) ||
    artifact.bundleSizeBytes !== entry.artifactBundleSizeBytes ||
    artifact.bundleSha256 !== entry.artifactBundleSha256
  ) {
    throw new Error(`${entry.modelId} report artifact identity does not match`);
  }
  if (
    report.evaluation.base?.modelId !== base.id ||
    report.evaluation.base.sha256 !== base.sha256 ||
    !COMMIT.test(base.revision ?? "") ||
    report.evaluation.base.revision !== base.revision
  ) {
    throw new Error(`${entry.modelId} report base identity does not match`);
  }
  const gates = report.evaluation.gates;
  if (
    gates?.provenance?.pass !== true ||
    gates.provenance.frozenEvaluation !== true ||
    !SHA256.test(gates.provenance.selectionManifestSha256 ?? "") ||
    !SHA256.test(gates.provenance.trainingManifestSha256 ?? "")
  ) {
    throw new Error(`${entry.modelId} provenance must bind frozen evaluation and training`);
  }
  if (
    !SHA256.test(gates.provenance.formatterSha256 ?? "") ||
    !SHA256.test(gates.provenance.trainerSha256 ?? "")
  ) {
    throw new Error(`${entry.modelId} provenance must bind the formatter and trainer`);
  }
  requireTaskCorrectness(entry.modelId, gates.taskCorrectness);
  if (
    gates.plainJava?.pass !== true ||
    gates.plainJava.realWeights !== true ||
    gates.springAi?.pass !== true ||
    gates.springAi.realWeights !== true ||
    gates.springAi.toolInvocations !== 1 ||
    gates.langChain4j?.pass !== true ||
    gates.langChain4j.realWeights !== true ||
    gates.langChain4j.toolInvocations !== 1
  ) {
    throw new Error(
      `${entry.modelId} must pass real-weight plain Java, Spring AI, and LangChain4j gates`,
    );
  }
  if (
    gates.toolResultLoop?.pass !== true ||
    gates.toolResultLoop.secondSelectionCompleted !== true ||
    gates.toolResultLoop.repeatedToolCall !== false
  ) {
    throw new Error(`${entry.modelId} must complete the real tool-result loop`);
  }
}

export async function validateComponentEvidence({
  qualifications,
  models,
  loadReport,
}) {
  requireDocuments(qualifications, models);
  if (typeof loadReport !== "function") {
    throw new Error("A report loader is required for component evidence");
  }
  const modelsById = new Map();
  for (const model of models.models) {
    if (typeof model?.id !== "string" || modelsById.has(model.id)) {
      throw new Error("Physical model ids must be nonempty and unique");
    }
    modelsById.set(model.id, model);
  }
  const entryIds = new Set();
  const checked = [];
  for (const entry of qualifications.entries) {
    if (typeof entry?.modelId !== "string" || entryIds.has(entry.modelId)) {
      throw new Error("Component qualification model ids must be nonempty and unique");
    }
    entryIds.add(entry.modelId);
    const model = modelsById.get(entry.modelId);
    if (model === undefined) {
      throw new Error(`Unknown component model ${entry.modelId}`);
    }
    requireArtifactIdentity(entry, model);
    const base = modelsById.get(entry.baseModelId);
    if (
      base === undefined ||
      base.capabilities?.includes("composition-component")
    ) {
      throw new Error(`${entry.modelId} must name a physical public base model`);
    }
    if (entry.qualified !== true) {
      continue;
    }
    if (!Array.isArray(entry.unresolvedRequiredWork)) {
      throw new Error(`${entry.modelId}.unresolvedRequiredWork must be an array`);
    }
    if (entry.unresolvedRequiredWork.length !== 0) {
      throw new Error(`${entry.modelId} has unresolved required work`);
    }
    const reportLocation = IMMUTABLE_MODELS_REPORT.exec(entry.reportUri ?? "");
    if (reportLocation === null) {
      throw new Error(
        `${entry.modelId}.reportUri must be an immutable raw integrallis/models URL`,
      );
    }
    if (reportLocation[1] !== qualifications.evidenceRevision) {
      throw new Error(
        `${entry.modelId}.reportUri is not pinned to the declared evidence revision`,
      );
    }
    if (!SHA256.test(entry.reportSha256 ?? "")) {
      throw new Error(`${entry.modelId}.reportSha256 must be a lowercase SHA-256`);
    }
    const bytes = await loadReport({ entry, model, base });
    if (createHash("sha256").update(bytes).digest("hex") !== entry.reportSha256) {
      throw new Error(`${entry.modelId} report SHA-256 does not match`);
    }
    let report;
    try {
      report = JSON.parse(Buffer.from(bytes).toString("utf8"));
    } catch (error) {
      throw new Error(`${entry.modelId} evidence report is not JSON`, {
        cause: error,
      });
    }
    requireReport(entry, report, model, base, qualifications.modelsRevision);
    checked.push(entry.modelId);
  }
  return checked;
}

function parseArguments(args) {
  if (args.length !== 4 || args[0] !== "--qualifications" || args[2] !== "--models") {
    throw new Error(
      "Usage: component-evidence-gate.mjs --qualifications FILE --models FILE",
    );
  }
  return { qualifications: args[1], models: args[3] };
}

async function main() {
  const paths = parseArguments(process.argv.slice(2));
  const checked = await validateComponentEvidence({
    qualifications: JSON.parse(await readFile(paths.qualifications, "utf8")),
    models: JSON.parse(await readFile(paths.models, "utf8")),
    loadReport: async ({ entry }) => {
      const response = await fetch(entry.reportUri);
      if (!response.ok) {
        throw new Error(`Could not fetch ${entry.reportUri}: HTTP ${response.status}`);
      }
      return new Uint8Array(await response.arrayBuffer());
    },
  });
  process.stdout.write(
    `Validated ${checked.length} qualified component evidence report(s)\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main();
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
    process.exitCode = 1;
  }
}
