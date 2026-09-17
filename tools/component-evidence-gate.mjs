#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const SHA256 = /^[a-f0-9]{64}$/;
const COMMIT = /^[a-f0-9]{40}$/;
const COMPONENT_POLICY = "activated-adapter-component-v1";
const TRAINED_TOOL_SPECIALIST = "trained-tool-specialist";
const UPSTREAM_RAG_SPECIALIST = "upstream-rag-specialist";
const FIRST_PARTY_RAG_SPECIALIST = "first-party-rag-specialist";
const SPECIALIST_KINDS = new Set([
  TRAINED_TOOL_SPECIALIST,
  UPSTREAM_RAG_SPECIALIST,
  FIRST_PARTY_RAG_SPECIALIST,
]);
const LABEL_SOURCES = new Set(["dataset", "confirmed"]);
const GITHUB_REPOSITORY = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const MINIMUM_WINDOW_SUITES = 2;
const MINIMUM_WINDOW_CASES = 100;
const MINIMUM_BALANCED_ACCURACY = 0.8;
const MINIMUM_KERNEL_IDENTITY_CASES = 10;
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const IMMUTABLE_MODELS_REPORT =
  /^https:\/\/raw\.githubusercontent\.com\/integrallis\/models\/([a-f0-9]{40})\/.+$/;
const MAVEN_CENTRAL = "https://repo1.maven.org/maven2";
const REQUIRED_MODELS_MODULES = [
  "backend-java",
  "models-runtime",
  "models-spring-ai",
  "models-langchain4j",
];
const RELEASE_VERSION = /^\d+\.\d+\.\d+$/;

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

/** The evidence shape an entry claims; absent means the original trained tool specialist. */
function specialistKind(entry) {
  const kind = entry.specialistKind ?? TRAINED_TOOL_SPECIALIST;
  if (!SPECIALIST_KINDS.has(kind)) {
    throw new Error(`${entry.modelId} declares an unknown specialistKind ${kind}`);
  }
  return kind;
}

