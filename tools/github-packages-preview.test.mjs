import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

test("publishes an immutable aggregate-only private preview", async () => {
  const [build, workflow] = await Promise.all([
    read("build.gradle.kts"),
    read(".github/workflows/publish.yml"),
  ]);

  assert.match(build, /name = "GitHubPackages"/);
  assert.match(
    build,
    /https:\/\/maven\.pkg\.github\.com\/modeljars\/modeljars/,
  );
  assert.match(build, /System\.getenv\("GITHUB_ACTOR"\)/);
  assert.match(build, /System\.getenv\("GITHUB_TOKEN"\)/);
  assert.match(build, /publishGitHubPackagesPreview/);
  assert.match(
    build,
    /:modeljars-core:publishMavenPublicationToGitHubPackagesRepository/,
  );
  assert.match(
    build,
    /:modeljars-catalog:publishMavenPublicationToGitHubPackagesRepository/,
  );
  assert.match(
    build,
    /:modeljars:publishMavenPublicationToGitHubPackagesRepository/,
  );
  assert.doesNotMatch(
    build,
    /publishAllPublicationsToGitHubPackagesRepository/,
  );

  assert.match(workflow, /github-packages/);
  assert.match(workflow, /packages: write/);
  assert.match(workflow, /attestations: write/);
  assert.match(workflow, /id-token: write/);
  assert.match(
    workflow,
    /preview\.\$\{GITHUB_RUN_NUMBER\}\.\$\{GITHUB_RUN_ATTEMPT\}\.\$\{SHORT_SHA\}/,
  );
  assert.match(workflow, /publishGitHubPackagesPreview/);
  assert.match(workflow, /GITHUB_TOKEN: \$\{\{ secrets\.GITHUB_TOKEN \}\}/);
  assert.match(workflow, /actions\/attest@[a-f0-9]{40}/);
  assert.match(workflow, /actions\/upload-artifact@[a-f0-9]{40}/);
});

test("documents one-time credentials without embedding a token", async () => {
  const guide = await read("docs/private-preview-packages.md");

  assert.match(guide, /read:packages/);
  assert.match(guide, /personal access token \(classic\)/i);
  assert.match(guide, /~\/\.gradle\/gradle\.properties/);
  assert.match(guide, /~\/\.m2\/settings\.xml/);
  assert.match(guide, /org\.modeljars:modeljars/);
  assert.match(guide, /maven\.pkg\.github\.com\/modeljars\/modeljars/);
  assert.doesNotMatch(guide, /ghp_[A-Za-z0-9]{20,}/);
});
