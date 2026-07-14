const DIMENSION_KEYS = {
  contextLength: "context_length",
  embeddingLength: "embedding_length",
  blockCount: "block_count",
  attentionHeadCount: "attention.head_count",
  keyValueHeadCount: "attention.head_count_kv",
  keyLength: "attention.key_length",
  valueLength: "attention.value_length",
  feedForwardLength: "feed_forward_length",
  expertCount: "expert_count",
  expertUsedCount: "expert_used_count",
};

export function extractGgufDimensions(metadata, parameterCount, tensorInfos = []) {
  const architecture = metadata["general.architecture"];
  if (typeof architecture !== "string" || architecture.length === 0) {
    throw new Error("GGUF metadata does not declare general.architecture");
  }
  if (!Number.isSafeInteger(parameterCount) || parameterCount <= 0) {
    throw new Error("GGUF parameter count must be a positive safe integer");
  }

  const dimensions = { parameterCount };
  for (const [name, suffix] of Object.entries(DIMENSION_KEYS)) {
    const value = metadata[`${architecture}.${suffix}`];
    if (Number.isSafeInteger(value) && value > 0) {
      dimensions[name] = value;
    }
  }
  const attentionBlocks = new Set();
  for (const tensor of tensorInfos) {
    const match = tensor.name?.match(/^(?:blk|block)\.(\d+)\.attn_q\.weight$/);
    if (match) {
      attentionBlocks.add(Number.parseInt(match[1], 10));
    }
  }
  if (attentionBlocks.size > 0) {
    dimensions.attentionBlockCount = attentionBlocks.size;
  } else {
    const hybrid =
      Object.keys(metadata).some(
        (key) => key.startsWith(`${architecture}.ssm.`) || key.startsWith(`${architecture}.shortconv.`),
      ) || tensorInfos.some((tensor) => /\.(?:ssm|shortconv)[_.]/.test(tensor.name || ""));
    if (!hybrid && dimensions.blockCount) {
      dimensions.attentionBlockCount = dimensions.blockCount;
    }
  }
  return dimensions;
}
