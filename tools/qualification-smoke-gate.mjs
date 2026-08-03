#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const SHA256 = /^[a-f0-9]{64}$/;
const GIT_REVISION = /^[a-f0-9]{40}$/;

function requireDocument(value, schemaVersion, collection, label) {
  if (
    value === null ||
    typeof value !== "object" ||
    value.schemaVersion !== schemaVersion ||
    !Array.isArray(value[collection])
  ) {
    throw new Error(`${label} must use schemaVersion ${schemaVersion} and contain ${collection}`);
  }
}

function qualificationKey(entry) {
  return `${entry.modelId}/${entry.backend}`;
}

function evidenceIdentity(entry) {
  return JSON.stringify({
    qualified: entry.qualified,
    verdict: entry.verdict,
    artifactSha256: entry.artifactSha256,
    artifactSizeBytes: entry.artifactSizeBytes,
    backendVersion: entry.backendVersion,
    report: entry.report,
    reportSha256: entry.reportSha256,
    workload: entry.workload,
    promptTemplate: entry.promptTemplate,
  });
}

function requireSha(value, label) {
  if (!SHA256.test(value ?? "")) {
    throw new Error(`${label} must be a lowercase SHA-256`);
  }
}

function requireNormalizedReportPath(value, label) {
  if (
    typeof value !== "string" ||
    value.startsWith("/") ||
    value.includes("\\") ||
    value.split("/").some((part) => !part || part === "." || part === "..")
  ) {
    throw new Error(`${label} must be a normalized repository-relative path`);
  }
  if (!value.split("/").includes("default-correctness")) {
    throw new Error(`${label} must identify default-correctness evidence`);
  }
}

function assertSmokeMetadata(entry) {
  const label = `${qualificationKey(entry)}.defaultConfigurationSmoke`;
  const smoke = entry.defaultConfigurationSmoke;
  if (smoke === null || typeof smoke !== "object" || Array.isArray(smoke)) {
    throw new Error(`${label} is required for a new or changed qualification`);
  }
  if (smoke.configuration !== "library-defaults") {
    throw new Error(`${label}.configuration must be library-defaults`);
  }
  if (smoke.backend !== entry.backend) {
    throw new Error(`${label}.backend must match the qualified backend`);
  }
  if (smoke.artifactSha256 !== entry.artifactSha256) {
    throw new Error(`${label}.artifactSha256 must match the qualified artifact`);
  }
  requireNormalizedReportPath(smoke.report, `${label}.report`);
  requireSha(smoke.reportSha256, `${label}.reportSha256`);
  if (!Number.isSafeInteger(smoke.totalAttempts) || smoke.totalAttempts < 1) {
    throw new Error(`${label}.totalAttempts must be a positive integer`);
  }
  if (smoke.successfulAttempts !== smoke.totalAttempts) {
    throw new Error(`${label} must record a successful result for every attempt`);
  }
  if (!Array.isArray(smoke.tuningSystemProperties) || smoke.tuningSystemProperties.length !== 0) {
    throw new Error(`${label}.tuningSystemProperties must be empty`);
  }
  return smoke;
}

function assertReport(entry, smoke, bytes) {
  const actualSha = createHash("sha256").update(bytes).digest("hex");
  if (actualSha !== smoke.reportSha256) {
    throw new Error(`${qualificationKey(entry)} default smoke report SHA-256 does not match`);
  }

  let report;
  try {
    report = JSON.parse(Buffer.from(bytes).toString("utf8"));
  } catch (error) {
    throw new Error(`${qualificationKey(entry)} default smoke report is not JSON`, { cause: error });
  }
  const valid =
    report.backend === entry.backend &&
    report.modelId === entry.modelId &&
    report.artifactSha256 === entry.artifactSha256 &&
    report.settings?.warmups === 0 &&
    report.settings?.iterations === 1 &&
    report.settings?.generationControls?.promptCache === "longest-common-prefix" &&
    report.summary?.totalAttempts === smoke.totalAttempts &&
    report.summary?.successfulAttempts === smoke.successfulAttempts &&
    report.summary?.correctAnswerRate === 1 &&
    report.summary?.abstentionAccuracy === 1 &&
    Array.isArray(report.failures) &&
    report.failures.length === 0;
  if (!valid) {
    throw new Error(
      `${qualificationKey(entry)} default smoke must use library defaults, exercise the prefix cache, and pass every attempt`,
    );
  }
  if (
    entry.backend === "rust-ffm" &&
    report.backendDiagnostics?.environment?.["native-quantized-decode"] !== "false"
  ) {
    throw new Error(
      `${qualificationKey(entry)} default smoke enabled models.native.quantizedDecode`,
    );
  }
}

export async function validateQualificationSmokeGate({
  previousQualifications,
  currentQualifications,
  currentCatalog,
  loadReport,
}) {
  requireDocument(previousQualifications, 1, "entries", "Previous qualifications");
  requireDocument(currentQualifications, 1, "entries", "Current qualifications");
  requireDocument(currentCatalog, 2, "models", "Current catalog");
  if (!GIT_REVISION.test(currentQualifications.modelsRevision ?? "")) {
    throw new Error("Current qualifications modelsRevision must be a 40-character Git commit");
  }

  const catalogIds = new Set(currentCatalog.models.map((model) => model.id));
  const previous = new Map(
    previousQualifications.entries.map((entry) => [qualificationKey(entry), entry]),
  );
  const checked = [];
  for (const entry of currentQualifications.entries) {
    if (entry.qualified !== true || !catalogIds.has(entry.modelId)) continue;
    const prior = previous.get(qualificationKey(entry));
    if (prior !== undefined && evidenceIdentity(prior) === evidenceIdentity(entry)) continue;

    const smoke = assertSmokeMetadata(entry);
    if (loadReport !== undefined) {
      const bytes = await loadReport({
        entry,
        revision: currentQualifications.modelsRevision,
        report: smoke.report,
      });
      assertReport(entry, smoke, bytes);
    }
    checked.push(qualificationKey(entry));
  }
  return checked;
}

function parseArguments(args) {
  let verifyRemote = false;
  const values = new Map();
  for (let index = 0; index < args.length; index += 1) {
    const name = args[index];
    if (name === "--verify-remote") {
      verifyRemote = true;
      continue;
    }
    const value = args[index + 1];
    if (!["--previous", "--current", "--catalog"].includes(name) || value === undefined) {
      throw new Error(
        "Usage: qualification-smoke-gate.mjs --previous FILE --current FILE --catalog FILE [--verify-remote]",
      );
    }
    values.set(name, value);
    index += 1;
  }
  for (const required of ["--previous", "--current", "--catalog"]) {
    if (!values.has(required)) throw new Error(`${required} is required`);
  }
  return { values, verifyRemote };
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function main() {
  const { values, verifyRemote } = parseArguments(process.argv.slice(2));
  const loadReport = verifyRemote
    ? async ({ revision, report }) => {
        const url = `https://raw.githubusercontent.com/integrallis/models/${revision}/${report}`;
        const response = await fetch(url);
        if (!response.ok) throw new Error(`Could not fetch ${url}: HTTP ${response.status}`);
        return new Uint8Array(await response.arrayBuffer());
      }
    : undefined;
  const checked = await validateQualificationSmokeGate({
    previousQualifications: await readJson(values.get("--previous")),
    currentQualifications: await readJson(values.get("--current")),
    currentCatalog: await readJson(values.get("--catalog")),
    loadReport,
  });
  process.stdout.write(`Validated ${checked.length} new or changed default-configuration smoke(s)\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main();
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
    process.exitCode = 1;
  }
}
