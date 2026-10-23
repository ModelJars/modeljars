import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/**
 * Every place that enumerates qualification kinds by hand.
 *
 * Adding a kind means touching all of them. MEASURED 2026-10-23: the typed-decision kind was
 * added and five of these were missed. The one that mattered was the publication plan --
 * filterQualifiedPublications read six manifests and not the decision manifest -- so laya_q8_0
 * sat in the catalog, passed verifyCatalog, generated a marker, exported its accuracy to the
 * router, and was dropped from every publication plan. It could not be published at all, and
 * nothing said so; the marker simply never appeared on Central.
 */
const ENUMERATING_SITES = [
  ["tools/stamp-catalog-publication.mjs", "records catalogPublishedAt on newly qualified models"],
  ["tools/model-publications.mjs", "collects the qualified ids a publication plan may contain"],
  ["tools/plan-model-publications.mjs", "passes each manifest into the plan"],
  [".github/workflows/model-artifacts.yml", "triggers on the manifest and hands it to the plan"],
  [
    "modeljars/src/test/java/org/modeljars/runtime/ModelJarsJvmRuntimeDependencyTest.java",
    "takes the union of qualified ids across every registry",
  ],
];

/** The kinds the catalog actually holds, read from it rather than restated here. */
async function qualificationKinds() {
  const entries = await readdir(path.join(repositoryRoot, "catalog"));
  const kinds = entries
    .filter((name) => name.endsWith("-qualifications.json"))
    .map((name) => name.replace("-qualifications.json", ""));
  assert.ok(kinds.length >= 6, `expected the known kinds, found ${kinds.join(", ")}`);
  return kinds;
}

test("every qualification kind reaches every place that enumerates them", async () => {
  const kinds = await qualificationKinds();
  const missing = [];

  for (const [relative, why] of ENUMERATING_SITES) {
    const text = await readFile(path.join(repositoryRoot, relative), "utf8");
    for (const kind of kinds) {
      if (!text.includes(kind)) {
        missing.push(`${relative} never mentions '${kind}' — it ${why}`);
      }
    }
  }

  assert.deepEqual(
    missing.sort(),
    [],
    `A qualification kind is missing from a place that enumerates them:\n  ${missing.sort().join("\n  ")}`,
  );
});

test("the sites this test guards all still exist", async () => {
  // A path that quietly stopped existing would make the check above pass by reading nothing.
  for (const [relative] of ENUMERATING_SITES) {
    const text = await readFile(path.join(repositoryRoot, relative), "utf8");
    assert.ok(text.length > 0, `${relative} is empty`);
  }
});
