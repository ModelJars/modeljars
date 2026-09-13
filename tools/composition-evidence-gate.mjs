#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const SHA256 = /^[a-f0-9]{64}$/;
const IMMUTABLE_RAW_GITHUB =
  /^https:\/\/raw\.githubusercontent\.com\/[^/]+\/[^/]+\/([a-f0-9]{40})\/.+$/;
const IMPLEMENTED_HANDOFFS = new Set(["exact-kv-block-sharing"]);

function requireDocument(value) {
  if (
    value === null ||
    typeof value !== "object" ||
    value.schemaVersion !== 1 ||
    !Array.isArray(value.compositions)
  ) {
    throw new Error("Compositions must use schemaVersion 1 and contain compositions");
  }
}

function physicalModels(value) {
  if (
    value === null ||
    typeof value !== "object" ||
    value.schemaVersion !== 2 ||
    !Array.isArray(value.models)
  ) {
    throw new Error("Physical models must use schemaVersion 2 and contain models");
  }
  const byId = new Map();
  for (const model of value.models) {
    if (typeof model.id !== "string" || byId.has(model.id)) {
      throw new Error("Physical model ids must be nonempty and unique");
    }
    if (typeof model.markerCoordinate !== "string" || !SHA256.test(model.sha256 ?? "")) {
      throw new Error(`${model.id} physical model identity is incomplete`);
    }
    byId.set(model.id, model);
  }
  return byId;
}

function requireCompleteEvidence(composition, qualification) {
  const label = `${composition.id}.compositionQualifications`;
  if (!Array.isArray(qualification.unresolvedRequiredWork)) {
    throw new Error(`${label}.unresolvedRequiredWork must be an explicit array`);
  }
  if (qualification.unresolvedRequiredWork.length !== 0) {
    throw new Error(`${composition.id} has unresolved required work and cannot be qualified`);
  }
  if (!IMMUTABLE_RAW_GITHUB.test(qualification.reportUri ?? "")) {
    throw new Error(`${label}.reportUri must be an immutable raw GitHub URL pinned to a commit`);
  }
  if (!SHA256.test(qualification.reportSha256 ?? "")) {
    throw new Error(`${label}.reportSha256 must be a lowercase SHA-256`);
  }
}

function parseReport(composition, bytes) {
  try {
    return JSON.parse(Buffer.from(bytes).toString("utf8"));
  } catch (error) {
    throw new Error(`${composition.id} evidence report is not JSON`, { cause: error });
  }
}

function metricsMatch(qualification, report) {
  const evaluation = report.evaluation;
  return (
    evaluation !== null &&
    typeof evaluation === "object" &&
    qualification.controlMedianMillis === evaluation.controlMedianMillis &&
    qualification.compositeMedianMillis === evaluation.hybridMedianMillis &&
    qualification.latencyImprovement === evaluation.improvement
  );
}

