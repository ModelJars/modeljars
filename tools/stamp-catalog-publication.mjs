#!/usr/bin/env node
/**
 * Records `catalogPublishedAt` on newly qualified catalog models.
 *
 * `catalogPublishedAt` is the instant a model's marker was first published publicly, and
 * `build.gradle.kts` refuses a qualified model without one. It is a **first** publication instant,
 * so this never overwrites an existing value: moving one would rewrite when an already-public
 * marker appeared. Models are matched by id and the field is inserted after `sizeBytes`, which is
 * where every existing entry carries it.
 *
 * Usage:
 *   node tools/stamp-catalog-publication.mjs --at 2026-10-09T10:00:00Z \
 *     [--ids a,b,c | --ids-file FILE] [--write]
 *
 * With no ids, every qualified model missing the field is stamped — which is exactly the set the
 * build gate complains about.
 */
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

const QUALIFICATION_FILES = [
  ["catalog/qualifications.json", (entry) => entry.qualified === true],
  ["catalog/tool-qualifications.json", (entry) => entry.summary?.qualified === true],
  ["catalog/embedding-qualifications.json", (entry) => entry.qualified === true],
  ["catalog/reranking-qualifications.json", (entry) => entry.qualified === true],
  ["catalog/speech-qualifications.json", (entry) => entry.qualified === true],
  ["catalog/component-qualifications.json", (entry) => entry.qualified === true],
];

/** Inserts the field directly after sizeBytes, leaving every other key in place. */
export function withPublicationInstant(model, instant) {
  if (model.catalogPublishedAt !== undefined) {
    return model;
  }
  const next = {};
  for (const [key, value] of Object.entries(model)) {
    next[key] = value;
    if (key === "sizeBytes") {
      next.catalogPublishedAt = instant;
    }
  }
  if (next.catalogPublishedAt === undefined) {
    throw new Error(`${model.id} has no sizeBytes, so there is nowhere to record publication`);
  }
  return next;
}

/** The qualified ids, as the union across every manifest — never the sum. */
export function qualifiedIds(manifests) {
  const ids = new Set();
  for (const [manifest, isQualified] of manifests) {
    for (const entry of manifest.entries) {
      if (isQualified(entry)) {
        ids.add(entry.modelId);
      }
    }
  }
  return ids;
}

async function main() {
  const argv = process.argv.slice(2);
  const option = (flag) => {
    const index = argv.indexOf(flag);
    return index >= 0 ? argv[index + 1] : undefined;
  };
  const instant = option("--at");
  if (!instant || !INSTANT.test(instant)) {
    throw new Error("--at must be an instant like 2026-10-09T10:00:00Z");
  }
  const write = argv.includes("--write");

  let ids;
  const idsOption = option("--ids");
  const idsFile = option("--ids-file");
  if (idsOption) {
    ids = new Set(idsOption.split(",").map((value) => value.trim()).filter(Boolean));
  } else if (idsFile) {
    ids = new Set(
      (await readFile(idsFile, "utf8")).split("\n").map((line) => line.split("\t")[0].trim()).filter(Boolean),
    );
  }

  const catalog = JSON.parse(await readFile("catalog/models.json", "utf8"));
  const manifests = [];
  for (const [path, isQualified] of QUALIFICATION_FILES) {
    manifests.push([JSON.parse(await readFile(path, "utf8")), isQualified]);
  }
  const qualified = qualifiedIds(manifests);

  const stamped = [];
  const models = catalog.models.map((model) => {
    const wanted = ids === undefined ? qualified.has(model.id) : ids.has(model.id);
    if (!wanted || model.catalogPublishedAt !== undefined) {
      return model;
    }
    if (!qualified.has(model.id)) {
      throw new Error(`${model.id} is not qualified; an unqualified model is not published`);
    }
    stamped.push(model.id);
    return withPublicationInstant(model, instant);
  });

  if (stamped.length === 0) {
    console.log("Every qualified model already records catalogPublishedAt");
    return;
  }
  if (!write) {
    console.log(`${stamped.length} qualified models would be stamped ${instant}:`);
    for (const id of stamped.sort()) {
      console.log(`  ${id}`);
    }
    process.exitCode = 1;
    return;
  }
  await writeFile("catalog/models.json", `${JSON.stringify({ ...catalog, models }, null, 2)}\n`);
  console.log(`Recorded catalogPublishedAt=${instant} on ${stamped.length} models`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
