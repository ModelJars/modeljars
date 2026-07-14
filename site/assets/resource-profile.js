export function estimateMemory(model, contextTokens, kvElementBytes = 2) {
  const dimensions = model.dimensions || {};
  const {
    embeddingLength,
    blockCount,
    attentionBlockCount,
    attentionHeadCount,
    keyValueHeadCount = attentionHeadCount,
    keyLength,
    valueLength,
  } = dimensions;
  const inferredHeadLength =
    Number.isSafeInteger(embeddingLength) &&
    Number.isSafeInteger(attentionHeadCount) &&
    embeddingLength % attentionHeadCount === 0
      ? embeddingLength / attentionHeadCount
      : null;
  const keyWidth = keyLength || inferredHeadLength;
  const valueWidth = valueLength || inferredHeadLength;

  if (
    !Number.isSafeInteger(model.sizeBytes) ||
    !Number.isSafeInteger(contextTokens) ||
    contextTokens <= 0 ||
    !Number.isSafeInteger(kvElementBytes) ||
    kvElementBytes <= 0 ||
    !Number.isSafeInteger(embeddingLength) ||
    !Number.isSafeInteger(blockCount) ||
    !Number.isSafeInteger(attentionBlockCount) ||
    !Number.isSafeInteger(attentionHeadCount) ||
    !Number.isSafeInteger(keyValueHeadCount) ||
    !Number.isSafeInteger(keyWidth) ||
    !Number.isSafeInteger(valueWidth) ||
    (dimensions.contextLength && contextTokens > dimensions.contextLength)
  ) {
    return null;
  }

  const kvCacheBytes =
    contextTokens *
    attentionBlockCount *
    keyValueHeadCount *
    (keyWidth + valueWidth) *
    kvElementBytes;
  if (!Number.isSafeInteger(kvCacheBytes + model.sizeBytes)) {
    return null;
  }

  return {
    contextTokens,
    weightBytes: model.sizeBytes,
    kvCacheBytes,
    minimumBytes: model.sizeBytes + kvCacheBytes,
    kvElementBytes,
  };
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "Unknown";
  }
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${unit === 0 ? value : value.toFixed(2)} ${units[unit]}`;
}

export function formatParameters(parameters) {
  if (!Number.isFinite(parameters) || parameters <= 0) {
    return "Unknown";
  }
  const units = [
    [1_000_000_000_000, "T"],
    [1_000_000_000, "B"],
    [1_000_000, "M"],
    [1_000, "K"],
  ];
  for (const [divisor, suffix] of units) {
    if (parameters >= divisor) {
      const value = parameters / divisor;
      const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
      return `${Number(value.toFixed(digits))}${suffix}`;
    }
  }
  return parameters.toLocaleString("en-US");
}
