import assert from "node:assert/strict";
import test from "node:test";

import { versionHtml, versionJavaScript } from "./site-asset-versioning.mjs";

test("versions browser assets and replaces an earlier deployment token", () => {
  const html = [
    '<link rel="stylesheet" href="/assets/styles.css?v=old">',
    '<script src="/assets/app.js" type="module"></script>',
  ].join("\n");

  assert.equal(
    versionHtml(html, "abc123"),
    [
      '<link rel="stylesheet" href="/assets/styles.css?v=abc123">',
      '<script src="/assets/app.js?v=abc123" type="module"></script>',
    ].join("\n"),
  );
});

test("versions the complete module graph and generated JSON requests", () => {
  const source = [
    'import { primaryQualification } from "./qualification-data.js?v=old";',
    'const catalog = await fetch("/catalog.json");',
  ].join("\n");

  assert.equal(
    versionJavaScript(source, "abc123"),
    [
      'import { primaryQualification } from "./qualification-data.js?v=abc123";',
      'const catalog = await fetch("/catalog.json?v=abc123");',
    ].join("\n"),
  );
});