function requireArtifactIdentity(entry, model) {
  if (
    !Number.isInteger(entry.minimumSharedPrefixTokens) ||
    entry.minimumSharedPrefixTokens <= 0
  ) {
    throw new Error(
      `${entry.modelId} must bind a positive measured sharing crossover`,
    );
  }
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

function sameMembers(actual, expected) {
  return (
    Array.isArray(actual) &&
    actual.length === expected.length &&
    expected.every((item) => actual.includes(item))
  );
}

function requirePositiveInteger(value) {
  return Number.isInteger(value) && value > 0;
}

function requireReleasedArtifactFile(entry, artifact, version, kind) {
  const file = artifact?.[kind];
  const extension = kind === "jar" ? "jar" : "pom";
  const module = artifact?.module;
  const expectedCoordinate = `com.integrallis:${module}:${version}`;
  const expectedUri =
    `${MAVEN_CENTRAL}/com/integrallis/${module}/${version}/` +
    `${module}-${version}.${extension}`;
  if (
    file === null ||
    typeof file !== "object" ||
    artifact?.coordinate !== expectedCoordinate ||
    file.uri !== expectedUri ||
    !SHA256.test(file.sha256 ?? "") ||
    !requirePositiveInteger(file.sizeBytes)
  ) {
    throw new Error(
      `${entry.modelId} must bind immutable Maven Central ${artifact?.module ?? "artifact"} ${kind} evidence`,
    );
  }
  return file;
}

async function requireReleasedModelsArtifacts(entry, gate, loadReleasedArtifact) {
  if (
    gate?.pass !== true ||
    !RELEASE_VERSION.test(gate.version ?? "") ||
    !Array.isArray(gate.artifacts) ||
    gate.artifacts.length !== REQUIRED_MODELS_MODULES.length
  ) {
    throw new Error(
      `${entry.modelId} must bind clean-host immutable Maven Central Models artifacts`,
    );
  }
  const modules = gate.artifacts.map((artifact) => artifact?.module);
  if (!sameMembers(modules, REQUIRED_MODELS_MODULES)) {
    throw new Error(
      `${entry.modelId} must bind exactly the required Models artifacts`,
    );
  }
  for (const artifact of gate.artifacts) {
    const jar = requireReleasedArtifactFile(entry, artifact, gate.version, "jar");
    const pom = requireReleasedArtifactFile(entry, artifact, gate.version, "pom");
    for (const [kind, expected] of [
      ["jar", jar],
      ["pom", pom],
    ]) {
      const bytes = await loadReleasedArtifact({ entry, artifact, kind });
      if (!(bytes instanceof Uint8Array)) {
        throw new Error(
          `${entry.modelId} released ${artifact.module} ${kind} loader did not return bytes`,
        );
      }
      if (
        bytes.byteLength !== expected.sizeBytes ||
        createHash("sha256").update(bytes).digest("hex") !== expected.sha256
      ) {
        throw new Error(
          `${entry.modelId} released ${artifact.module} ${kind} bytes do not match immutable evidence`,
        );
      }
    }
  }
}

async function requireCleanHostRun(
  entry,
  run,
  evidenceRevision,
  modelsVersion,
  loadEvidenceFile,
) {
  const startedAt = Date.parse(run?.startedAt ?? "");
  const completedAt = Date.parse(run?.completedAt ?? "");
  const outputLog = run?.outputLog;
  const logLocation = IMMUTABLE_MODELS_REPORT.exec(outputLog?.uri ?? "");
  if (
    run?.pass !== true ||
    run.freshMachine !== true ||
    run.externalInference !== false ||
    run.javaMajor !== 25 ||
    run.exitCode !== 0 ||
    run.modelsVersion !== modelsVersion ||
    !UTC_TIMESTAMP.test(run.startedAt ?? "") ||
    !UTC_TIMESTAMP.test(run.completedAt ?? "") ||
    Number.isNaN(startedAt) ||
    Number.isNaN(completedAt) ||
    completedAt <= startedAt ||
    !Array.isArray(run.command) ||
    run.command.length === 0 ||
    run.command.some((part) => typeof part !== "string" || part.length === 0) ||
    !SHA256.test(run.resolvedClasspathSha256 ?? "") ||
    // Any 40-hex integrallis/models commit is immutable; the bytes are verified below. The log
    // cannot be pinned to evidenceRevision itself because the report at that commit embeds this
    // URI, which would make the commit hash depend on its own contents.
    logLocation === null ||
    !SHA256.test(outputLog?.sha256 ?? "") ||
    !requirePositiveInteger(outputLog?.sizeBytes)
  ) {
    throw new Error(
      `${entry.modelId} must bind a completed immutable clean-host Java 25 run`,
    );
  }
  const bytes = await loadEvidenceFile({
    entry,
    evidence: outputLog,
    kind: "clean-host-output",
  });
  if (!(bytes instanceof Uint8Array)) {
    throw new Error(`${entry.modelId} clean-host output loader did not return bytes`);
  }
  if (
    bytes.byteLength !== outputLog.sizeBytes ||
    createHash("sha256").update(bytes).digest("hex") !== outputLog.sha256
  ) {
    throw new Error(
      `${entry.modelId} clean-host output bytes do not match immutable evidence`,
    );
  }
}

async function requireReport(
  entry,
  report,
  model,
  base,
  modelsRevision,
  evidenceRevision,
  loadReleasedArtifact,
  loadEvidenceFile,
) {
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
    report.evaluation.base.sizeBytes !== base.sizeBytes ||
    !COMMIT.test(base.revision ?? "") ||
    report.evaluation.base.revision !== base.revision
  ) {
    throw new Error(`${entry.modelId} report base identity does not match`);
  }
  const gates = report.evaluation.gates;
  const kind = specialistKind(entry);
  if (kind === UPSTREAM_RAG_SPECIALIST || kind === FIRST_PARTY_RAG_SPECIALIST) {
    if (kind === UPSTREAM_RAG_SPECIALIST) {
      requireUpstreamRagSpecialistGates(entry, report, gates);
    } else {
      await requireFirstPartyRagSpecialistGates(
        entry,
        report,
        gates,
        model,
        loadEvidenceFile,
      );
    }
    await requireReleasedModelsArtifacts(
      entry,
      gates.modelsArtifact,
      loadReleasedArtifact,
    );
    await requireCleanHostRun(
      entry,
      gates.cleanHostRun,
      evidenceRevision,
      gates.modelsArtifact.version,
      loadEvidenceFile,
    );
    return;
  }
  if (report.specialistKind !== undefined && report.specialistKind !== TRAINED_TOOL_SPECIALIST) {
    throw new Error(`${entry.modelId} report specialistKind does not match the catalog entry`);
  }
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
    gates.plainJava.cases !== 14 ||
    gates.plainJava.zipcodeRegression !== true ||
    gates.plainJava.argumentThreshold !== true ||
    gates.plainJava.abstention !== true ||
    gates.plainJava.sixTurnConversation !== true ||
    gates.springAi?.pass !== true ||
    gates.springAi.realWeights !== true ||
    gates.springAi.toolInvocations !== 1 ||
    gates.springAi.naturalLanguageResult !== true ||
    !sameMembers(gates.springAi.versions, ["1.1.4", "1.1.8", "2.0.0"]) ||
    gates.langChain4j?.pass !== true ||
    gates.langChain4j.realWeights !== true ||
    gates.langChain4j.toolInvocations !== 1 ||
    gates.langChain4j.naturalLanguageResult !== true ||
    !sameMembers(gates.langChain4j.versions, ["1.0.0", "1.13.1", "1.17.2"])
  ) {
    throw new Error(
      `${entry.modelId} must pass real-weight plain Java, Spring AI, and LangChain4j gates`,
    );
  }
  if (
    gates.toolResultLoop?.pass !== true ||
    gates.toolResultLoop.secondSelectionCompleted !== true ||
    gates.toolResultLoop.repeatedToolCall !== false ||
    gates.toolResultLoop.naturalLanguageResult !== true
  ) {
    throw new Error(`${entry.modelId} must complete the real tool-result loop`);
  }
  if (
    gates.projectionOracle?.pass !== true ||
    !(gates.projectionOracle.comparedProjections > 0) ||
    !(gates.projectionOracle.maximumAbsoluteDelta >= 0) ||
    !(
      gates.projectionOracle.maximumAbsoluteDelta <=
      gates.projectionOracle.tolerance
    )
  ) {
    throw new Error(`${entry.modelId} must pass the independent projection oracle`);
  }
  if (
    gates.jvmMechanics?.pass !== true ||
    gates.jvmMechanics.realWeights !== true ||
    gates.jvmMechanics.physicalStorageIdentity !== true ||
    gates.jvmMechanics.exactBaseContinuation !== true ||
    gates.jvmMechanics.repeatedTurns !== true ||
    gates.jvmMechanics.disabledAdapterNoOp !== true
  ) {
    throw new Error(`${entry.modelId} must pass real-weight JVM mechanics`);
  }
  if (
    gates.longContext?.pass !== true ||
    gates.longContext.cases !== 8 ||
    gates.longContext.prefixTokens !== 4_096 ||
    gates.longContext.physicalStorageIdentity !== true ||
    !Number.isInteger(gates.longContext.nativeCorrectCases) ||
    gates.longContext.nativeCorrectCases < 6 ||
    gates.longContext.nativeCorrectCases > gates.longContext.cases ||
    !requirePositiveInteger(gates.longContext.retainedNativeCorrectCases) ||
    gates.longContext.retainedNativeCorrectCases > gates.longContext.cases ||
    gates.longContext.retainedNativeCorrectCases !==
      gates.longContext.nativeCorrectCases ||
    gates.longContext.exactBaseOutput !== true ||
    gates.longContext.correctTool !== true
  ) {
    throw new Error(`${entry.modelId} must pass the fixed long-context gate`);
  }
  if (
    gates.performanceAndMemory?.pass !== true ||
    gates.performanceAndMemory.physicalSharing !== true ||
    gates.performanceAndMemory.tokenExactAtAllTiers !== true ||
    gates.performanceAndMemory.memoryComplete !== true ||
    gates.performanceAndMemory.crossoverPrefixTokens !==
      entry.minimumSharedPrefixTokens ||
    !sameMembers(gates.performanceAndMemory.prefixTiers, [256, 1_024, 4_096]) ||
    gates.performanceAndMemory.recomputedIndependence !== true ||
    gates.performanceAndMemory.jvmNativeMemoryAvailable !== true ||
    !(gates.performanceAndMemory.fourKImprovement >= 0.2) ||
    !(gates.performanceAndMemory.peakRssBytes > 0)
  ) {
    throw new Error(
      `${entry.modelId} must bind passing physical-sharing performance and memory evidence`,
    );
  }
  await requireReleasedModelsArtifacts(
    entry,
    gates.modelsArtifact,
    loadReleasedArtifact,
  );
  await requireCleanHostRun(
    entry,
    gates.cleanHostRun,
    evidenceRevision,
    gates.modelsArtifact.version,
    loadEvidenceFile,
  );
}

