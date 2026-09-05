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

export const AUDIOCPP_EMBEDDED_METADATA_KEYS = Object.freeze([
  "audiocpp.embedded_files.names",
  "audiocpp.embedded_files.offsets",
  "audiocpp.embedded_files.data",
]);

export function assertGgufIdentity(model, metadata) {
  const remoteArchitecture = metadata["general.architecture"];
  const expectedArchitecture = model.ggufArchitecture || model.architecture;
  if (remoteArchitecture !== expectedArchitecture) {
    throw new Error(
      `architecture mismatch: catalog=${expectedArchitecture}, GGUF=${remoteArchitecture}`,
    );
  }
  if (model.ggufModelFamily !== undefined) {
    const remoteFamily = metadata[`${remoteArchitecture}.model_spec.family`];
    if (remoteFamily !== model.ggufModelFamily) {
      throw new Error(
        `model family mismatch: catalog=${model.ggufModelFamily}, GGUF=${remoteFamily}`,
      );
    }
  }
}

export function extractGgufDimensions(metadata, parameterCount, tensorInfos = []) {
  const architecture = metadata["general.architecture"];
  if (typeof architecture !== "string" || architecture.length === 0) {
    throw new Error("GGUF metadata does not declare general.architecture");
  }
  if (!Number.isSafeInteger(parameterCount) || parameterCount <= 0) {
    throw new Error("GGUF parameter count must be a positive safe integer");
  }
  if (
    architecture === "audiocpp" &&
    metadata["audiocpp.model_spec.family"] === "soprano_tts"
  ) {
    return extractSopranoDimensions(metadata, parameterCount);
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
    const match = tensor.name?.match(
      /^(?:(?:blk|block)\.(\d+)\.attn_q|model\.layers\.(\d+)\.self_attn\.q_proj)\.weight$/,
    );
    if (match) {
      attentionBlocks.add(Number.parseInt(match[1] || match[2], 10));
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

function extractSopranoDimensions(metadata, parameterCount) {
  const config = JSON.parse(new TextDecoder().decode(embeddedFile(metadata, "config.json")));
  if (config.model_type !== "qwen3") {
    throw new Error(`Soprano config model_type must be qwen3, got ${config.model_type}`);
  }
  const hiddenSize = positiveInteger(config.hidden_size, "hidden_size");
  const layers = positiveInteger(config.num_hidden_layers, "num_hidden_layers");
  const headDimension = positiveInteger(config.head_dim, "head_dim");
  return {
    parameterCount,
    contextLength: positiveInteger(config.max_position_embeddings, "max_position_embeddings"),
    embeddingLength: hiddenSize,
    blockCount: layers,
    attentionBlockCount: layers,
    attentionHeadCount: positiveInteger(config.num_attention_heads, "num_attention_heads"),
    keyValueHeadCount: positiveInteger(config.num_key_value_heads, "num_key_value_heads"),
    keyLength: headDimension,
    valueLength: headDimension,
    feedForwardLength: positiveInteger(config.intermediate_size, "intermediate_size"),
  };
}

function embeddedFile(metadata, requestedName) {
  const names = metadata["audiocpp.embedded_files.names"];
  const offsets = metadata["audiocpp.embedded_files.offsets"];
  const data = metadata["audiocpp.embedded_files.data"];
  if (!Array.isArray(names) || !Array.isArray(offsets) || !(data instanceof Uint8Array)) {
    throw new Error("audio.cpp GGUF is missing its retained embedded-file metadata");
  }
  if (offsets.length !== names.length + 1) {
    throw new Error("audio.cpp embedded-file offsets must contain one more entry than names");
  }
  const index = names.indexOf(requestedName);
  if (index < 0) {
    throw new Error(`audio.cpp GGUF does not embed ${requestedName}`);
  }
  const start = offsets[index];
  const end = offsets[index + 1];
  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(end) ||
    start < 0 ||
    end < start ||
    end > data.length
  ) {
    throw new Error(`audio.cpp embedded-file range is invalid for ${requestedName}`);
  }
  return data.subarray(start, end);
}

function positiveInteger(value, name) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`Soprano config ${name} must be a positive integer`);
  }
  return value;
}
