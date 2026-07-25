import { execFile } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";
import { promisify } from "node:util";

import { gguf } from "@huggingface/gguf";

import {
  changedGgufModelIds,
  createRetryingFetch,
} from "./catalog-enrichment.mjs";
import { extractGgufDimensions } from "./gguf-metadata.mjs";

const catalogPath = new URL("../catalog/models.json", import.meta.url);
const write = process.argv.includes("--write");
const concurrencyArgument = process.argv.find((argument) => argument.startsWith("--concurrency="));
const concurrency = Number.parseInt(concurrencyArgument?.split("=")[1] || "3", 10);
const changedFromArgument = process.argv.find((argument) => argument.startsWith("--changed-from="));
const changedFrom = changedFromArgument?.slice("--changed-from=".length);

if (!Number.isSafeInteger(concurrency) || concurrency <= 0) {
  throw new Error("--concurrency must be a positive integer");
}
if (changedFromArgument && !changedFrom) {
  throw new Error("--changed-from must name a Git revision");
}

const document = JSON.parse(await readFile(catalogPath, "utf8"));
let targetIds;
if (changedFrom) {
  const runGit = promisify(execFile);
  const { stdout } = await runGit(
    "git",
    ["show", `${changedFrom}:catalog/models.json`],
    { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 },
  );
  targetIds = new Set(changedGgufModelIds(JSON.parse(stdout), document));
}
const targets = document.models.filter(
  (model) => model.format === "gguf" && (!targetIds || targetIds.has(model.id)),
);
const dimensions = new Map();
const failures = [];
let nextIndex = 0;
const retryingFetch = createRetryingFetch();

async function inspect(model) {
  const parsed = await gguf(model.downloadUri, {
    computeParametersCount: true,
    fetch: retryingFetch,
    additionalFetchHeaders: { "User-Agent": "ModelJars-Catalog-Enricher/0.1" },
  });
  const remoteArchitecture = parsed.metadata["general.architecture"];
  if (remoteArchitecture !== model.architecture) {
    throw new Error(
      `architecture mismatch: catalog=${model.architecture}, GGUF=${remoteArchitecture}`,
    );
  }
  return extractGgufDimensions(parsed.metadata, parsed.parameterCount, parsed.tensorInfos);
}

async function worker() {
  while (nextIndex < targets.length) {
    const index = nextIndex;
    nextIndex += 1;
    const model = targets[index];
    try {
      const profile = await inspect(model);
      dimensions.set(model.id, profile);
      process.stderr.write(`[${dimensions.size}/${targets.length}] ${model.id}\n`);
    } catch (failure) {
      const wrapped = new Error(`Unable to inspect ${model.id}: ${failure.message}`, {
        cause: failure,
      });
      failures.push(wrapped);
      process.stderr.write(`FAILED ${model.id}: ${wrapped.message}\n`);
    }
  }
}

await Promise.all(Array.from({ length: Math.min(concurrency, targets.length) }, () => worker()));

if (failures.length > 0) {
  throw new AggregateError(failures, `Unable to inspect ${failures.length} GGUF artifacts`);
}

let differences = 0;
for (const model of targets) {
  const actual = dimensions.get(model.id);
  if (JSON.stringify(model.dimensions) !== JSON.stringify(actual)) {
    differences += 1;
    if (!write) {
      process.stderr.write(`Outdated dimensions: ${model.id}\n`);
    }
  }
  model.dimensions = actual;
}

if (write) {
  await writeFile(catalogPath, `${JSON.stringify(document, null, 2)}\n`, "utf8");
  process.stdout.write(`Updated ${targets.length} selected GGUF resource profiles.\n`);
} else if (differences > 0) {
  throw new Error(`${differences} GGUF resource profiles need regeneration`);
} else {
  process.stdout.write(`Verified ${targets.length} selected GGUF resource profiles.\n`);
}
