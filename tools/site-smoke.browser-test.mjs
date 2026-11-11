import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { smokeGeneratedSite } from "./site-smoke.mjs";

async function fixture(t, script) {
  const root = await mkdtemp(path.join(tmpdir(), "modeljars-browser-fixture-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(path.join(root, "catalog.json"), JSON.stringify([{ id: "fixture" }]));
  await writeFile(path.join(root, "index.html"), `<!doctype html><html><body>
    <div id="catalog-results" aria-busy="true"></div>
    <script type="module">${script}</script></body></html>`);
  return root;
}

test("waits for asynchronous catalog rendering", async (t) => {
  const root = await fixture(t, `
    const catalog = await (await fetch('/catalog.json')).json();
    setTimeout(() => {
      const result = document.querySelector('#catalog-results');
      result.innerHTML = '<details class="catalog-entry"></details>1 qualified entry';
      result.setAttribute('aria-busy', 'false');
    }, 1200);
  `);
  await smokeGeneratedSite(root);
});

test("reports JavaScript failures before the catalog renders", async (t) => {
  const root = await fixture(t, `throw new Error('fixture render failure');`);
  await assert.rejects(smokeGeneratedSite(root), /browser JavaScript error:.*fixture render failure/);
});

test("reports a missing module instead of accepting the initial empty document", async (t) => {
  const root = await fixture(t, `import './missing-module.js';`);
  await assert.rejects(smokeGeneratedSite(root, { timeoutMillis: 5000 }),
    /did not become ready[\s\S]*HTTP 404:.*missing-module.js/);
});

test("rejects a completed render with missing catalog entries", async (t) => {
  const root = await fixture(t, `document.querySelector('#catalog-results').setAttribute('aria-busy', 'false');`);
  await assert.rejects(smokeGeneratedSite(root), /rendered 0 catalog entries; expected 1/);
});
