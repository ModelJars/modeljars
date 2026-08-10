import { modelTerms, sizeTier } from "./taxonomy.js";
import { primaryQualification } from "./qualification-data.js";

const SEARCH_ALIASES = new Map([
  ["fintech", ["finance"]],
  ["financial", ["finance"]],
  ["medical", ["healthcare", "clinical"]],
  ["medicine", ["healthcare", "clinical"]],
  ["programming", ["coding"]],
  ["developer", ["coding"]],
  ["java", ["pure-java"]],
  ["local", ["offline", "on-device"]],
  ["vector", ["embedding"]],
  ["vectors", ["embedding"]],
  ["embed", ["embedding"]],
  ["similarity", ["semantic-search"]],
  ["law", ["legal"]],
  ["math", ["mathematics"]],
  ["translate", ["translation"]],
  ["speech", ["voice", "audio"]],
  ["tts", ["voice", "audio", "text-to-speech"]],
]);

export function normalize(value) {
  return String(value || "").trim().toLowerCase();
}

// A query word matches when the catalog text contains it, or contains any of its aliases.
// Splitting on whitespace lets "semantic search" find the "semantic-search" capability, which
// a single substring test cannot because of the hyphen.
function queryWords(query) {
  return normalize(query).split(/\s+/).filter(Boolean);
}

function wordMatches(text, word) {
  return [word, ...(SEARCH_ALIASES.get(word) || [])].some((term) => text.includes(term));
}

export function matches(model, query, backend) {
  const text = modelTerms(model).join(" ");

  const words = queryWords(query);
  // Every word must match, so adding words narrows the result set rather than widening it.
  const queryMatches = !words.length || words.every((word) => wordMatches(text, word));
  const backendMatches = !backend || model.backends?.[backend] === true;
  return queryMatches && backendMatches;
}

export function filterCatalog(catalog, filters = {}) {
  const { query, domain, backend, architecture, size, qualification, sort = "name" } = filters;
  const filtered = catalog.filter(
    (model) => {
      const evidence = primaryQualification(model);
      const evidenceTier = String(evidence?.useCaseTier || "not-evaluated")
        .toLowerCase()
        .replaceAll("_", "-");
      const qualificationMatches =
        !qualification ||
        (qualification === "production-rag" ? evidence?.qualified === true : evidenceTier === qualification);
      return (
        matches(model, query, backend) &&
        (!domain || model.domains?.includes(domain)) &&
        (!architecture || normalize(model.architecture) === normalize(architecture)) &&
        (!size || sizeTier(model) === size) &&
        qualificationMatches
      );
    },
  );

  const comparators = {
    largest: (left, right) => (right.sizeBytes || 0) - (left.sizeBytes || 0),
    name: (left, right) => left.name.localeCompare(right.name),
    newest: (left, right) => String(right.modelVersion).localeCompare(String(left.modelVersion)),
    smallest: (left, right) => (left.sizeBytes || Number.MAX_SAFE_INTEGER) - (right.sizeBytes || Number.MAX_SAFE_INTEGER),
  };

  return [...filtered].sort(comparators[sort] || comparators.name);
}
