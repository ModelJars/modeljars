// Generation-safety measurements: the repetition-loop stop rate at a model's documented generation
// profile.
//
// Every value in catalog/generation-safety.json must come from a measured run; nothing here is
// computed or estimated, and a model without a recorded run has no value (displayed as "not
// measured"), never zero. The manifest lives outside catalog/qualifications.json on purpose:
// qualification entries are embedded in immutable marker JARs, so a metric recorded after a marker
// was published would otherwise force a new marker coordinate. Like catalog/model-profiles.json it
// ships only in the aggregate catalog, the website, and the CLI.

export const GENERATION_SAFETY_SCHEMA_VERSION = 1;
export const MINIMUM_MODELS_VERSION = "0.3.41";

export const REPETITION_LOOP_METHOD = Object.freeze({
  status: "measured; absent until a run is recorded",
  metric:
    "stopRate = stops / generations: the fraction of completed generations that the Models " +
    "repetition-loop detector stopped with StopReason.REPETITION_LOOP",
  generationProfile:
    "sampling must apply every value catalog/model-profiles.json documents for the exact artifact " +
    "(temperature, top-p, top-k, min-p, repetition penalty) with exactly the documented values; " +
    "settings the profile does not document keep the runtime defaults and are recorded as applied " +
    "when set; a model whose profile documents no sampling value has no documented generation " +
    "profile and cannot carry this metric",
  detector:
    "SamplingOptions.repetitionLoopDetection(new RepetitionLoopDetection(maxSpan, minRepeats, " +
    "minLoopTokens)), enabled (maxSpan > 0, minRepeats >= 2); the thresholds are recorded with " +
    "the value because the rate depends on them",
  counter:
    "stops is the delta of RuntimeTextGenerationModel.repetitionLoopStops() (or " +
    "GenerationLoop.repetitionLoopStops() / ContinuousBatchingMetrics.repetitionLoopStops()) " +
    "across the run, cross-checked against the per-generation stop reasons in the report",
  run:
    "one generation per workload case with a fresh model state, on the recorded backend and " +
    "Models version (0.3.41 or later, the first release with the detector); the raw report is " +
    "published in integrallis/models at modelsRevision and bound by reportSha256",
  limits:
    "exact periodicity only: instructed or legitimately repeated output (a zero-filled array, a " +
    "table rule) also counts as a stop, and a loop whose tokens drift is not detected; a rate of " +
    "zero on a workload that never elicits loops is no data about loops, not evidence of their " +
    "absence",
});

const SAMPLING_KEYS = Object.freeze(["temperature", "topP", "topK", "minP", "repetitionPenalty"]);

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function compareVersions(left, right) {
  const parse = (value) => value.split(".").map((part) => Number.parseInt(part, 10));
  const [a, b] = [parse(left), parse(right)];
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] - b[index];
  }
  return 0;
}

function nonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

export function validateGenerationSafety(document, catalog, profileCatalog) {
  if (
    document === null ||
    typeof document !== "object" ||
    document.schemaVersion !== GENERATION_SAFETY_SCHEMA_VERSION ||
    Number.isNaN(Date.parse(document.generatedAt ?? "")) ||
    !Array.isArray(document.entries)
  ) {
    throw new Error("catalog/generation-safety.json must be a schemaVersion 1 manifest");
  }
  if (canonical(document.repetitionLoopMethod) !== canonical(REPETITION_LOOP_METHOD)) {
    throw new Error("repetitionLoopMethod does not match tools/generation-safety.mjs");
  }
  const models = new Map(catalog.models.map((model) => [model.id, model]));
  const profiles = new Map(profileCatalog.profiles.map((profile) => [profile.modelId, profile]));
  const seen = new Set();
  for (const entry of document.entries) {
    const label = `repetition-loop measurement ${entry?.modelId}/${entry?.backend}`;
    const model = models.get(entry?.modelId);
    if (model === undefined) throw new Error(`${label} references an unknown catalog model`);
    if (entry.artifactSha256 !== model.sha256) {
      throw new Error(`${label} artifactSha256 does not match the catalog artifact`);
    }
    if (typeof entry.backend !== "string" || entry.backend.length === 0) {
      throw new Error(`${label} must name its backend`);
    }
    const key = JSON.stringify([entry.modelId, entry.backend, entry.workload]);
    if (seen.has(key)) throw new Error(`${label} is duplicated`);
    seen.add(key);
    if (
      typeof entry.modelsVersion !== "string" ||
      !/^\d+\.\d+\.\d+$/.test(entry.modelsVersion) ||
      compareVersions(entry.modelsVersion, MINIMUM_MODELS_VERSION) < 0
    ) {
      throw new Error(`${label} requires Models ${MINIMUM_MODELS_VERSION} or later`);
    }
    if (!/^[0-9a-f]{40}$/.test(entry.modelsRevision ?? "")) {
      throw new Error(`${label} modelsRevision must be a 40-character commit`);
    }
    if (typeof entry.workload !== "string" || entry.workload.length === 0) {
      throw new Error(`${label} must name its workload`);
    }
    if (
      typeof entry.report !== "string" ||
      entry.report.length === 0 ||
      entry.report.startsWith("/") ||
      entry.report.split("/").includes("..")
    ) {
      throw new Error(`${label} report must be a repository-relative path`);
    }
    if (!/^[0-9a-f]{64}$/.test(entry.reportSha256 ?? "")) {
      throw new Error(`${label} reportSha256 must be a SHA-256 digest`);
    }

    const documented = profiles.get(model.id)?.generation?.sampling ?? {};
    const documentedKeys = SAMPLING_KEYS.filter((name) => documented[name] !== undefined);
    if (documentedKeys.length === 0) {
      throw new Error(`${label}: the model profile documents no sampling values`);
    }
    const applied = entry.sampling ?? {};
    for (const name of Object.keys(applied)) {
      if (!SAMPLING_KEYS.includes(name) || typeof applied[name] !== "number") {
        throw new Error(`${label} records an unknown or non-numeric sampling value ${name}`);
      }
    }
    for (const name of documentedKeys) {
      if (applied[name] === undefined) {
        throw new Error(
          `${label}: ${name} is documented as ${documented[name].value} but was not applied`,
        );
      }
      if (applied[name] !== documented[name].value) {
        throw new Error(
          `${label}: ${name} ${applied[name]} is not the documented ${documented[name].value}`,
        );
      }
    }

    const detector = entry.detector ?? {};
    if (
      !Number.isSafeInteger(detector.maxSpan) ||
      detector.maxSpan <= 0 ||
      !Number.isSafeInteger(detector.minRepeats) ||
      detector.minRepeats < 2 ||
      !nonNegativeInteger(detector.minLoopTokens)
    ) {
      throw new Error(`${label}: the repetition-loop detector must be enabled with valid thresholds`);
    }
    if (!Number.isSafeInteger(entry.generations) || entry.generations < 1) {
      throw new Error(`${label}: generations must be a positive integer`);
    }
    if (!nonNegativeInteger(entry.stops) || entry.stops > entry.generations) {
      throw new Error(`${label}: stops must be an integer from 0 to generations`);
    }
    if (entry.stopRate !== entry.stops / entry.generations) {
      throw new Error(`${label}: stopRate must equal stops / generations`);
    }
  }
  return true;
}