/**
 * Gates for a publisher-trained activated specialist that Models runs unchanged. There is no
 * training manifest to bind, so provenance pins the upstream repository, revision, adapter,
 * configuration, model card, and tokenizer files instead. Task correctness is a frozen window of
 * at least two public datasets scored with balanced accuracy against the unadapted base, and the
 * component is usable only through the Models Java activated API; no framework surface is claimed.
 */
function requireUpstreamRagSpecialistGates(entry, report, gates) {
  if (report.specialistKind !== UPSTREAM_RAG_SPECIALIST) {
    throw new Error(`${entry.modelId} report must declare ${UPSTREAM_RAG_SPECIALIST}`);
  }
  requireUpstreamProvenance(entry, gates?.provenance);
  requireAnswerabilityWindow(entry, gates);
  requireRagSpecialistRuntimeGates(entry, gates);
}

function requireUpstreamProvenance(entry, provenance) {
  if (
    provenance?.pass !== true ||
    provenance.upstream !== true ||
    typeof provenance.upstreamRepository !== "string" ||
    provenance.upstreamRepository.length === 0 ||
    !/^[a-f0-9]{40}$/.test(provenance.upstreamRevision ?? "") ||
    !SHA256.test(provenance.adapterSha256 ?? "") ||
    !SHA256.test(provenance.adapterConfigSha256 ?? "") ||
    !SHA256.test(provenance.modelCardSha256 ?? "") ||
    typeof provenance.license !== "string" ||
    provenance.license.length === 0 ||
    !Array.isArray(provenance.tokenizerFiles) ||
    provenance.tokenizerFiles.length === 0 ||
    provenance.tokenizerFiles.some(
      (file) => typeof file?.name !== "string" || !SHA256.test(file.sha256 ?? ""),
    )
  ) {
    throw new Error(`${entry.modelId} provenance must pin the upstream adapter and tokenizer`);
  }
}

