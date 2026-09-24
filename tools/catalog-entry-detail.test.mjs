import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { qualificationSummary } from "../site/assets/model-detail.js";

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
