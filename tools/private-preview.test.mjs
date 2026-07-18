import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("keeps the temporary private preview on GitHub Pages only", async () => {
  const [pagesWorkflow, placeholder, packageJson, operations] = await Promise.all([
    read(".github/workflows/pages.yml"),
    read("site-public/index.html"),
    read("package.json").then(JSON.parse),
    read("docs/modeljars-operations-and-model-candidates.md"),
  ]);

  assert.match(pagesWorkflow, /generatePublicSite/);
  assert.match(pagesWorkflow, /build\/public-site/);
  assert.doesNotMatch(pagesWorkflow, /build\/site(?:\s|$)/);
  assert.match(placeholder, /Private preview/);
  assert.doesNotMatch(placeholder, /catalog|pure Java|model framework|org\.modeljars/i);
  assert.doesNotMatch(placeholder, /password|authorization|sign[ -]?in|login/i);
  assert.equal(packageJson.devDependencies?.wrangler, undefined);
  assert.doesNotMatch(operations, /Cloudflare/i);

  await Promise.all([
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
