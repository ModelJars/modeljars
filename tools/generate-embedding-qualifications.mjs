#!/usr/bin/env node
/**
 * Builds `catalog/embedding-qualifications.json` entries from oracle-equivalence reports.
 *
 * Entries are derived, never typed. Each one is read out of the report the models repo commits,
 * and every field the catalog also knows -- the artifact digest and its size -- is checked against
 * `catalog/models.json` rather than copied, because a qualification whose digest disagrees with
 * the catalog is a claim about a different file than the one users download.
 *
 * Usage:
 *   node tools/generate-embedding-qualifications.mjs \
 *     --models-repo ../models --models-revision <40-char sha> \
 *     --backend-version models-0.3.54 \
 *     --reports <id>=<path> [--reports ...]      # or --report-list FILE with "id\tpath" lines
 *     [--write]
 *
 * Without --write it prints the entries it would add and exits non-zero if the manifest is stale,
 * so CI can gate on it the way `catalog:profiles:check` gates the profile file.
 */
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const REQUIRED_REPORT_FIELDS = [
  "workload",
  "model",
  "backend",
  "artifactSha256",
  "artifactSizeBytes",
  "probeSetSha256",
  "probes",
  "embeddingDimension",
  "pooling",
  "normalized",
  "oracleBackend",
  "oracleVersion",
  "minimumOracleCosine",
  "meanOracleCosine",
  "maxComponentDelta",
  "maxNormDeviation",
  "qualified",
];

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

/** One entry, in the field order the existing manifest uses. */
export function entryFromReport(modelId, report, reportPath, reportSha256, backendVersion, model) {
  for (const field of REQUIRED_REPORT_FIELDS) {
    if (report[field] === undefined) {
      throw new Error(`${modelId}: report is missing ${field}`);
    }
  }
  if (report.qualified !== true) {
    throw new Error(`${modelId}: report says qualified=${report.qualified}; only passes land`);
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
    model: report.model,
    backend: report.backend,
    backendVersion,
    workload: report.workload,
    probeSetSha256: report.probeSetSha256,
    artifactSha256: report.artifactSha256,
    artifactSizeBytes: report.artifactSizeBytes,
    report: reportPath,
    reportSha256,
    qualified: true,
    probes: report.probes,
    embeddingDimension: report.embeddingDimension,
    pooling: report.pooling,
    normalized: report.normalized,
    oracleBackend: report.oracleBackend,
    oracleVersion: report.oracleVersion,
    minimumOracleCosine: report.minimumOracleCosine,
    meanOracleCosine: report.meanOracleCosine,
    maxComponentDelta: report.maxComponentDelta,
    maxNormDeviation: report.maxNormDeviation,
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

  let pairs = options.get("reports").map((value) => {
    const split = value.indexOf("=");
    return [value.slice(0, split), value.slice(split + 1)];
  });
  const listPath = options.get("--report-list");
  if (listPath) {
    const lines = (await readFile(listPath, "utf8")).split("\n").filter((line) => line.trim());
    pairs = pairs.concat(lines.map((line) => line.split("\t")));
  }

  const catalog = JSON.parse(await readFile("catalog/models.json", "utf8"));
  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  const manifestPath = "catalog/embedding-qualifications.json";
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  const byId = new Map(manifest.entries.map((entry) => [entry.modelId, entry]));

  const added = [];
  for (const [modelId, reportRelative] of pairs) {
    const model = modelsById.get(modelId);
    if (model === undefined) {
      throw new Error(`${modelId} is not in catalog/models.json`);
    }
    const absolute = path.join(modelsRepo, reportRelative);
    const raw = await readFile(absolute);
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
      const existing = JSON.stringify(byId.get(modelId));
      if (existing === JSON.stringify(entry)) {
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
    ...manifest,
    generatedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    modelsRevision,
    qualifiedModels: entries.filter((entry) => entry.qualified === true).length,
    rejectedModels: entries.filter((entry) => entry.qualified !== true).length,
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
    console.log(`qualifiedModels ${manifest.qualifiedModels} -> ${next.qualifiedModels}`);
    process.exitCode = 1;
    return;
  }
  await writeFile(manifestPath, `${JSON.stringify(next, null, 2)}\n`);
  console.log(
    `${manifestPath}: +${added.length} entries, qualifiedModels ${manifest.qualifiedModels} -> ${next.qualifiedModels}`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
