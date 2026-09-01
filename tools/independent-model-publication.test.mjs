import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

test("model marker JARs are independent of the Models runtime", async () => {
  const build = await read("build.gradle.kts");

  assert.match(build, /verifyMarkerPublicationIndependence/);
  assert.match(build, /api\("com\.integrallis:models:\$modelsVersion"\)/);
  assert.match(
    build,
    /api\("com\.integrallis:backend-java:\$modelsVersion"\)/,
  );
  assert.match(
    build,
    /api\("com\.integrallis:backend-native:\$modelsVersion"\)/,
  );
  assert.match(
    build,
    /Model marker \$\{entry\.markerCoordinate\} must not depend on Models or any runtime/,
  );
});

test("builds a signed Central bundle containing only selected model publications", async () => {
  const build = await read("build.gradle.kts");

  assert.match(build, /modeljarsMarkerIds/);
  assert.match(build, /verifyMarkerReleaseBundle/);
  assert.match(build, /markerReleaseBundleZip/);
  assert.match(build, /modeljars-marker-bundle\.zip/);
  assert.doesNotMatch(
    build,
    /val verifyMarkerReleaseBundle =[\s\S]*?dependsOn\(verifyLaunchQualifications\)/,
  );
});

test("publishes accepted model coordinates independently from platform artifacts", async () => {
  const workflow = await read(".github/workflows/model-artifacts.yml");

  assert.match(workflow, /pull_request:/);
  assert.match(workflow, /push:/);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /plan-model-publications\.mjs/);
  assert.match(workflow, /Comma-separated catalog model IDs, or all/);
  assert.match(workflow, /tools\/model-publications\.test\.mjs/);
  assert.match(workflow, /tools\/independent-model-publication\.test\.mjs/);
  assert.match(workflow, /fromJson\(needs\.plan\.outputs\.matrix\)/);
  assert.match(workflow, /packages: write/);
  assert.match(workflow, /matrix\.githubTask/);
  assert.match(workflow, /markerReleaseBundleZip/);
  assert.match(workflow, /publishingType=USER_MANAGED/);
  assert.match(workflow, /environment: maven-central/);
  assert.match(workflow, /actions\/attest@[a-f0-9]{40}/);
  assert.doesNotMatch(workflow, /:modeljars:publish/);
  assert.doesNotMatch(workflow, /:modeljars-core:publish/);
});

test("reuses an identical immutable GitHub Package without hiding collisions", async () => {
  const workflow = await read(".github/workflows/model-artifacts.yml");

  assert.match(workflow, /id: existing_package/);
  assert.match(workflow, /maven\.pkg\.github\.com\/modeljars\/modeljars/);
  assert.match(workflow, /jar --extract --file/);
  assert.match(workflow, /diff --recursive --brief/);
  assert.doesNotMatch(workflow, /cmp --silent/);
  assert.match(workflow, /Existing immutable package does not match/);
  assert.match(
    workflow,
    /if: steps\.existing_package\.outputs\.exists != 'true'/,
  );
});
