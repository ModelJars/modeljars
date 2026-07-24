import { modelTerms, sizeTier } from "./taxonomy.js";
import { primaryQualification } from "./qualification-data.js";

const SEARCH_ALIASES = new Map([
  ["medical", ["healthcare", "clinical"]],
  ["medicine", ["healthcare", "clinical"]],
  ["programming", ["coding", "code"]],
  ["developer", ["coding", "code"]],
  ["java", ["pure-java"]],
  ["local", ["offline", "on-device"]],
]);

export function normalize(value) {
  return String(value || "").trim().toLowerCase();
}

function queryTerms(query) {
  const normalized = normalize(query);
  if (!normalized) return [];
  return [normalized, ...(SEARCH_ALIASES.get(normalized) || [])];
}

export function matches(model, query, backend) {
  const text = modelTerms(model).join(" ");

  const queryMatches = !query || queryTerms(query).some((term) => text.includes(term));
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