function requireProductionEvidence(composition, report, modelsById) {
  if (report.schemaVersion !== 2) {
    throw new Error(`${composition.id} evidence report must use schemaVersion 2`);
  }
  const implementation = report.implementation;
  if (
    implementation?.runtime !== "java" ||
    implementation.publicApiExercised !== true ||
    implementation.externalInference !== false
  ) {
    throw new Error(
      `${composition.id} evidence must exercise the Java production runtime through its public API`,
    );
  }
  if (!IMPLEMENTED_HANDOFFS.has(implementation.stateHandoffMechanism)) {
    throw new Error(`${composition.id} evidence must name an implemented cache-state handoff`);
  }

  const evaluation = report.evaluation;
  if (evaluation.handoffCostIncluded !== true) {
    throw new Error(`${composition.id} performance evidence must include the complete handoff cost`);
  }
  const artifacts = evaluation.publishedArtifacts;
  if (!Array.isArray(artifacts) || artifacts.length !== composition.members?.length) {
    throw new Error(`${composition.id} must verify every published member artifact`);
  }
  const expectedMembers = new Set(composition.members.map((member) => member.modelId));
  if (expectedMembers.size !== composition.members.length) {
    throw new Error(`${composition.id} member model ids must be unique`);
  }
  const coordinates = new Set();
  for (const artifact of artifacts) {
    if (
      typeof artifact.coordinate !== "string" ||
      artifact.coordinate.split(":").length !== 3 ||
      !SHA256.test(artifact.sha256 ?? "")
    ) {
      throw new Error(`${composition.id} published artifact identity is incomplete`);
    }
    if (artifact.resolvedFromCentral !== true || artifact.runViaPublicApi !== true) {
      throw new Error(
        `${composition.id} published artifacts must be resolved from Central and run through the public API`,
      );
    }
    const physical = modelsById.get(artifact.modelId);
    if (
      !expectedMembers.delete(artifact.modelId) ||
      physical === undefined ||
      artifact.coordinate !== physical.markerCoordinate ||
      artifact.sha256 !== physical.sha256
    ) {
      throw new Error(
        `${composition.id} member artifacts must match the physical model catalog`,
      );
    }
    coordinates.add(artifact.coordinate);
  }
  if (coordinates.size !== artifacts.length || expectedMembers.size !== 0) {
    throw new Error(`${composition.id} published artifact coordinates must be unique`);
  }

  const gates = evaluation.gates;
  for (const name of [
    "provenance",
    "taskCorrectness",
    "plainJava",
    "springAi",
    "langChain4j",
    "toolResultLoop",
    "conversationState",
    "longContextRetrieval",
    "performanceCrossover",
    "memoryAccounting",
  ]) {
    if (gates?.[name]?.pass !== true) {
      throw new Error(`${composition.id} ${name} gate must pass`);
    }
  }

  if (
    gates.provenance.frozenEvaluation !== true ||
    !SHA256.test(gates.provenance.selectionManifestSha256 ?? "") ||
    !SHA256.test(gates.provenance.adapterManifestSha256 ?? "")
  ) {
    throw new Error(`${composition.id} provenance must bind the frozen evaluation and adapter`);
  }

  const task = gates.taskCorrectness;
  const measuredFalseCallRate = task.falseToolCalls / task.irrelevanceAttempts;
  if (
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
    throw new Error(`${composition.id} taskCorrectness does not meet the fixed task-correctness thresholds`);
  }

  if (
    gates.plainJava.realWeights !== true ||
    gates.springAi.realWeights !== true ||
    gates.springAi.toolInvocations !== 1 ||
    gates.langChain4j.realWeights !== true ||
    gates.langChain4j.toolInvocations !== 1
  ) {
    throw new Error(
      `${composition.id} must pass real-weight plain Java, Spring AI, and LangChain4j gates`,
    );
  }
  if (
    gates.toolResultLoop.secondSelectionCompleted !== true ||
    gates.toolResultLoop.repeatedToolCall !== false
  ) {
    throw new Error(`${composition.id} must complete the real tool-result selection loop`);
  }
  if (
    !(gates.conversationState.turns >= 6) ||
    gates.conversationState.oneCacheLineage !== true
  ) {
    throw new Error(`${composition.id} must retain one cache lineage across six conversation turns`);
  }

  const longContext = gates.longContextRetrieval;
  if (
    longContext.cases !== 8 ||
    longContext.contextTokens !== 4096 ||
    !(longContext.nativeCorrect >= 6) ||
    longContext.retainedNativeCorrect !== longContext.nativeCorrect ||
    longContext.tokenExactCases !== longContext.cases ||
    longContext.physicallySharedCases !== longContext.cases
  ) {
    throw new Error(
      `${composition.id} longContextRetrieval must retain every native-correct answer over physically shared 4K prefixes`,
    );
  }

  const crossover = gates.performanceCrossover;
  if (
    crossover.contextTokens !== 4096 ||
    !(crossover.improvement >= 0.2) ||
    crossover.improvement !== evaluation.improvement ||
    crossover.physicallySharedAllTiers !== true ||
    crossover.recomputedIndependentAllTiers !== true ||
    crossover.tokenExactAllTiers !== true
  ) {
    throw new Error(
      `${composition.id} performanceCrossover must prove physical KV sharing and token equality with at least 20% improvement at 4K`,
    );
  }

  const memory = gates.memoryAccounting;
  if (
    memory.complete !== true ||
    !(memory.peakRssBytes > 0) ||
    !(memory.sharedUniqueStateBytes > 0) ||
    !(memory.recomputedUniqueStateBytes > 0) ||
    !(memory.sharedUniqueStateBytes < memory.recomputedUniqueStateBytes)
  ) {
    throw new Error(
      `${composition.id} memory accounting must prove fewer unique inference-state bytes with sharing`,
    );
  }
}

export async function validateCompositionEvidence({ compositions, models, loadReport }) {
  requireDocument(compositions);
  const modelsById = physicalModels(models);
  if (typeof loadReport !== "function") {
    throw new Error("A report loader is required; qualified composite evidence must be verified");
  }

  const checked = [];
  for (const composition of compositions.compositions) {
    const qualifications = composition.compositionQualifications;
    if (!Array.isArray(qualifications)) continue;
    for (const qualification of qualifications) {
      if (qualification.qualified !== true) continue;
      requireCompleteEvidence(composition, qualification);

      const bytes = await loadReport({ composition, qualification });
      const actualSha = createHash("sha256").update(bytes).digest("hex");
      if (actualSha !== qualification.reportSha256) {
        throw new Error(`${composition.id} evidence report SHA-256 does not match`);
      }

      const report = parseReport(composition, bytes);
      if (report.evaluation?.qualified !== true || report.evaluation?.correctnessPassed !== true) {
        throw new Error(`${composition.id} evidence report must pass correctness and qualification`);
      }
      requireProductionEvidence(composition, report, modelsById);
      if (!metricsMatch(qualification, report)) {
        throw new Error(`${composition.id} catalog metrics do not match its evidence report`);
      }
    }
    if (qualifications.some((qualification) => qualification.qualified === true)) {
      checked.push(composition.id);
    }
  }
  return checked;
}

function parseArguments(args) {
  if (
    args.length !== 4 ||
    args[0] !== "--compositions" ||
    args[2] !== "--models"
  ) {
    throw new Error(
      "Usage: composition-evidence-gate.mjs --compositions FILE --models FILE",
    );
  }
  return { compositions: args[1], models: args[3] };
}

async function main() {
  const paths = parseArguments(process.argv.slice(2));
  const checked = await validateCompositionEvidence({
    compositions: JSON.parse(await readFile(paths.compositions, "utf8")),
    models: JSON.parse(await readFile(paths.models, "utf8")),
    loadReport: async ({ qualification }) => {
      const response = await fetch(qualification.reportUri);
      if (!response.ok) {
        throw new Error(`Could not fetch ${qualification.reportUri}: HTTP ${response.status}`);
      }
      return new Uint8Array(await response.arrayBuffer());
    },
  });
  process.stdout.write(`Validated ${checked.length} qualified composition evidence report(s)\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main();
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
    process.exitCode = 1;
  }
}