/**
 * The frozen answerability window shared by every RAG specialist kind: at least two public suites
 * of at least 100 cases, every completion structured, balanced accuracy at least 0.80 and no worse
 * than the unadapted base, physical sharing on every case, and a kernel arm proven identical to
 * pure Java.
 */
function requireAnswerabilityWindow(entry, gates) {
  const task = gates?.taskCorrectness;
  if (
    task?.pass !== true ||
    !SHA256.test(task.windowSha256 ?? "") ||
    task.promptOracleIdentical !== true ||
    typeof task.backend !== "string" ||
    !Array.isArray(task.suites) ||
    task.suites.length < MINIMUM_WINDOW_SUITES ||
    task.suites.some(
      (suite) =>
        typeof suite?.name !== "string" ||
        !Number.isInteger(suite.cases) ||
        suite.cases < MINIMUM_WINDOW_CASES ||
        suite.structuredRate !== 1 ||
        !(suite.balancedAccuracy >= MINIMUM_BALANCED_ACCURACY) ||
        !(typeof suite.baseBalancedAccuracy === "number") ||
        !(suite.balancedAccuracy >= suite.baseBalancedAccuracy) ||
        suite.physicallySharedCases !== suite.cases,
    )
  ) {
    throw new Error(
      `${entry.modelId} taskCorrectness does not meet the fixed answerability window thresholds`,
    );
  }
  if (task.backend !== "pure-java") {
    const identity = gates.kernelIdentity;
    if (
      identity?.pass !== true ||
      identity.backend !== task.backend ||
      !Number.isInteger(identity.casesPerSuitePerArm) ||
      identity.casesPerSuitePerArm < MINIMUM_KERNEL_IDENTITY_CASES ||
      identity.identicalOutputs !== true
    ) {
      throw new Error(
        `${entry.modelId} window ran on ${task.backend} without proven identity to pure Java`,
      );
    }
  }
}

