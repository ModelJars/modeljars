import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

test("requires reproducible, signed Maven publications with modern checksums", async () => {
  const build = await read("build.gradle.kts");

  assert.match(build, /apply\(plugin = "signing"\)/);
  assert.match(build, /useInMemoryPgpKeys/);
  assert.match(build, /isPreserveFileTimestamps = false/);
  assert.match(build, /isReproducibleFileOrder = true/);
  assert.match(build, /generateReleaseChecksums/);
  assert.match(build, /"SHA-256"/);
  assert.match(build, /"SHA-512"/);
  assert.match(build, /withType<PublishToMavenRepository>\(\)/);
  assert.match(build, /verifyReleaseBundle/);
  assert.match(build, /releaseBundleZip/);
  assert.match(
    build,
    /val verifyReleaseBundle =[\s\S]*?dependsOn\(verifyLaunchQualifications\)/,
  );
  assert.doesNotMatch(
    build,
    /tasks\.named\("check"\) \{[\s\S]{0,300}dependsOn\(verifyLaunchQualifications\)/,
  );
});

test("attests a user-managed Central bundle from the protected release job", async () => {
  const workflow = await read(".github/workflows/publish.yml");

  assert.match(workflow, /attestations: write/);
  assert.match(workflow, /actions\/attest@[a-f0-9]{40}/);
  assert.match(workflow, /publishingType=USER_MANAGED/);
  assert.match(workflow, /central\.sonatype\.com\/api\/v1\/publisher\/upload/);
  assert.match(workflow, /verifyReleaseBundle/);
  assert.match(workflow, /GPG_PRIVATE_KEY/);
  assert.match(workflow, /GPG_PASSPHRASE/);
  assert.match(
    workflow,
    /- name: Clean release workspace\s+run: \.\/gradlew --no-daemon clean/,
  );
  assert.doesNotMatch(
    workflow,
    /- name: Build signed Central bundle[\s\S]*?run: >\s+\.\/gradlew --no-daemon\s+clean/,
  );
});

test("documents verification and rejects obfuscation as an integrity mechanism", async () => {
  const security = await read("SECURITY.md");

  assert.match(security, /gh attestation verify/);
  assert.match(security, /OpenPGP/i);
  assert.match(security, /SHA-256/);
  assert.match(security, /obfuscat/i);
  assert.match(security, /reproducib/i);
});
