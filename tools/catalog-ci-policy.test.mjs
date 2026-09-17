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

test("requires qualification manifest changes to advance generatedAt in CI", async () => {
  const validateWorkflow = await read(".github/workflows/validate.yml");

  assert.match(
    validateWorkflow,
    /node tools\/qualification-generation-gate\.mjs --base "\$\{COMPARISON\}"/,
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

test("gates the Granite answerability composite module on its catalog composition", async () => {
  const [validateWorkflow, publishWorkflow, build, settings] = await Promise.all([
    read(".github/workflows/validate.yml"),
    read(".github/workflows/publish.yml"),
    read("build.gradle.kts"),
    read("settings.gradle.kts"),
  ]);

  assert.match(settings, /include\("modeljars-composite-granite-answerability"\)/);
  assert.match(
    build,
    /val graniteAnswerabilityQualified =\s*catalogCompositions\.any \{ it\.id == "granite_4_1_3b_answerability_hybrid" \}/,
  );
  assert.match(build, /onlyIf\s*\{ graniteAnswerabilityQualified \}/);
  assert.match(
    build,
    /if \(graniteAnswerabilityQualified\)[\s\S]*?publishMavenPublicationToGitHubPackagesRepository/,
  );
  assert.match(
    build,
    /if \(graniteAnswerabilityQualified\)[\s\S]*?publishMavenPublicationToReleaseBundleRepository/,
  );
  assert.match(build, /file\("modeljars-composite-granite-answerability\/src\/main\/java"\)/);
  assert.match(build, /dependsOn\(verifyGraniteAnswerabilityPublication\)/);
  assert.match(validateWorkflow, /verifyGraniteAnswerabilityPublication/);
  assert.match(publishWorkflow, /verifyGraniteAnswerabilityPublication/);
});

test("binds the Granite answerability composition to the roles its report assembler writes", async () => {
  const [compositions, models] = await Promise.all([
    read("catalog/compositions.json").then(JSON.parse),
    read("catalog/models.json").then(JSON.parse),
  ]);

  const composition = compositions.compositions.find(
    (entry) => entry.id === "granite_4_1_3b_answerability_hybrid",
  );
  if (composition === undefined) {
    return;
  }

  assert.equal(composition.kind, "hybrid");
  assert.equal(composition.specialistKind, "first-party-rag-specialist");
  // assemble_composition_report.py writes exactly these two roles, in this order.
  assert.deepEqual(
    composition.members.map((member) => member.role),
    ["base", "answerability"],
  );

  const byId = new Map(models.models.map((model) => [model.id, model]));
  const weightBytes = composition.members.reduce((total, member) => {
    const model = byId.get(member.modelId);
    assert.ok(model !== undefined, `${member.modelId} is not in catalog/models.json`);
    const files = model.files ?? [];
    return total + (files.length === 0
      ? model.sizeBytes
      : files.reduce((sum, file) => sum + file.sizeBytes, 0));
  }, 0);
  assert.equal(composition.requiredWeightBytes, weightBytes);

  const specialist = byId.get(
    composition.members.find((member) => member.role === "answerability").modelId,
  );
  assert.ok(specialist.capabilities.includes("composition-component"));
  assert.ok(specialist.features.includes("activated-lora-adapter"));
  assert.equal(specialist.backends["rust-ffm"], true);
});