/** Real-weight conformance, mechanics, long-context, and sharing gates of every RAG specialist. */
function requireRagSpecialistRuntimeGates(entry, gates) {
  if (
    gates.plainJava?.pass !== true ||
    gates.plainJava.realWeights !== true ||
    typeof gates.plainJava.conformanceOracle !== "string" ||
    !(gates.plainJava.greedyOracles >= 2) ||
    gates.plainJava.markerRoundTrip !== true
  ) {
    throw new Error(`${entry.modelId} must pass real-weight plain Java conformance`);
  }
  if (
    gates.jvmMechanics?.pass !== true ||
    gates.jvmMechanics.realWeights !== true ||
    gates.jvmMechanics.physicalStorageIdentity !== true ||
    gates.jvmMechanics.exactBaseContinuation !== true ||
    gates.jvmMechanics.disabledAdapterNoOp !== true ||
    gates.jvmMechanics.batchedPrefillIdentity !== true
  ) {
    throw new Error(`${entry.modelId} must pass real-weight JVM mechanics`);
  }
  if (
    gates.longContext?.pass !== true ||
    gates.longContext.cases !== 8 ||
    gates.longContext.prefixTokens !== 4_096 ||
    gates.longContext.physicalStorageIdentity !== true ||
    !Number.isInteger(gates.longContext.nativeCorrectCases) ||
    gates.longContext.nativeCorrectCases < 6 ||
    gates.longContext.nativeCorrectCases > gates.longContext.cases ||
    gates.longContext.retainedNativeCorrectCases !==
      gates.longContext.nativeCorrectCases ||
    gates.longContext.exactBaseOutput !== true ||
    !Number.isInteger(gates.longContext.specialistCorrectCases) ||
    gates.longContext.specialistCorrectCases < 6
  ) {
    throw new Error(`${entry.modelId} must pass the fixed answerability long-context gate`);
  }
  if (
    gates.performanceAndMemory?.pass !== true ||
    gates.performanceAndMemory.physicalSharing !== true ||
    gates.performanceAndMemory.tokenExactAtAllTiers !== true ||
    gates.performanceAndMemory.memoryComplete !== true ||
    gates.performanceAndMemory.crossoverPrefixTokens !==
      entry.minimumSharedPrefixTokens ||
    !sameMembers(gates.performanceAndMemory.prefixTiers, [256, 1_024, 4_096]) ||
    gates.performanceAndMemory.recomputedIndependence !== true ||
    gates.performanceAndMemory.jvmNativeMemoryAvailable !== true ||
    !(gates.performanceAndMemory.fourKImprovement >= 0.2) ||
    !(gates.performanceAndMemory.peakRssBytes > 0)
  ) {
    throw new Error(
      `${entry.modelId} must bind passing physical-sharing performance and memory evidence`,
    );
  }
}

