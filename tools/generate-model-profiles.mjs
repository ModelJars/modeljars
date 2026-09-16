#!/usr/bin/env node
// Regenerates catalog/model-profiles.json from files at each model's pinned revision.
//
//   npm run catalog:profiles          # rewrite the file
//   npm run catalog:profiles:check    # fail when the committed file differs from the sources
//   node tools/generate-model-profiles.mjs --write --ids a,b
//
// Sources (nothing else is consulted, nothing is guessed):
//   - https://huggingface.co/<repo>/resolve/<revision>/generation_config.json (sha256 of the bytes)
//   - the pinned GGUF header, read with HTTP ranges; its sha256 is the catalog-pinned artifact digest
// Set HF_TOKEN for gated repositories.

import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";

import { createRetryingFetch } from "./catalog-enrichment.mjs";
import { inspectGguf } from "./gguf-inspector.mjs";
import { assertGgufIdentity } from "./gguf-metadata.mjs";
import {
  MEMORY_FIT_METHOD,
  PROFILE_SCHEMA_VERSION,
  computeMemoryFit,
  generationProfile,
  kvLayoutFromGguf,
  validateModelProfiles,
} from "./model-profiles.mjs";

const catalogUrl = new URL("../catalog/models.json", import.meta.url);
const profilesUrl = new URL("../catalog/model-profiles.json", import.meta.url);
const write = process.argv.includes("--write");
const idsArgument = process.argv.find((argument) => argument.startsWith("--ids="));
const requestedIds = idsArgument ? new Set(idsArgument.slice(6).split(",")) : null;
const userAgent = { "User-Agent": "ModelJars-Model-Profiles/0.1" };
const token = process.env.HF_TOKEN || process.env.HUGGING_FACE_HUB_TOKEN;

const catalog = JSON.parse(await readFile(catalogUrl, "utf8"));
const retryingFetch = createRetryingFetch();

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function repository(model) {
  if (!model.sourceId.startsWith("hf://")) return null;
  return model.sourceId.slice("hf://".length);
}

