import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("scopes remote catalog checks while retaining scheduled and release audits", async () => {
  const [validateWorkflow, publishWorkflow] = await Promise.all([
    read(".github/workflows/validate.yml"),
    read(".github/workflows/publish.yml"),
  ]);

  assert.match(validateWorkflow, /schedule:/);
  assert.match(validateWorkflow, /workflow_dispatch:/);
  assert.match(validateWorkflow, /fetch-depth:\s*0/);
  assert.match(validateWorkflow, /id:\s*remote-catalog/);
  assert.match(validateWorkflow, /catalog\/models\.json/);
  assert.match(validateWorkflow, /mode=changed/);
  assert.match(validateWorkflow, /--changed-from="\$\{COMPARISON\}"/);

  const remoteCondition = /if:\s*steps\.remote-catalog\.outputs\.required == 'true'/g;
  assert.equal(validateWorkflow.match(remoteCondition)?.length, 2);
  assert.match(validateWorkflow, /verifyRemoteCatalogMetadata/);
  assert.match(validateWorkflow, /HF_TOKEN:\s*\$\{\{ secrets\.HF_TOKEN \}\}/);
  assert.match(validateWorkflow, /npm run catalog:enrich/);

  assert.match(publishWorkflow, /verifyRemoteCatalogMetadata/);
  assert.match(validateWorkflow, /npm run catalog:verify-compositions/);
  assert.equal(
    publishWorkflow.match(/npm run catalog:verify-compositions/g)?.length,
    2,
    "release verification and the signed release bundle must both verify composite evidence",
  );
  assert.equal(
    publishWorkflow.match(/HF_TOKEN:\s*\$\{\{ secrets\.HF_TOKEN \}\}/g)?.length,
    2,
    "both release verification passes must authenticate gated Hugging Face metadata",
  );
  assert.doesNotMatch(publishWorkflow, /steps\.remote-catalog/);
  assert.equal(
    publishWorkflow.match(/npm run catalog:enrich/g)?.length,
    1,
    "one verified commit must not probe every remote GGUF twice",
  );
});

test("runs the qualified mxbai Safetensors bundle through the public API in CI", async () => {
  const validateWorkflow = await read(".github/workflows/validate.yml");

  assert.match(validateWorkflow, /mxbaiRerankerIntegrationTest/);
  assert.match(validateWorkflow, /-PmxbaiRerankerLive=true/);
});

test("does not republish the withdrawn Qwen routing experiment", async () => {
  const [publishWorkflow, build, compositions] = await Promise.all([
    read(".github/workflows/publish.yml"),
    read("build.gradle.kts"),
    read("catalog/compositions.json").then(JSON.parse),
  ]);

  assert.equal(
    compositions.compositions.some((entry) => entry.id === "qwen3_chat_tools_composite"),
    false,
  );
  assert.doesNotMatch(
    publishWorkflow,
    /:modeljars-composite-qwen3-chat-tools:assemble/,
  );
  assert.doesNotMatch(
    publishWorkflow,
    /modeljars-composite-qwen3-chat-tools\/build\/libs/,
  );
  assert.match(build, /val qwenChatToolsQualified =\s*catalogCompositions\.any/);
  assert.match(build, /onlyIf\s*\{ qwenChatToolsQualified \}/);
  assert.match(build, /if \(qwenChatToolsQualified\)[\s\S]*publishMavenPublicationToReleaseBundleRepository/);
});

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}