function isNonBlankString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function pinnedTrainingFile(provenance, name) {
  const file = provenance[name];
  const prefix =
    `https://raw.githubusercontent.com/${provenance.trainingRepository}/` +
    `${provenance.trainingRevision}/`;
  return (
    file !== null &&
    typeof file === "object" &&
    typeof file.uri === "string" &&
    file.uri.startsWith(prefix) &&
    file.uri.length > prefix.length &&
    !file.uri.slice(prefix.length).split("/").some((part) => part === "" || part === "..") &&
    SHA256.test(file.sha256 ?? "") &&
    requirePositiveInteger(file.sizeBytes)
  );
}

async function loadPinnedTrainingFile(entry, evidence, kind, label, loadEvidenceFile) {
  const bytes = await loadEvidenceFile({ entry, evidence, kind });
  if (!(bytes instanceof Uint8Array)) {
    throw new Error(`${entry.modelId} ${label} loader did not return bytes`);
  }
  if (
    bytes.byteLength !== evidence.sizeBytes ||
    createHash("sha256").update(bytes).digest("hex") !== evidence.sha256
  ) {
    throw new Error(`${entry.modelId} ${label} bytes do not match immutable evidence`);
  }
  return bytes;
}

/**
 * Provenance of an adapter we trained ourselves. It is not upstream, so it must not borrow the
 * upstream fields; instead it pins the publisher, the training repository and the commit holding
 * the trainer, data preparation, and manifests, and it byte-verifies the training and
 * prepared-data manifests at that commit. The adapter weights and configuration must be the
 * files the training manifest recorded, and the weights must be the file the catalog publishes.
 */
async function requireFirstPartyProvenance(entry, provenance, model, loadEvidenceFile) {
  if (
    provenance?.pass !== true ||
    provenance.upstream !== false ||
    provenance.upstreamRepository !== undefined ||
    provenance.upstreamRevision !== undefined ||
    !isNonBlankString(provenance.publisher) ||
    !GITHUB_REPOSITORY.test(provenance.trainingRepository ?? "") ||
    !COMMIT.test(provenance.trainingRevision ?? "") ||
    !pinnedTrainingFile(provenance, "trainingManifest") ||
    !pinnedTrainingFile(provenance, "preparedDataManifest") ||
    !SHA256.test(provenance.adapterSha256 ?? "") ||
    !SHA256.test(provenance.adapterConfigSha256 ?? "") ||
    !SHA256.test(provenance.modelCardSha256 ?? "") ||
    !isNonBlankString(provenance.license) ||
    !Array.isArray(provenance.trainingDataLicenses) ||
    provenance.trainingDataLicenses.length === 0 ||
    provenance.trainingDataLicenses.some(
      (item) => !isNonBlankString(item?.dataset) || !isNonBlankString(item?.license),
    ) ||
    !Array.isArray(provenance.tokenizerFiles) ||
    provenance.tokenizerFiles.length === 0 ||
    provenance.tokenizerFiles.some(
      (file) => typeof file?.name !== "string" || !SHA256.test(file.sha256 ?? ""),
    )
  ) {
    throw new Error(
      `${entry.modelId} provenance must pin the first-party training commit, manifests, adapter, data licenses, and tokenizer`,
    );
  }
  const trainingBytes = await loadPinnedTrainingFile(
    entry,
    provenance.trainingManifest,
    "training-manifest",
    "training manifest",
    loadEvidenceFile,
  );
  await loadPinnedTrainingFile(
    entry,
    provenance.preparedDataManifest,
    "prepared-data-manifest",
    "prepared-data manifest",
    loadEvidenceFile,
  );
  let manifest;
  try {
    manifest = JSON.parse(Buffer.from(trainingBytes).toString("utf8"));
  } catch (error) {
    throw new Error(`${entry.modelId} training manifest is not JSON`, { cause: error });
  }
  const recorded = manifest?.adapterFiles;
  if (recorded?.["adapter_model.safetensors"]?.sha256 !== provenance.adapterSha256) {
    throw new Error(`${entry.modelId} adapter weights do not match the training manifest`);
  }
  if (recorded?.["adapter_config.json"]?.sha256 !== provenance.adapterConfigSha256) {
    throw new Error(`${entry.modelId} adapter configuration does not match the training manifest`);
  }
  const weights = model.files.find((file) => file.role === "adapter-weights");
  if (weights?.sha256 !== provenance.adapterSha256) {
    throw new Error(`${entry.modelId} adapter weights do not match the catalog bundle`);
  }
}

