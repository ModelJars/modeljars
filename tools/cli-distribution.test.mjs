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
  const [workflow, installer, build, docs] = await Promise.all([
    read(".github/workflows/cli-release.yml"),
    read("install.sh"),
    read("build.gradle.kts"),
    read("docs/cli-distribution.md"),
  ]);

  assert.match(workflow, /integrallis\/homebrew-tap/);
  assert.match(workflow, /integrallis\/scoop-bucket/);
  assert.match(workflow, /PACKAGES_PUBLISH_TOKEN/);
  assert.match(workflow, /sdkman\/sdkman-release-action@[0-9a-f]{40}/);
  assert.match(workflow, /candidate: modeljars/);
  assert.match(workflow, /modeljars-\$\{VERSION\}\/bin/);
  assert.match(workflow, /publishMavenPublicationToGitHubPackagesRepository/);
  assert.doesNotMatch(workflow, /gho_|github_pat_|Consumer-Key: [A-Za-z0-9]/);

  assert.match(installer, /\.sha256/);
  assert.match(installer, /actual_checksum/);
  assert.match(build, /imageName\.set\("modeljars"\)/);
  assert.match(docs, /SDKMAN requires its vendor onboarding/);
});