async function fetchGenerationConfig(model) {
  const repo = repository(model);
  if (repo === null) return { status: "not a Hugging Face source" };
  const uri = `https://huggingface.co/${repo}/resolve/${model.revision}/generation_config.json`;
  const headers = { ...userAgent };
  if (token) headers.Authorization = `Bearer ${token}`;
  let response;
  try {
    response = await retryingFetch(uri, { headers });
  } catch (failure) {
    const status = /HTTP (\d+)/.exec(failure.message)?.[1];
    if (status === "404") return { status: "absent at pinned revision" };
    if (status === "401" || status === "403") {
      return { status: `unavailable (HTTP ${status}; gated, set HF_TOKEN)` };
    }
    throw failure;
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  return {
    status: "recorded",
    source: {
      kind: "hf-generation-config",
      file: "generation_config.json",
      uri,
      revision: model.revision,
      sha256: sha256(bytes),
      sha256Basis: "fetched bytes",
    },
    document: JSON.parse(bytes.toString("utf8")),
  };
}

async function inspectHeader(model) {
  const architecture = model.ggufArchitecture || model.architecture;
  const parsed = await inspectGguf(model.downloadUri, {
    fetch: retryingFetch,
    additionalFetchHeaders: userAgent,
    retainMetadataArrays: [
      "tokenizer.ggml.tokens",
      `${architecture}.attention.head_count`,
      `${architecture}.attention.head_count_kv`,
      `${architecture}.attention.sliding_window_pattern`,
    ],
  });
  assertGgufIdentity(model, parsed.metadata);
  const file = decodeURIComponent(new URL(model.downloadUri).pathname.split("/").pop());
  return {
    ...parsed,
    source: {
      kind: "gguf-metadata",
      file,
      uri: model.downloadUri,
      revision: model.revision,
      sha256: model.sha256,
      sha256Basis: "catalog-pinned artifact digest; header read by HTTP range",
    },
  };
}

async function profile(model) {
  const generates = model.capabilities.includes("text-generation");
  const coverage = { modelId: model.id };
  const entry = { modelId: model.id, artifactSha256: model.sha256, revision: model.revision };

  const header =
    model.format === "gguf" && (model.ggufArchitecture || model.architecture) !== "audiocpp"
      ? await inspectHeader(model)
      : null;

  if (generates) {
    const config = await fetchGenerationConfig(model);
    const generation = generationProfile({
      gguf: header
        ? {
            source: header.source,
            metadata: header.metadata,
            tokens: header.metadata["tokenizer.ggml.tokens"] ?? [],
          }
        : undefined,
      generationConfig: config.status === "recorded" ? config : undefined,
    });
    if (generation) entry.generation = generation;
    const missing = [];
    for (const name of ["temperature", "topP", "topK", "minP", "repetitionPenalty"]) {
      if (!generation?.sampling?.[name]) missing.push(name);
    }
    if (!generation?.eosTokenIds) missing.push("eosTokenIds");
    if (!generation?.reasoning?.openToken) missing.push("reasoningMarkers");
    if (!generation?.reasoning?.thinkingDefault) missing.push("thinkingDefault");
    coverage.generationConfig = config.status;
    coverage.ggufHeader = header ? "read" : "not a GGUF artifact";
    coverage.unsourced = missing;
  } else {
    coverage.generation = "not applicable: no text-generation capability";
  }

  if (generates && header) {
    const layout = kvLayoutFromGguf(header.metadata, header.tensorInfos);
    if (layout) {
      entry.memoryFit = computeMemoryFit(layout, model.sizeBytes);
      coverage.memoryFit = entry.memoryFit.upperBound ? "computed (upper bound)" : "computed";
    } else {
      coverage.memoryFit = "not computed: header lacks KV dimensions";
    }
  } else {
    coverage.memoryFit = generates
      ? "not applicable: not a GGUF artifact"
      : "not applicable: no generation KV cache";
  }
  return { entry: entry.generation || entry.memoryFit ? entry : null, coverage };
}

const previous = JSON.parse(await readFile(profilesUrl, "utf8").catch(() => "null"));
const targets = catalog.models.filter((model) =>
  requestedIds ? requestedIds.has(model.id) : model.catalogPublishedAt !== undefined,
);
const results = new Map();
const failures = [];
let next = 0;
async function worker() {
  while (next < targets.length) {
    const model = targets[next++];
    try {
      results.set(model.id, await profile(model));
      process.stderr.write(`[${results.size}/${targets.length}] ${model.id}\n`);
    } catch (failure) {
      failures.push(`${model.id}: ${failure.message}`);
      process.stderr.write(`FAILED ${model.id}: ${failure.message}\n`);
    }
  }
}
await Promise.all(Array.from({ length: Math.min(4, targets.length) }, worker));
if (failures.length > 0) {
  throw new Error(`Unable to profile ${failures.length} models:\n${failures.join("\n")}`);
}

// With --ids, keep untouched entries from the previous file.
const order = new Map(catalog.models.map((model, index) => [model.id, index]));
const keep = (list = []) => list.filter((item) => !results.has(item.modelId));
const byCatalogOrder = (left, right) => order.get(left.modelId) - order.get(right.modelId);
const document = {
  schemaVersion: PROFILE_SCHEMA_VERSION,
  generatedBy: "tools/generate-model-profiles.mjs",
  memoryFitMethod: MEMORY_FIT_METHOD,
  profiles: [
    ...(requestedIds ? keep(previous?.profiles) : []),
    ...[...results.values()].map((result) => result.entry).filter(Boolean),
  ].sort(byCatalogOrder),
  coverage: [
    ...(requestedIds ? keep(previous?.coverage) : []),
    ...[...results.values()].map((result) => result.coverage),
  ].sort(byCatalogOrder),
};
validateModelProfiles(JSON.parse(JSON.stringify(document)), catalog);
const serialized = `${JSON.stringify(document, null, 2)}\n`;

if (write) {
  await writeFile(profilesUrl, serialized, "utf8");
  process.stdout.write(
    `Wrote ${document.profiles.length} model profiles (${document.profiles.filter((p) => p.memoryFit).length} with memory fit).\n`,
  );
} else if (previous === null || `${JSON.stringify(previous, null, 2)}\n` !== serialized) {
  throw new Error("catalog/model-profiles.json is stale; run npm run catalog:profiles");
} else {
  process.stdout.write(`Verified ${document.profiles.length} model profiles.\n`);
}
