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
        "--previous-qualifications",
        "--embedding-qualifications",
        "--previous-embedding-qualifications",
        "--tool-qualifications",
        "--previous-tool-qualifications",
        "--github-output",
        "--ids",
      ].includes(name) ||
      value === undefined
    ) {
      throw new Error(
        "Usage: plan-model-publications.mjs --current FILE " +
          "(--previous FILE | --ids ID[,ID...]) " +
          "[--previous-profiles FILE --current-profiles FILE] " +
          "[--qualifications FILE --previous-qualifications FILE] " +
          "[--embedding-qualifications FILE " +
          "--previous-embedding-qualifications FILE] " +
          "[--tool-qualifications FILE --previous-tool-qualifications FILE] " +
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
  if (
    values.has("--previous-qualifications") &&
    (!values.has("--previous") || !values.has("--qualifications"))
  ) {
    throw new Error(
      "--previous-qualifications requires --previous and --qualifications",
    );
  }
  if (
    values.has("--previous") &&
    values.has("--qualifications") &&
    !values.has("--previous-qualifications")
  ) {
    throw new Error(
      "--previous-qualifications is required for a qualification-aware delta",
    );
  }
  if (
    values.has("--previous-tool-qualifications") &&
    (!values.has("--previous") || !values.has("--tool-qualifications"))
  ) {
    throw new Error(
      "--previous-tool-qualifications requires --previous and --tool-qualifications",
    );
  }
  if (
    values.has("--previous") &&
    values.has("--tool-qualifications") &&
    !values.has("--previous-tool-qualifications")
  ) {
    throw new Error(
      "--previous-tool-qualifications is required for a tool-qualification-aware delta",
    );
  }
  return values;
}

async function readCatalog(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function qualifiedCatalog(
  catalog,
  qualificationManifest,
  embeddingManifest,
  toolManifest,
) {
  filterQualifiedPublications(
    { publications: [], removed: [] },
    catalog,
    qualificationManifest,
    embeddingManifest,
    toolManifest,
  );
  const qualifiedIds = new Set(
    [
      ...qualificationManifest.entries.filter(
        (entry) => entry.qualified === true,
      ),
      ...(embeddingManifest?.entries ?? []).filter(
        (entry) => entry.qualified === true,
      ),
      ...(toolManifest?.entries ?? []).filter(
        (entry) => entry.summary?.qualified === true,
      ),
    ]
      .map((entry) => entry.modelId),
  );
  return {
    catalog: {
      ...catalog,
      models: catalog.models.filter((model) => qualifiedIds.has(model.id)),
    },
    qualifiedIds,
  };
}

function qualifiedProfiles(profileCatalog, qualifiedIds) {
  return {
    ...profileCatalog,
    profiles: profileCatalog.profiles.filter((profile) =>
      qualifiedIds.has(profile.modelId),
    ),
  };
}

async function main() {
  const argumentsMap = parseArguments(process.argv.slice(2));
  const current = await readCatalog(argumentsMap.get("--current"));
  const previousPath = argumentsMap.get("--previous");
  const previousProfilesPath = argumentsMap.get("--previous-profiles");
  let profileOptions =
    previousProfilesPath === undefined
      ? {}
      : {
          previousProfiles: await readCatalog(previousProfilesPath),
          currentProfiles: await readCatalog(
            argumentsMap.get("--current-profiles"),
          ),
        };
  const qualificationPath = argumentsMap.get("--qualifications");
  const currentQualifications =
    qualificationPath === undefined
      ? undefined
      : await readCatalog(qualificationPath);
  const embeddingQualificationPath = argumentsMap.get(
    "--embedding-qualifications",
  );
  const currentEmbeddingQualifications =
    embeddingQualificationPath === undefined
      ? undefined
      : await readCatalog(embeddingQualificationPath);
  const toolQualificationPath = argumentsMap.get("--tool-qualifications");
  const currentToolQualifications =
    toolQualificationPath === undefined
      ? undefined
      : await readCatalog(toolQualificationPath);
  let planned;
  if (previousPath === undefined) {
    planned = selectCatalogPublications(
      current,
      argumentsMap
        .get("--ids")
        .split(",")
        .map((id) => id.trim())
        .filter(Boolean),
    );
  } else {
    let previous = await readCatalog(previousPath);
    let currentForDelta = current;
    const previousQualificationsPath = argumentsMap.get(
      "--previous-qualifications",
    );
    if (previousQualificationsPath !== undefined) {
      const previousEmbeddingPath = argumentsMap.get(
        "--previous-embedding-qualifications",
      );
      const previousToolPath = argumentsMap.get(
        "--previous-tool-qualifications",
      );
      const previousQualified = qualifiedCatalog(
        previous,
        await readCatalog(previousQualificationsPath),
        previousEmbeddingPath === undefined
          ? undefined
          : await readCatalog(previousEmbeddingPath),
        previousToolPath === undefined
          ? undefined
          : await readCatalog(previousToolPath),
      );
      const currentQualified = qualifiedCatalog(
        current,
        currentQualifications,
        currentEmbeddingQualifications,
        currentToolQualifications,
      );
      previous = previousQualified.catalog;
      currentForDelta = currentQualified.catalog;
      if (previousProfilesPath !== undefined) {
        profileOptions = {
          previousProfiles: qualifiedProfiles(
            profileOptions.previousProfiles,
            previousQualified.qualifiedIds,
          ),
          currentProfiles: qualifiedProfiles(
            profileOptions.currentProfiles,
            currentQualified.qualifiedIds,
          ),
        };
      }
    }
    planned = catalogPublicationDelta(previous, currentForDelta, profileOptions);
  }
  const delta =
    qualificationPath === undefined
      ? planned
      : filterQualifiedPublications(
          planned,
          current,
          currentQualifications,
          currentEmbeddingQualifications,
          currentToolQualifications,
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