/**
 * A fine-tune is admitted only when it beats its base, so every suite must be strictly better
 * than the unadapted base. A suite scored on confirmed labels must bind the label set and keep
 * its dataset-label score beside the confirmed one; ModelJars does not re-score either.
 */
function requireFirstPartyWindow(entry, gates) {
  for (const suite of gates.taskCorrectness.suites) {
    if (!(suite.balancedAccuracy > suite.baseBalancedAccuracy)) {
      throw new Error(
        `${entry.modelId} ${suite.name} must strictly beat its base balanced accuracy`,
      );
    }
    if (suite.labelSource !== undefined && !LABEL_SOURCES.has(suite.labelSource)) {
      throw new Error(`${entry.modelId} ${suite.name} declares an unknown labelSource`);
    }
    if (
      suite.labelSource === "confirmed" &&
      (!SHA256.test(suite.labelsSha256 ?? "") ||
        typeof suite.originalBalancedAccuracy !== "number" ||
        !Number.isFinite(suite.originalBalancedAccuracy))
    ) {
      throw new Error(
        `${entry.modelId} ${suite.name} confirmed labels must bind labelsSha256 and originalBalancedAccuracy`,
      );
    }
  }
}

/**
 * Gates for an activated RAG specialist Integrallis trained. Every task, mechanics, long-context,
 * and sharing requirement of an upstream specialist applies unchanged; provenance binds the
 * training commit instead of an upstream publisher, and each suite must strictly beat the base.
 */
async function requireFirstPartyRagSpecialistGates(
  entry,
  report,
  gates,
  model,
  loadEvidenceFile,
) {
  if (report.specialistKind !== FIRST_PARTY_RAG_SPECIALIST) {
    throw new Error(`${entry.modelId} report must declare ${FIRST_PARTY_RAG_SPECIALIST}`);
  }
  await requireFirstPartyProvenance(entry, gates?.provenance, model, loadEvidenceFile);
  requireAnswerabilityWindow(entry, gates);
  requireFirstPartyWindow(entry, gates);
  requireRagSpecialistRuntimeGates(entry, gates);
}

export async function validateComponentEvidence({
  qualifications,
  models,
  loadReport,
  loadReleasedArtifact,
  loadEvidenceFile,
}) {
  requireDocuments(qualifications, models);
  if (typeof loadReport !== "function") {
    throw new Error("A report loader is required for component evidence");
  }
  if (typeof loadReleasedArtifact !== "function") {
    throw new Error(
      "A released Models artifact loader is required for component evidence",
    );
  }
  if (typeof loadEvidenceFile !== "function") {
    throw new Error("An evidence-file loader is required for component evidence");
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
    if (
      entry.baseArtifactSha256 !== base.sha256 ||
      entry.baseArtifactSizeBytes !== base.sizeBytes
    ) {
      throw new Error(`${entry.modelId} qualification base identity is stale`);
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
    await requireReport(
      entry,
      report,
      model,
      base,
      qualifications.modelsRevision,
      qualifications.evidenceRevision,
      loadReleasedArtifact,
      loadEvidenceFile,
    );
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
    loadReleasedArtifact: async ({ artifact, kind }) => {
      const response = await fetch(artifact[kind].uri, { redirect: "error" });
      if (!response.ok) {
        throw new Error(
          `Could not fetch ${artifact[kind].uri}: HTTP ${response.status}`,
        );
      }
      return new Uint8Array(await response.arrayBuffer());
    },
    loadEvidenceFile: async ({ evidence }) => {
      const response = await fetch(evidence.uri, { redirect: "error" });
      if (!response.ok) {
        throw new Error(`Could not fetch ${evidence.uri}: HTTP ${response.status}`);
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
