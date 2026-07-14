export function normalize(value) {
  return String(value || "").toLowerCase();
}

export function matches(model, query, backend) {
  const text = [
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
    (model.domains || []).join(" "),
    (model.capabilities || []).join(" "),
    (model.features || []).join(" "),
    Object.keys(model.backends || {}).join(" "),
  ].join(" ");

  const queryMatches = !query || normalize(text).includes(normalize(query));
  const backendMatches = !backend || model.backends?.[backend] === true;
  return queryMatches && backendMatches;
}
