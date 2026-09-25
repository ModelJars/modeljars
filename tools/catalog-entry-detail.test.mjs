import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { qualificationSummary, renderModel } from "../site/assets/model-detail.js";
import { primaryQualification } from "../site/assets/qualification-data.js";

/**
 * EVERY PUBLISHED COMPOSITION MUST RENDER ITS OWN DETAIL PAGE.
 *
 * MEASURED 2026-09-23: clicking Harriet on modeljars.org showed "Catalog unavailable — duration
 * must be a finite non-negative number". qualificationSummary recognised HYBRID_COMPOSITION and
 * nothing else, so a recipe fell through to the generative-RAG shape and formatted p95TtftMillis,
 * a field a recipe has no reason to carry: it composes nothing, so it has no latency of its own
 * and no control to compare a composite against.
 *
 * Every gate passed. The build, the evidence gate, the publication planner and the site tests all
 * went green while the page this catalog exists to serve threw on load, because nothing rendered
 * a detail page for an entry the catalog had just accepted.
 *
 * IT HAPPENED AGAIN, 2026-09-25: the same page threw "Catalog unavailable — downloadBytes is not
 * defined". renderModel referenced a binding declared in descriptorRows, a different function. The
 * test written after the first failure only exercised qualificationSummary, so 291 tests passed
 * while the page was broken -- the note above was written and the coverage it called for was not.
 *
 * So this file now calls renderModel itself, for every entry in the catalog. A ReferenceError in
 * any branch of that function fails here instead of on the live site.
 */
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const COMPOSITIONS = JSON.parse(
  readFileSync(join(ROOT, "catalog/compositions.json"), "utf8"),
);

test("every composition's evidence renders a detail summary", () => {
  for (const composition of COMPOSITIONS.compositions) {
    for (const qualification of composition.compositionQualifications ?? []) {
      const summary = qualificationSummary(qualification);
      assert.ok(
        summary && typeof summary.label === "string" && summary.label.length > 0,
        `${composition.id} must render a labelled detail summary`,
      );
      assert.ok(
        summary.evidenceUri && summary.evidenceSha256,
        `${composition.id} must show where its evidence lives and its hash`,
      );
    }
  }
});

test("a recipe reports the verdict and bytes its pinned base earned", () => {
  const recipe = COMPOSITIONS.compositions.find((entry) => entry.kind === "recipe");
  if (recipe === undefined) return;
  const summary = qualificationSummary(recipe.compositionQualifications[0]);

  assert.equal(summary.basisVerdict, "QUALIFIED");
  assert.equal(summary.basisModelId, recipe.members[0].modelId);
  assert.equal(summary.basisArtifactSha256, recipe.compositionSha256);
});


/**
 * The test the note above asked for and did not get: render the real page, for every entry.
 *
 * A template literal evaluates every interpolation eagerly, so one undefined binding anywhere in
 * renderModel throws for any entry that reaches it. That is what took the page down twice. Calling
 * it is the only check that covers the whole function; asserting on a summary helper does not.
 */
test("every catalog entry renders its full detail page without throwing", () => {
  // The site fetches /catalog.json, which `gradlew generateSite` builds by joining models to their
  // qualifications. npm test runs BEFORE generateSite in pages.yml, which is the structural reason
  // a broken detail page reaches production green: nothing here ever held a joined entry. So the
  // join is done here, by modelId, the same way.
  const MODELS = JSON.parse(readFileSync(join(ROOT, "catalog/models.json"), "utf8"));
  const QUALIFICATIONS = JSON.parse(
    readFileSync(join(ROOT, "catalog/qualifications.json"), "utf8"),
  );
  const byModelId = new Map();
  for (const entry of QUALIFICATIONS.entries) {
    byModelId.set(entry.modelId, [...(byModelId.get(entry.modelId) ?? []), entry]);
  }
  const models = MODELS.models.map((model) => {
    const qualifications = byModelId.get(model.id);
    return qualifications ? { ...model, qualifications } : model;
  });
  const catalog = [...models, ...COMPOSITIONS.compositions];
  assert.ok(catalog.length > 0, "catalog must not be empty");

  // An entry with no qualification is not what the site renders, and asserting on it would test a
  // shape production never serves. Only joined entries are exercised.
  const renderable = catalog.filter((entry) => primaryQualification(entry) !== null);
  assert.ok(
    renderable.some((entry) => entry.kind === "recipe"),
    "the recipe that broke this page twice must be among the entries exercised",
  );

  // renderModel writes into the page as well as building it, so it needs somewhere to write. The
  // stub records what it was given and nothing more: this test is about the template evaluating,
  // not about the DOM.
  const written = [];
  globalThis.document = {
    title: "",
    querySelector: () => ({
      set innerHTML(value) {
        written.push(value);
      },
      get innerHTML() {
        return written.at(-1) ?? "";
      },
      content: "",
      addEventListener() {},
    }),
    addEventListener() {},
  };

  try {
    for (const entry of renderable) {
      written.length = 0;
      // A ReferenceError or a TypeError is the failure this test exists for: a binding that is not
      // in scope, or a property read off null. Both took this page down -- "downloadBytes is not
      // defined" and "Cannot read properties of null". A plain Error is the code refusing a case on
      // purpose, which is behaviour and not a crash, so it is allowed through.
      try {
        renderModel(entry, catalog);
      } catch (failure) {
        assert.ok(
          !(failure instanceof ReferenceError) && !(failure instanceof TypeError),
          `${entry.id} detail page threw ${failure.name}: ${failure.message}`,
        );
        continue;
      }
      assert.ok(
        written.join("").includes(entry.name),
        `${entry.id} must render its own name into the page`,
      );
    }
  } finally {
    delete globalThis.document;
  }
});
