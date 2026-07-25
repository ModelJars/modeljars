const TRANSIENT_HTTP_STATUSES = new Set([408, 425, 429, 500, 502, 503, 504]);
const TRANSIENT_NETWORK_CODES = new Set([
  "ECONNRESET",
  "ECONNREFUSED",
  "EHOSTUNREACH",
  "ENETUNREACH",
  "ETIMEDOUT",
  "UND_ERR_CONNECT_TIMEOUT",
  "UND_ERR_HEADERS_TIMEOUT",
  "UND_ERR_SOCKET",
]);

export function createRetryingFetch({
  fetchImpl = globalThis.fetch,
  maxAttempts = 5,
  sleep = delay,
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new TypeError("fetchImpl must be a function");
  }
  if (!Number.isSafeInteger(maxAttempts) || maxAttempts <= 0) {
    throw new TypeError("maxAttempts must be a positive integer");
  }

  return async function retryingFetch(input, init) {
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        const response = await fetchImpl(input, init);
        if (response.ok) {
          return response;
        }

        await response.body?.cancel().catch(() => {});
        const failure = new Error(`HTTP ${response.status} while fetching ${input}`);
        failure.retryable = TRANSIENT_HTTP_STATUSES.has(response.status);
        throw failure;
      } catch (failure) {
        if (attempt === maxAttempts || !isTransientFailure(failure)) {
          throw failure;
        }
        await sleep(1_000 * 2 ** (attempt - 1));
      }
    }

    throw new Error("Unreachable retry state");
  };
}

export function changedGgufModelIds(baselineDocument, currentDocument) {
  const baselineById = catalogModels(baselineDocument, "baseline").reduce(
    (models, model) => models.set(model.id, model),
    new Map(),
  );

  return catalogModels(currentDocument, "current")
    .filter((model) => model.format === "gguf")
    .filter((model) => {
      const baseline = baselineById.get(model.id);
      return baseline === undefined || JSON.stringify(baseline) !== JSON.stringify(model);
    })
    .map((model) => model.id);
}

function catalogModels(document, label) {
  if (!document || !Array.isArray(document.models)) {
    throw new TypeError(`${label} catalog must contain a models array`);
  }

  const ids = new Set();
  for (const model of document.models) {
    if (!model || typeof model.id !== "string" || model.id.length === 0) {
      throw new TypeError(`${label} catalog contains a model without an id`);
    }
    if (ids.has(model.id)) {
      throw new TypeError(`${label} catalog contains duplicate model id ${model.id}`);
    }
    ids.add(model.id);
  }
  return document.models;
}

function isTransientFailure(failure) {
  if (failure?.retryable === true || failure instanceof TypeError) {
    return true;
  }

  let cause = failure;
  while (cause) {
    if (TRANSIENT_NETWORK_CODES.has(cause.code)) {
      return true;
    }
    cause = cause.cause;
  }
  return false;
}

function delay(delayMillis) {
  return new Promise((resolve) => setTimeout(resolve, delayMillis));
}
