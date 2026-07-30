import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("deploys the qualified public catalog on GitHub Pages", async () => {
  const [pagesWorkflow, catalog, packageJson, operations] = await Promise.all([
    read(".github/workflows/pages.yml"),
    read("site/index.html"),
    read("package.json").then(JSON.parse),
    read("docs/modeljars-operations-and-model-candidates.md"),
  ]);

  assert.match(pagesWorkflow, /generateSite/);
  assert.match(pagesWorkflow, /build\/site(?:\s|$)/);
  assert.doesNotMatch(pagesWorkflow, /generatePublicSite|build\/public-site/);
  assert.match(catalog, /Search models/i);
  assert.doesNotMatch(catalog, /Private preview|invited accounts/i);
  assert.equal(packageJson.devDependencies?.wrangler, undefined);
  assert.doesNotMatch(operations, /Cloudflare/i);

  await Promise.all([
    assertMissing("site-public"),
    assertMissing(".github/workflows/cloudflare-pages.yml"),
    assertMissing("functions/_middleware.js"),
    assertMissing("functions/login.js"),
    assertMissing("site/_routes.json"),
    assertMissing("docs/private-preview-auth.md"),
  ]);
});

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

async function assertMissing(relativePath) {
  await assert.rejects(
    access(path.join(repositoryRoot, relativePath)),
    (error) => error?.code === "ENOENT",
  );
}
