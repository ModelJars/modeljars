#!/usr/bin/env node

// Every published marker JAR carries a snapshot of the qualification manifests it was built from,
// and the runtime merges those snapshots with the catalog bundled in the ModelJars JAR by
// generatedAt: a later instant wins, and two snapshots at the same instant must agree. A manifest
// edited without advancing generatedAt therefore makes every marker already published from the
// earlier content unloadable beside the new runtime ("Conflicting ... at the same generation
// instant"). This gate compares each manifest with a base revision and requires any change to
// advance generatedAt.

import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

export const QUALIFICATION_MANIFESTS = [
  "catalog/qualifications.json",
  "catalog/tool-qualifications.json",
  "catalog/embedding-qualifications.json",
  "catalog/reranking-qualifications.json",
  "catalog/speech-qualifications.json",
  "catalog/component-qualifications.json",
];

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

function instant(manifest, label) {
  const millis = Date.parse(manifest?.generatedAt ?? "");
  if (Number.isNaN(millis)) {
    throw new Error(`${label} must declare a parseable generatedAt`);
  }
  return millis;
}

/**
 * Returns the violations for one manifest. A manifest absent at the base revision is new and has
 * nothing published to conflict with.
 */
export function generationViolations(path, base, head) {
  if (base === undefined || head === undefined) {
    return [];
  }
  const { generatedAt: baseGeneratedAt, ...baseContent } = base;
  const { generatedAt: headGeneratedAt, ...headContent } = head;
  const baseInstant = instant(base, `${path} at the base revision`);
  const headInstant = instant(head, path);
  if (headInstant < baseInstant) {
    return [`${path} moves generatedAt backwards (${baseGeneratedAt} -> ${headGeneratedAt})`];
  }
  if (canonicalJson(baseContent) === canonicalJson(headContent)) {
    return [];
  }
  if (headInstant === baseInstant) {
    return [
      `${path} changed without advancing generatedAt (${headGeneratedAt}); markers published ` +
        "from the earlier content would conflict with the bundled catalog at the same instant",
    ];
  }
  return [];
}

function manifestAt(revision, path) {
  try {
    return JSON.parse(
      execFileSync("git", ["show", `${revision}:${path}`], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
        maxBuffer: 64 * 1024 * 1024,
      }),
    );
  } catch {
    return undefined;
  }
}

async function manifestInWorkingTree(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") {
      return undefined;
    }
    throw error;
  }
}

export async function verifyQualificationGenerations(baseRevision) {
  const violations = [];
  for (const path of QUALIFICATION_MANIFESTS) {
    violations.push(
      ...generationViolations(
        path,
        manifestAt(baseRevision, path),
        await manifestInWorkingTree(path),
      ),
    );
  }
  return violations;
}

async function main() {
  const index = process.argv.indexOf("--base");
  const base = index >= 0 ? process.argv[index + 1] : undefined;
  if (!base) {
    throw new Error("usage: qualification-generation-gate.mjs --base <git revision>");
  }
  const violations = await verifyQualificationGenerations(base);
  if (violations.length > 0) {
    for (const violation of violations) {
      console.error(violation);
    }
    process.exitCode = 1;
    return;
  }
  console.log(`Qualification manifests advance generatedAt for every change since ${base}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
