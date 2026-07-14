const FACET_FIELDS = {
  domains: (model) => model.domains || [],
  capabilities: (model) => model.capabilities || [],
  tags: (model) => model.tags || [],
  backends: (model) =>
    Object.entries(model.backends || {})
      .filter(([, supported]) => supported === true)
      .map(([backend]) => backend),
  architectures: (model) => (model.architecture ? [model.architecture] : []),
  quantizations: (model) => (model.quantization ? [model.quantization] : []),
  modalities: (model) => model.modalities || [],
  sizes: (model) => [sizeTier(model)],
};

function normalizedValues(values) {
  return values.map((value) => String(value).trim().toLowerCase()).filter(Boolean);
}

export function sizeTier(model) {
  const parameters = Number(model.dimensions?.parameterCount || 0);
  if (!parameters) return "unknown";
  if (parameters <= 1_000_000_000) return "tiny";
  if (parameters <= 4_000_000_000) return "small";
  if (parameters <= 15_000_000_000) return "medium";
  if (parameters <= 40_000_000_000) return "large";
  return "very-large";
}

export function modelTerms(model) {
  const terms = [
    model.name,
    model.description,
    model.sourceId,
    model.markerCoordinate,
    model.architecture,
    model.format,
    model.quantization,
    model.packaging,
    model.language,
    model.topology,
    model.license,
    model.family,
    sizeTier(model),
    ...(model.domains || []),
    ...(model.capabilities || []),
    ...(model.features || []),
    ...(model.tags || []),
    ...(model.languages || []),
    ...(model.modalities || []),
    ...FACET_FIELDS.backends(model),
  ];

  return [...new Set(normalizedValues(terms.filter((term) => term != null)))];
}

function countedFacet(models, valuesForModel) {
  const counts = new Map();
  for (const model of models) {
    for (const value of new Set(normalizedValues(valuesForModel(model)))) {
      counts.set(value, (counts.get(value) || 0) + 1);
    }
  }

  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((left, right) => right.count - left.count || left.value.localeCompare(right.value));
}

export function buildFacets(models) {
  return Object.fromEntries(
    Object.entries(FACET_FIELDS).map(([name, valuesForModel]) => [
      name,
      countedFacet(models, valuesForModel),
    ]),
  );
}

function hasCompleteMetadata(model) {
  return Boolean(
    model.name &&
      model.sourceId &&
      model.markerCoordinate &&
      model.architecture &&
      model.format &&
      model.quantization &&
      model.license &&
      model.sizeBytes,
  );
}

export function verificationProfile(model) {
  const checks = [];
  const pinnedArtifact = Boolean(model.sha256 && model.revision);
  const completeMetadata = hasCompleteMetadata(model);
  const pureJavaExecuted = model.backends?.["pure-java"] === true;

  if (pinnedArtifact) checks.push("Pinned artifact");
  if (completeMetadata) checks.push("Complete metadata");
  if (pureJavaExecuted) checks.push("Pure Java executed");

  if (pureJavaExecuted && pinnedArtifact && completeMetadata) {
    return { level: "executed", label: "Runtime verified", checks };
  }
  if (pinnedArtifact && completeMetadata) {
    return { level: "verified", label: "Artifact verified", checks };
  }
  return { level: "cataloged", label: "Cataloged", checks };
}

function overlap(left = [], right = []) {
  const rightValues = new Set(normalizedValues(right));
  return normalizedValues(left).filter((value) => rightValues.has(value)).length;
}

function sourceFamily(sourceId = "") {
  return String(sourceId).replace(/^hf:\/\//, "").split("/")[0].toLowerCase();
}

export function relatedModels(model, catalog, limit = 3) {
  return catalog
    .filter((candidate) => candidate.id !== model.id)
    .map((candidate) => {
      let score = 0;
      if (candidate.sourceId === model.sourceId) score += 100;
      else if (sourceFamily(candidate.sourceId) === sourceFamily(model.sourceId)) score += 20;
      if (candidate.architecture === model.architecture) score += 12;
      score += overlap(candidate.domains, model.domains) * 8;
      score += overlap(candidate.capabilities, model.capabilities) * 4;
      return { candidate, score };
    })
    .filter(({ score }) => score > 0)
    .sort(
      (left, right) =>
        right.score - left.score || left.candidate.name.localeCompare(right.candidate.name),
    )
    .slice(0, limit)
    .map(({ candidate }) => candidate);
}
