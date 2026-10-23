import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
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
  // MEASURED 2026-10-23: this join attached `qualifications`, a field primaryQualification never
  // reads, so `renderable` below held 0 of 462 models and this test -- named for every catalog
  // entry -- exercised only the compositions. That is the structural reason the typed-decision
  // crash reached production: no model's detail page was ever rendered here.
  //
  // generateSite joins catalog/<kind>-qualifications.json onto <kind>Qualifications, with rag read
  // from catalog/qualifications.json (build.gradle.kts, the candidateTestMetadata join). The kinds
  // are read from the directory rather than listed here, so a new kind is joined the day it lands
  // instead of the day somebody remembers this file.
  //
  // WHAT THIS JOIN STILL CANNOT TEST, measured 2026-10-23: generateSite does not attach the raw
  // manifest entry, it attaches entry.siteMetadata(...), which ENRICHES it. A raw rag entry in
  // catalog/qualifications.json carries no useCaseTier at all; the deployed catalog.json carries
  // useCaseTier=GUARDED_RAG on the same entry. So these renders exercise the raw shape, one field
  // short of the shape production serves, and that enrichment lives in Kotlin and cannot be
  // reproduced here. The assertions below hold on both shapes, but a defect reachable ONLY via an
  // enriched field would pass here. Closing that gap means asserting against the generated
  // catalog.json after generateSite, which runs later in validate.yml than npm test does.
  const MODELS = JSON.parse(readFileSync(join(ROOT, "catalog/models.json"), "utf8"));
  const kindFiles = [
    ["ragQualifications", "catalog/qualifications.json"],
    ...readdirSync(join(ROOT, "catalog"))
      .filter((name) => name.endsWith("-qualifications.json"))
      .map((name) => [
        `${name.replace("-qualifications.json", "")}Qualifications`,
        `catalog/${name}`,
      ]),
  ];
  const entriesByField = kindFiles.map(([field, relative]) => {
    const document = JSON.parse(readFileSync(join(ROOT, relative), "utf8"));
    const byModelId = new Map();
    for (const entry of document.entries ?? []) {
      byModelId.set(entry.modelId, [...(byModelId.get(entry.modelId) ?? []), entry]);
    }
    return [field, byModelId];
  });
  const models = MODELS.models.map((model) => {
    const joined = { ...model };
    for (const [field, byModelId] of entriesByField) {
      joined[field] = byModelId.get(model.id) ?? [];
    }
    return joined;
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
  // A join that silently stops attaching a kind returns this test to rendering nothing, which is
  // how it passed while the page was broken. Every kind the catalog holds evidence for must reach
  // a rendered page, and the count is asserted so a collapse to the compositions alone fails here.
  // componentQualifications is the one kind with no standalone page, by design: a component is an
  // internal member bound by its composition's evidence, which is what the page says of it. The
  // premise is asserted rather than trusted -- if a component ever stops being a member, it needs
  // a page and this exclusion has to go.
  const MEMBER_IDS = new Set(
    COMPOSITIONS.compositions.flatMap((entry) =>
      (entry.members ?? []).map((member) => member.modelId),
    ),
  );
  for (const [field, byModelId] of entriesByField) {
    if (byModelId.size === 0) continue;
    if (field === "componentQualifications") {
      for (const modelId of byModelId.keys()) {
        assert.ok(
          MEMBER_IDS.has(modelId),
          `${modelId} carries component evidence but is no composition member, so it needs a` +
            " detail page of its own and must not be excluded here",
        );
      }
      continue;
    }
    assert.ok(
      renderable.some((entry) => (entry[field] ?? []).length > 0),
      `no rendered entry carries ${field}: the join dropped a whole qualification kind`,
    );
  }
  assert.ok(
    renderable.filter((entry) => entry.kind !== "recipe" && entry.kind !== "hybrid").length > 0,
    "renderable must hold models, not only compositions",
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
      const page = written.join("");
      assert.ok(
        page.includes(entry.name),
        `${entry.id} must render its own name into the page`,
      );

      // Rendering without throwing is not rendering correctly. Both checks below are about what
      // the page SAYS, which is what the crash test could never see.
      //
      // MEASURED 2026-10-23 on the live laya_q8_0 page: an absent field interpolated into a
      // catalog check and published the sentence "undefined-request RAG qualification" -- a
      // decision model credited with a RAG workload it never ran.
      assert.ok(
        !page.includes("undefined"),
        `${entry.id} renders the text "undefined": a field is missing from an interpolation`,
      );

      // MEASURED the same day: the snippet chain's fallback is the generative one, so every
      // non-generative kind that reached it published ModelJars.openRuntime -- the exact call
      // requireGenerativeModel refuses. The page handed readers code that throws on line one.
      // This is the general invariant, so the next kind added cannot fall through the same way.
      const generative = (entry.capabilities ?? []).some((capability) =>
        ["chat", "generation", "text-generation"].includes(capability),
      );
      if (!generative && entry.kind !== "hybrid" && entry.kind !== "recipe") {
        assert.ok(
          !page.includes("ModelJars.openRuntime"),
          `${entry.id} is not a text generator but its page publishes ModelJars.openRuntime,` +
            " which refuses it",
        );
      }
    }
  } finally {
    delete globalThis.document;
  }
});
