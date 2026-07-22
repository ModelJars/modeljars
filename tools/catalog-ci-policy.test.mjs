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

  const remoteCondition = /if:\s*steps\.remote-catalog\.outputs\.required == 'true'/g;
  assert.equal(validateWorkflow.match(remoteCondition)?.length, 2);
  assert.match(validateWorkflow, /verifyRemoteCatalogMetadata/);
  assert.match(validateWorkflow, /npm run catalog:enrich/);

  assert.match(publishWorkflow, /verifyRemoteCatalogMetadata/);
  assert.doesNotMatch(publishWorkflow, /steps\.remote-catalog/);
});

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}
