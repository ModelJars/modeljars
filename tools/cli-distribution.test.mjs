import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

test("builds native CLI assets on every supported host architecture", async () => {
  const workflow = await read(".github/workflows/cli-release.yml");

  for (const target of [
    "linux-x86_64",
    "linux-aarch64",
    "macos-x86_64",
    "macos-aarch64",
    "windows-x86_64",
  ]) {
    assert.match(workflow, new RegExp(`label: ${target}`));
  }
  assert.match(workflow, /:modeljars-cli:nativeCompile/);
  assert.match(workflow, /graalvm\/setup-graalvm@[0-9a-f]{40}/);
  assert.match(workflow, /"\$binary" search gemma/);
});

test("publishes native and JVM CLI channels without exposing credentials", async () => {
  const [workflow, sdkmanWorkflow, installer, build, docs] = await Promise.all([
    read(".github/workflows/cli-release.yml"),
    read(".github/workflows/sdkman-publish.yml"),
    read("install.sh"),
    read("build.gradle.kts"),
    read("docs/cli-distribution.md"),
  ]);

  assert.match(workflow, /integrallis\/homebrew-tap/);
  assert.match(workflow, /integrallis\/scoop-bucket/);
  assert.match(workflow, /PACKAGES_PUBLISH_TOKEN/);
  assert.match(workflow, /modeljars-\$\{VERSION\}\/bin/);
  assert.match(workflow, /Smoke-test SDKMAN archive/);
  assert.match(workflow, /\.\/\.github\/workflows\/sdkman-publish\.yml/);
  assert.match(workflow, /publish_sdkman:[\s\S]*default: false/);
  assert.match(
    workflow,
    /publish-sdkman:[\s\S]*vars\.SDKMAN_PUBLISH_ENABLED == 'true'/,
  );
  assert.match(workflow, /publishMavenPublicationToGitHubPackagesRepository/);
  assert.doesNotMatch(workflow, /gho_|github_pat_|Consumer-Key: [A-Za-z0-9]/);

  assert.match(sdkmanWorkflow, /workflow_dispatch:/);
  assert.match(sdkmanWorkflow, /environment: sdkman/);
  assert.match(sdkmanWorkflow, /publish-platforms:[\s\S]*if: vars\.SDKMAN_PUBLISH_ENABLED == 'true'/);
  assert.match(sdkmanWorkflow, /finalize:[\s\S]*if: vars\.SDKMAN_PUBLISH_ENABLED == 'true'/);
  assert.match(sdkmanWorkflow, /sdkman\/sdkman-release-action@[0-9a-f]{40}/);
  assert.match(sdkmanWorkflow, /candidate: modeljars/);
  assert.match(sdkmanWorkflow, /checksum-sha-256/);
  assert.match(sdkmanWorkflow, /https:\/\/vendors\.sdkman\.io\/default/);
  assert.match(sdkmanWorkflow, /https:\/\/vendors\.sdkman\.io\/announce\/struct/);
  assert.match(sdkmanWorkflow, /https:\/\/api\.sdkman\.io\/2\/candidates\/modeljars/);
  assert.doesNotMatch(sdkmanWorkflow, /gho_|github_pat_|Consumer-Key: [A-Za-z0-9]/);

  assert.match(installer, /\.sha256/);
  assert.match(installer, /actual_checksum/);
  assert.match(build, /imageName\.set\("modeljars"\)/);
  assert.match(docs, /SDKMAN is not an active package channel/);
  assert.match(docs, /SDKMAN_PUBLISH_ENABLED/);
  assert.match(docs, /every SDKMAN publication job is skipped/);
});
