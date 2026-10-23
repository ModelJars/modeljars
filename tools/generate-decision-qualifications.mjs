#!/usr/bin/env node
/**
 * Builds `catalog/decision-qualifications.json` entries from JevBench calibration reports.
 *
 * Entries are derived, never typed. Every field comes out of the report the models repo commits,
 * and the two fields the catalog also knows -- the artifact digest and its size -- are checked
 * against `catalog/models.json` rather than copied, because a qualification whose digest disagrees
 * with the catalog is a claim about a different file than the one users download.
 *
 * Deliberately not shaped like the RAG or reranking manifests. A decision model has no retrieval,
 * no streamed tokens and no abstention, so there is no latency percentile or answer rate to carry.
 * What it has is an accuracy, the chance floor that makes that accuracy readable, and a tier
 * saying how far its published confidence travels.
 *
 * Usage:
 *   node tools/generate-decision-qualifications.mjs \
 *     --models-repo ../models --models-revision <40-char sha> \
 *     --backend-version models-0.5.3 \
 *     --reports <id>=<path> [--reports ...] \
 *     [--write]
 *
 * Without --write it prints what it would add and exits non-zero, so CI can gate on staleness.
 */
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const POLICY_VERSION = "decision-calibration-v2";

const REQUIRED_REPORT_FIELDS = [
  "policy",
  "cohort",
  "cohortSha256",
  "artifactSha256",
  "artifactSizeBytes",
  "cases",
  "chanceAccuracy",
  "accuracy",
  "calibrationError",
  "uncalibratedAnswers",
  "uncalibratedBucketsExercised",
  "tier",
  "publishable",
  "exportsConfidenceQuality",
  "byType",
  "environment",
];

const TIERS = new Set(["CALIBRATED_DECISION", "RANKED_DECISION"]);

function parseArguments(argv) {
  const values = new Map();
  const reports = [];
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    if (flag === "--write") {
      values.set("--write", true);
      continue;
    }
    if (flag === "--reports") {
      reports.push(argv[index + 1]);
      index += 1;
      continue;
    }
    if (flag.startsWith("--")) {
      values.set(flag, argv[index + 1]);
      index += 1;
    }
  }
  values.set("reports", reports);
  return values;
}

/** One entry, in the field order the manifest uses. */
export function entryFromReport(modelId, report, reportPath, reportSha256, backendVersion, model) {
  for (const field of REQUIRED_REPORT_FIELDS) {
    if (report[field] === undefined) {
      throw new Error(`${modelId}: report is missing ${field}`);
    }
  }
  if (report.policy !== POLICY_VERSION) {
    throw new Error(`${modelId}: report policy ${report.policy} is not ${POLICY_VERSION}`);
  }
  if (report.publishable !== true || !TIERS.has(report.tier)) {
    throw new Error(
      `${modelId}: report tier ${report.tier} (publishable=${report.publishable}) does not publish`,
    );
  }
  // The report states both the tier and what the tier means. Deriving one from the other here
  // would hide a disagreement in the report itself, which is the thing worth catching.
  const exportsConfidenceQuality = report.tier === "CALIBRATED_DECISION";
  if (report.exportsConfidenceQuality !== exportsConfidenceQuality) {
    throw new Error(
      `${modelId}: report says exportsConfidenceQuality=${report.exportsConfidenceQuality} ` +
        `for tier ${report.tier}`,
    );
  }
  if (exportsConfidenceQuality && !Number.isFinite(report.calibrationError)) {
    throw new Error(`${modelId}: CALIBRATED_DECISION with no measured calibration error`);
  }
  if (!(report.accuracy > report.chanceAccuracy)) {
    throw new Error(
      `${modelId}: accuracy ${report.accuracy} does not beat chance ${report.chanceAccuracy}`,
    );
  }
  if (report.artifactSha256 !== model.sha256) {
    throw new Error(
      `${modelId}: report artifactSha256 ${report.artifactSha256} does not match catalog ${model.sha256}`,
    );
  }
  if (report.artifactSizeBytes !== model.sizeBytes) {
    throw new Error(
      `${modelId}: report artifactSizeBytes ${report.artifactSizeBytes} does not match catalog ${model.sizeBytes}`,
    );
  }
  return {
    modelId,
    model: model.name,
    backend: "pure-java",
    backendVersion,
    tier: report.tier,
    cohort: report.cohort,
    cohortSha256: report.cohortSha256,
    artifactSha256: report.artifactSha256,
    artifactSizeBytes: report.artifactSizeBytes,
    report: reportPath,
    reportSha256,
    cases: report.cases,
    chanceAccuracy: report.chanceAccuracy,
    accuracy: report.accuracy,
    byType: report.byType,
    calibrationError: report.calibrationError,
    uncalibratedAnswers: report.uncalibratedAnswers,
    uncalibratedBucketsExercised: report.uncalibratedBucketsExercised,
    limitations: report.limitations ?? [],
    environment: report.environment,
  };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const modelsRepo = options.get("--models-repo");
  const modelsRevision = options.get("--models-revision");
  const backendVersion = options.get("--backend-version");
  if (!modelsRepo || !modelsRevision || !backendVersion) {
    throw new Error("--models-repo, --models-revision and --backend-version are all required");
  }
  if (!/^[0-9a-f]{40}$/.test(modelsRevision)) {
    throw new Error(`--models-revision must be a 40-character commit, got ${modelsRevision}`);
  }

  const pairs = options.get("reports").map((value) => {
    const split = value.indexOf("=");
    return [value.slice(0, split), value.slice(split + 1)];
  });

  const catalog = JSON.parse(await readFile("catalog/models.json", "utf8"));
  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  const manifestPath = "catalog/decision-qualifications.json";
  let manifest;
  try {
    manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
    manifest = { schemaVersion: 1, policyVersion: POLICY_VERSION, entries: [] };
  }
  const byId = new Map(manifest.entries.map((entry) => [entry.modelId, entry]));

  const added = [];
  for (const [modelId, reportRelative] of pairs) {
    const model = modelsById.get(modelId);
    if (model === undefined) {
      throw new Error(`${modelId} is not in catalog/models.json`);
    }
    const raw = await readFile(path.join(modelsRepo, reportRelative));
    const report = JSON.parse(raw.toString("utf8"));
    const reportSha256 = createHash("sha256").update(raw).digest("hex");
    const entry = entryFromReport(
      modelId,
      report,
      reportRelative,
      reportSha256,
      backendVersion,
      model,
    );
    if (byId.has(modelId)) {
      if (JSON.stringify(byId.get(modelId)) === JSON.stringify(entry)) {
        continue;
      }
      throw new Error(
        `${modelId} already has a different entry; a published qualification is not rewritten in place`,
      );
    }
    byId.set(modelId, entry);
    added.push(modelId);
  }

  const entries = [...byId.values()].sort((left, right) =>
    left.modelId.localeCompare(right.modelId),
  );
  const next = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    policyVersion: POLICY_VERSION,
    modelsRevision,
    entries,
  };

  if (added.length === 0) {
    console.log(`${manifestPath} already carries every report given; nothing to add`);
    return;
  }
  if (!options.get("--write")) {
    console.log(`${manifestPath} would gain ${added.length} entries:`);
    for (const id of added) {
      console.log(`  ${id}`);
    }
    process.exitCode = 1;
    return;
  }
  await writeFile(manifestPath, `${JSON.stringify(next, null, 2)}\n`);
  console.log(`${manifestPath}: +${added.length} entries, ${entries.length} total`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
