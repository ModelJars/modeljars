import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";

import { gguf } from "@huggingface/gguf";

import { extractGgufDimensions } from "./gguf-metadata.mjs";

const catalogPath = new URL("../catalog/models.json", import.meta.url);
const write = process.argv.includes("--write");
const concurrencyArgument = process.argv.find((argument) => argument.startsWith("--concurrency="));
const concurrency = Number.parseInt(concurrencyArgument?.split("=")[1] || "3", 10);

if (!Number.isSafeInteger(concurrency) || concurrency <= 0) {
  throw new Error("--concurrency must be a positive integer");
}

const document = JSON.parse(await readFile(catalogPath, "utf8"));
const targets = document.models.filter((model) => model.format === "gguf");
const dimensions = new Map();
const failures = [];
let nextIndex = 0;

async function inspect(model) {
  let lastFailure;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const parsed = await gguf(model.downloadUri, {
        computeParametersCount: true,
        additionalFetchHeaders: { "User-Agent": "ModelJars-Catalog-Enricher/0.1" },
      });
      const remoteArchitecture = parsed.metadata["general.architecture"];
      if (remoteArchitecture !== model.architecture) {
        throw new Error(
          `architecture mismatch: catalog=${model.architecture}, GGUF=${remoteArchitecture}`,
        );
      }
      return extractGgufDimensions(parsed.metadata, parsed.parameterCount, parsed.tensorInfos);
    } catch (failure) {
      lastFailure = failure;
      if (attempt < 3) {
        await new Promise((resolve) => setTimeout(resolve, attempt * 500));
      }
    }
  }
  throw new Error(`Unable to inspect ${model.id}: ${lastFailure?.message}`, {
    cause: lastFailure,
  });
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
      failures.push(failure);
      process.stderr.write(`FAILED ${model.id}: ${failure.message}\n`);
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
  process.stdout.write(`Updated ${targets.length} GGUF resource profiles.\n`);
} else if (differences > 0) {
  throw new Error(`${differences} GGUF resource profiles need regeneration`);
} else {
  process.stdout.write(`Verified ${targets.length} GGUF resource profiles.\n`);
}
