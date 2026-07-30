#!/usr/bin/env node

import { appendFile, readFile } from "node:fs/promises";

import {
  catalogPublicationDelta,
  filterQualifiedPublications,
  githubPublicationOutputs,
  selectCatalogPublications,
} from "./model-publications.mjs";

function parseArguments(argumentsList) {
  const values = new Map();
  for (let index = 0; index < argumentsList.length; index += 2) {
    const name = argumentsList[index];
    const value = argumentsList[index + 1];
    if (
      ![
        "--previous",
        "--current",
        "--previous-profiles",
        "--current-profiles",
        "--qualifications",
        "--github-output",
        "--ids",
      ].includes(name) ||
      value === undefined
    ) {
      throw new Error(
        "Usage: plan-model-publications.mjs --current FILE " +
          "(--previous FILE | --ids ID[,ID...]) " +
          "[--previous-profiles FILE --current-profiles FILE] " +
          "[--qualifications FILE] " +
          "[--github-output FILE]",
      );
    }
    values.set(name, value);
  }
  if (!values.has("--current")) {
    throw new Error("--current is required");
  }
  if (values.has("--previous") === values.has("--ids")) {
    throw new Error("Exactly one of --previous or --ids is required");
  }
  if (
    values.has("--previous-profiles") !== values.has("--current-profiles")
  ) {
    throw new Error(
      "--previous-profiles and --current-profiles must be provided together",
    );
  }
  return values;
}

async function readCatalog(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function main() {
  const argumentsMap = parseArguments(process.argv.slice(2));
  const current = await readCatalog(argumentsMap.get("--current"));
  const previousPath = argumentsMap.get("--previous");
  const previousProfilesPath = argumentsMap.get("--previous-profiles");
  const profileOptions =
    previousProfilesPath === undefined
      ? {}
      : {
          previousProfiles: await readCatalog(previousProfilesPath),
          currentProfiles: await readCatalog(
            argumentsMap.get("--current-profiles"),
          ),
        };
  const planned =
    previousPath === undefined
      ? selectCatalogPublications(
          current,
          argumentsMap
            .get("--ids")
            .split(",")
            .map((id) => id.trim())
            .filter(Boolean),
        )
      : catalogPublicationDelta(
          await readCatalog(previousPath),
          current,
          profileOptions,
        );
  const qualificationPath = argumentsMap.get("--qualifications");
  const delta =
    qualificationPath === undefined
      ? planned
      : filterQualifiedPublications(
          planned,
          current,
          await readCatalog(qualificationPath),
        );
  const outputs = githubPublicationOutputs(delta);
  const githubOutput = argumentsMap.get("--github-output");
  if (githubOutput !== undefined) {
    await appendFile(
      githubOutput,
      [
        `count=${outputs.count}`,
        `has_publications=${outputs.hasPublications}`,
        `matrix=${outputs.matrix}`,
        `removed=${outputs.removed}`,
        "",
      ].join("\n"),
    );
  }
  process.stdout.write(`${JSON.stringify(delta, null, 2)}\n`);
}

try {
  await main();
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
  process.exitCode = 1;
}
