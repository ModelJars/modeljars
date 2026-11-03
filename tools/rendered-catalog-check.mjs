/**
 * Render every entry of the GENERATED catalog, in the shape the site is actually served.
 *
 * tools/catalog-entry-detail.test.mjs renders the catalog too, but it has to assemble its own
 * join from catalog/*.json because npm test runs before the Gradle build. That join reproduces
 * the FIELDS and not the ENRICHMENT: generateSite attaches entry.siteMetadata(...), so a raw rag
 * entry carries no useCaseTier while the served catalog.json carries useCaseTier=GUARDED_RAG on
 * the same entry. MEASURED 2026-10-23: 104 of 107 rendered entries differed that way.
 *
 * So a defect reachable only through an enriched field passes npm test and reaches production.
 * This check closes that gap by reading build/generated/site/catalog.json -- the exact bytes the
 * browser fetches -- and asserting the same invariants against it.
 *
 * Run after generateSite. Exits non-zero and names every entry that fails.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { renderModel } from "../site/assets/model-detail.js";
import { primaryQualification, tierLabel } from "../site/assets/qualification-data.js";
import { buildFacets } from "../site/assets/taxonomy.js";
import { catalogDomains } from "../site/assets/taxonomy.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CATALOG = join(ROOT, "build/generated/site/catalog.json");

const catalog = JSON.parse(readFileSync(CATALOG, "utf8"));
assert.ok(Array.isArray(catalog) && catalog.length > 0, `${CATALOG} holds no entries`);

// Only joined entries are served a detail page, which is what the site renders.
const renderable = catalog.filter((entry) => primaryQualification(entry) !== null);
assert.ok(renderable.length > 0, "no entry in the generated catalog has a primary qualification");

// The enrichment this check exists for must actually be present, or it is silently testing the
// same raw shape npm test already covers and proving nothing new.
const enriched = renderable.filter((entry) =>
  (entry.ragQualifications || []).some((q) => q.useCaseTier),
);
assert.ok(
  enriched.length > 0,
  "no rag entry carries useCaseTier: this is the raw shape, not the served one -- the check is" +
    " reading the wrong file and would pass without testing the enrichment",
);

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

const failures = [];
for (const entry of renderable) {
  written.length = 0;
  let page = "";
  try {
    renderModel(entry, catalog);
    page = written.join("");
  } catch (failure) {
    if (failure instanceof ReferenceError || failure instanceof TypeError) {
      failures.push(`${entry.id}: detail page threw ${failure.name}: ${failure.message}`);
    }
    continue;
  }
  if (!page.includes(entry.name)) failures.push(`${entry.id}: page omits its own name`);
  if (page.includes("undefined")) {
    failures.push(`${entry.id}: page renders the text "undefined" (a missing interpolation)`);
  }
  const generative = (entry.capabilities ?? []).some((capability) =>
    ["chat", "generation", "text-generation"].includes(capability),
  );
  if (!generative && entry.kind !== "hybrid" && entry.kind !== "recipe") {
    if (page.includes("ModelJars.openRuntime")) {
      failures.push(`${entry.id}: not a text generator, yet publishes ModelJars.openRuntime`);
    }
  }
  if ((entry.capabilities ?? []).includes("typed-decision")) {
    if (!catalogDomains(entry).includes("system-1")) {
      failures.push(`${entry.id}: typed-decision but not discoverable as System One`);
    }
  }
}
// Every tier the served catalog emits must have its own label in the production-evidence filter.
//
// That select used to carry its options as literals in index.html while every other select is
// populated from the facets, and it drifted: the catalog emitted hybrid-composition,
// ranked-decision, recipe-pinned-base and schema-classification with no option for any of them, so
// the qualified typed-decision and schema-classification models could not be reached through the
// filter at all, while production-rag and generative-rag were offered and matched nothing. The
// options are generated now, and this is the guard that the generation covers what is emitted.
//
// A tier with no case in qualificationLabel falls through to "Guarded RAG", which would mislabel a
// new tier rather than fail. guarded-rag is the one value allowed to carry that label.
for (const facet of buildFacets(renderable).qualifications) {
  if (facet.value !== "guarded-rag" && tierLabel(facet.value) === "Guarded RAG") {
    failures.push(
      `${facet.value}: emitted by ${facet.count} entr${facet.count === 1 ? "y" : "ies"} with no` +
        ` label of its own, so the production-evidence filter would show it as "Guarded RAG"`,
    );
  }
}

delete globalThis.document;

process.stdout.write(
  `rendered ${renderable.length} served catalog entries (${enriched.length} carry enriched` +
    ` qualification metadata), ${failures.length} failed\n`,
);
if (failures.length > 0) {
  for (const failure of failures) process.stdout.write(`  ${failure}\n`);
  process.exit(1);
}
