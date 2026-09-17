// Model profiles: vendor-published generation settings and computed memory fit.
//
// Everything here is derived from files at a model's pinned revision. Nothing is guessed: a value
// the pinned files do not publish is omitted. Memory fit is COMPUTED from GGUF header metadata; it
// is not a measurement of any runtime.
//
// Profiles live in catalog/model-profiles.json, deliberately outside catalog/models.json, so they
// never enter a marker snapshot (tools/model-publications.mjs) and never force a new marker
// coordinate.

const GIB = 1024 ** 3;

export const PROFILE_SCHEMA_VERSION = 1;
export const CONTEXT_POINTS = Object.freeze([4096, 32768, 131072, 262144]);
export const MEMORY_BUDGETS_BYTES = Object.freeze([8 * GIB, 16 * GIB, 24 * GIB]);
// An assumed allowance for the runtime (JVM heap, compute/graph buffers, allocator slack). It is a
// stated planning constant, not a measured value, and is identical for every model.
export const FIXED_OVERHEAD_BYTES = GIB;
export const KV_CACHE_TYPES = Object.freeze(["f16", "q8_0"]);

export const MEMORY_FIT_METHOD = Object.freeze({
  status: "computed from GGUF header metadata; not measured",
  formula:
    "totalBytes = weightBytes + fixedOverheadBytes + sum over attention layers l of " +
    "min(contextTokens, slidingWindow_l or contextTokens) * " +
    "(rowBytes(kvHeads_l * keyLength_l) + rowBytes(kvHeads_l * valueLength_l)); " +
    "rowBytes(n) = 2n for f16 and 34 * n / 32 for q8_0 (n must be a multiple of 32)",
  weightBytes: "the pinned artifact's sizeBytes (the GGUF file, including its header)",
  fixedOverheadBytes: FIXED_OVERHEAD_BYTES,
  budgetsBytes: MEMORY_BUDGETS_BYTES,
  contextPoints: CONTEXT_POINTS,
  maxContext:
    "largest context c <= contextLength with totalBytes(c) <= budget; 0 when even one token " +
    "does not fit",
  headerDefaults:
    "per the GGUF specification, head_count_kv defaults to head_count and key/value length to " +
    "embedding_length / head_count when absent",
  exclusions: [
    "recurrent (SSM / short-convolution) state of hybrid layers",
    "runtime-specific KV padding, sequence count > 1, and compute buffers beyond the fixed overhead",
    "sliding-window layers are charged the declared window only; a window without a declared " +
      "per-layer pattern is charged as full attention on every layer and marked upperBound",
  ],
});

// --- KV layout -------------------------------------------------------------------------------

function positiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function perLayer(value, layer) {
  return Array.isArray(value) ? value[layer] : value;
}

export function kvLayoutFromGguf(metadata, tensorInfos = []) {
  const architecture = metadata?.["general.architecture"];
  if (typeof architecture !== "string" || architecture.length === 0) {
    return null;
  }
  const key = (suffix) => metadata[`${architecture}.${suffix}`];
  const blockCount = key("block_count");
  const contextLength = key("context_length");
  const headCount = key("attention.head_count");
  if (!positiveInteger(blockCount) || !positiveInteger(contextLength) || headCount === undefined) {
    return null;
  }
  const embeddingLength = key("embedding_length");
  const headCountKv = key("attention.head_count_kv") ?? headCount;
  const recurrent =
    Object.keys(metadata).some(
      (name) =>
        name.startsWith(`${architecture}.ssm.`) || name.startsWith(`${architecture}.shortconv.`),
    ) || tensorInfos.some((tensor) => /\.(?:ssm|shortconv)[_.]/.test(tensor.name || ""));

  const attentionLayers = new Set();
  const fusedLayers = new Set();
  const recurrentLayers = new Set();
  for (const tensor of tensorInfos) {
    const match = tensor.name?.match(/^blk\.(\d+)\.(attn_q|attn_k|attn_qkv|ssm_|shortconv)/);
    if (!match) continue;
    const layer = Number.parseInt(match[1], 10);
    if (match[2] === "attn_q" || match[2] === "attn_k") {
      if (/\.attn_[qk]\.weight$/.test(tensor.name)) attentionLayers.add(layer);
    } else if (match[2] === "attn_qkv") {
      fusedLayers.add(layer);
    } else {
      recurrentLayers.add(layer);
    }
  }
  // A fused QKV projection is attention unless the same block carries recurrent state
  // (for example Qwen3.5 gated delta-net layers).
  for (const layer of fusedLayers) {
    if (!recurrentLayers.has(layer)) attentionLayers.add(layer);
  }
  if (attentionLayers.size === 0) {
    if (recurrent) {
      return null;
    }
    for (let layer = 0; layer < blockCount; layer += 1) {
      attentionLayers.add(layer);
    }
  }

  const slidingWindow = key("attention.sliding_window");
  const pattern = key("attention.sliding_window_pattern");
  const hasWindow = positiveInteger(slidingWindow);
  const patternDeclared =
    hasWindow && Array.isArray(pattern) && pattern.length === blockCount;
  const sharedKvLayers = key("attention.shared_kv_layers");

  const groups = [];
  for (const layer of [...attentionLayers].sort((left, right) => left - right)) {
    const kvHeads = perLayer(headCountKv, layer);
    const heads = perLayer(headCount, layer);
    if (kvHeads === 0) {
      continue; // a zero KV-head layer carries no attention cache
    }
    if (!positiveInteger(kvHeads) || !positiveInteger(heads)) {
      return null;
    }
    const swa = patternDeclared && pattern[layer] === true;
    const inferred =
      positiveInteger(embeddingLength) && embeddingLength % heads === 0
        ? embeddingLength / heads
        : undefined;
    const keyLength =
      (swa ? key("attention.key_length_swa") : undefined) ?? key("attention.key_length") ?? inferred;
    const valueLength =
      (swa ? key("attention.value_length_swa") : undefined) ??
      key("attention.value_length") ??
      inferred;
    if (!positiveInteger(keyLength) || !positiveInteger(valueLength)) {
      return null;
    }
    const window = swa ? slidingWindow : null;
    const existing = groups.find(
      (group) =>
        group.kvHeads === kvHeads &&
        group.keyLength === keyLength &&
        group.valueLength === valueLength &&
        group.slidingWindow === window,
    );
    if (existing) {
      existing.layers += 1;
    } else {
      groups.push({ layers: 1, kvHeads, keyLength, valueLength, slidingWindow: window });
    }
  }
  if (groups.length === 0) {
    return null;
  }

  const layout = {
    contextLength,
    groups,
    slidingWindow: hasWindow ? slidingWindow : null,
    slidingWindowPatternDeclared: Boolean(patternDeclared),
    upperBound: (hasWindow && !patternDeclared) || (positiveInteger(sharedKvLayers) && sharedKvLayers > 0),
  };
  if (recurrent) {
    layout.recurrentStateExcluded = true;
  }
  return layout;
}

export function kvRowBytes(width, type) {
  if (type === "f16") {
    return width * 2;
  }
  if (type === "q8_0") {
    return width % 32 === 0 ? (width / 32) * 34 : null;
  }
  throw new Error(`Unsupported KV cache type: ${type}`);
}

export function kvCacheBytes(layout, contextTokens, type) {
  let total = 0;
  for (const group of layout.groups) {
    const keyRow = kvRowBytes(group.kvHeads * group.keyLength, type);
    const valueRow = kvRowBytes(group.kvHeads * group.valueLength, type);
    if (keyRow === null || valueRow === null) {
      return null;
    }
    const tokens =
      group.slidingWindow === null ? contextTokens : Math.min(contextTokens, group.slidingWindow);
    total += group.layers * tokens * (keyRow + valueRow);
  }
  if (!Number.isSafeInteger(total)) {
    throw new RangeError("KV cache size exceeds the safe integer range");
  }
  return total;
}

function bytesPerToken(layout, type, sliding) {
  return layout.groups
    .filter((group) => (group.slidingWindow !== null) === sliding)
    .reduce(
      (sum, group) =>
        sum +
        group.layers *
          (kvRowBytes(group.kvHeads * group.keyLength, type) +
            kvRowBytes(group.kvHeads * group.valueLength, type)),
      0,
    );
}

function maxContext(layout, type, weightBytes, budget) {
  const available = budget - FIXED_OVERHEAD_BYTES - weightBytes;
  if (available < kvCacheBytes(layout, 1, type)) {
    return { budgetBytes: budget, maxContextTokens: 0, limitedBy: "memory" };
  }
  let low = 1;
  let high = layout.contextLength;
  while (low < high) {
    const middle = Math.floor((low + high + 1) / 2);
    if (kvCacheBytes(layout, middle, type) <= available) {
      low = middle;
    } else {
      high = middle - 1;
    }
  }
  return {
    budgetBytes: budget,
    maxContextTokens: low,
    limitedBy: low === layout.contextLength ? "context-length" : "memory",
  };
}

export function computeMemoryFit(layout, weightBytes) {
  if (!positiveInteger(weightBytes)) {
    throw new Error("weightBytes must be a positive integer");
  }
  const contexts = [
    ...new Set(CONTEXT_POINTS.map((point) => Math.min(point, layout.contextLength))),
  ].sort((left, right) => left - right);
  const notes = [];
  const kvCache = [];
  for (const type of KV_CACHE_TYPES) {
    if (kvCacheBytes(layout, 1, type) === null) {
      notes.push(
        `${type} KV cache not computed: a KV row width is not a multiple of the 32-element ${type} block`,
      );
      continue;
    }
    kvCache.push({
      type,
      bytesPerToken: bytesPerToken(layout, type, false),
      slidingWindowBytesPerToken: bytesPerToken(layout, type, true),
      contexts: contexts.map((contextTokens) => {
        const kvBytes = kvCacheBytes(layout, contextTokens, type);
        return {
          contextTokens,
          kvBytes,
          totalBytes: weightBytes + FIXED_OVERHEAD_BYTES + kvBytes,
        };
      }),
      maxContextByBudget: MEMORY_BUDGETS_BYTES.map((budget) =>
        maxContext(layout, type, weightBytes, budget),
      ),
    });
  }
  if (layout.upperBound) {
    notes.push(
      layout.slidingWindow !== null && !layout.slidingWindowPatternDeclared
        ? `sliding window ${layout.slidingWindow} declared without a per-layer pattern; every layer is charged full attention (upper bound)`
        : "shared KV layers declared; every layer is charged its own cache (upper bound)",
    );
  }
  if (layout.recurrentStateExcluded) {
    notes.push("recurrent layer state is excluded; only attention layers are charged");
  }
  return {
    status: "computed",
    layout,
    weightBytes,
    fixedOverheadBytes: FIXED_OVERHEAD_BYTES,
    upperBound: layout.upperBound,
    kvCache,
    notes,
  };
}

// --- Generation profile ----------------------------------------------------------------------

export function float32Decimal(value) {
  if (!Number.isFinite(value) || Number.isInteger(value)) {
    return value;
  }
  const target = Math.fround(value);
  for (let digits = 1; digits <= 17; digits += 1) {
    const candidate = Number(value.toPrecision(digits));
    if (Math.fround(candidate) === target) {
      return candidate;
    }
  }
  return value;
}

const THINKING_RULES = Object.freeze([
  {
    rule: "enable-thinking-defaults-true",
    value: true,
    pattern: /enable_thinking is not defined\s*-?%\}\s*\{%-?\s*set enable_thinking = true/,
  },
  {
    rule: "opt-in-enable-thinking",
    value: false,
    pattern: /enable_thinking is defined and enable_thinking is true\s*-?%\}[\s\S]*?\{%-?\s*else\s*-?%\}/,
  },
  {
    rule: "opt-out-enable-thinking",
    value: true,
    pattern: /enable_thinking is defined and enable_thinking is false/,
  },
  {
    rule: "enable-thinking-default-false",
    value: false,
    pattern: /not enable_thinking\s*\|\s*default\(false\)/,
  },
]);

export function detectThinkingDefault(template) {
  if (typeof template !== "string" || template.length === 0) {
    return null;
  }
  const matches = THINKING_RULES.filter(({ pattern }) => pattern.test(template));
  if (matches.length === 0 || matches.some((match) => match.value !== matches[0].value)) {
    return null;
  }
  return { value: matches[0].value, rule: matches[0].rule };
}

export function detectReasoningMarkers(tokens, template) {
  if (!Array.isArray(tokens) || typeof template !== "string") {
    return null;
  }
  const open = tokens.indexOf("<think>");
  const close = tokens.indexOf("</think>");
  if (open < 0 || close < 0 || !template.includes("</think>")) {
    return null;
  }
  return { open: { text: "<think>", id: open }, close: { text: "</think>", id: close } };
}

const SAMPLING_KEYS = Object.freeze([
  ["temperature", "temperature", "general.sampling.temp"],
  ["topP", "top_p", "general.sampling.top_p"],
  ["topK", "top_k", "general.sampling.top_k"],
  ["minP", "min_p", "general.sampling.min_p"],
  ["repetitionPenalty", "repetition_penalty", "general.sampling.penalty_repeat"],
]);

const GGUF_EOS_KEYS = Object.freeze([
  "tokenizer.ggml.eos_token_id",
  "tokenizer.ggml.eot_token_id",
  "tokenizer.ggml.eom_token_id",
]);

export function generationProfile({ gguf, generationConfig } = {}) {
  const sources = [];
  if (gguf) sources.push({ id: "gguf", ...gguf.source });
  if (generationConfig) sources.push({ id: "generation-config", ...generationConfig.source });

  const config = generationConfig?.document ?? {};
  const metadata = gguf?.metadata ?? {};

  const sampling = {};
  if (typeof config.do_sample === "boolean") {
    sampling.doSample = {
      value: config.do_sample,
      provenance: [{ source: "generation-config", key: "do_sample" }],
    };
  }
  for (const [name, configKey, ggufKey] of SAMPLING_KEYS) {
    const candidates = [];
    if (generationConfig && typeof config[configKey] === "number") {
      candidates.push({ source: "generation-config", key: configKey, value: config[configKey] });
    }
    if (gguf && typeof metadata[ggufKey] === "number") {
      candidates.push({ source: "gguf", key: ggufKey, value: float32Decimal(metadata[ggufKey]) });
    }
    if (candidates.length === 0) continue;
    const chosen = candidates[0].value;
    const entry = {
      value: chosen,
      provenance: candidates
        .filter((candidate) => candidate.value === chosen)
        .map(({ source, key }) => ({ source, key })),
    };
    const conflicts = candidates.filter((candidate) => candidate.value !== chosen);
    if (conflicts.length > 0) entry.conflicts = conflicts;
    sampling[name] = entry;
  }

  const eos = new Map();
  const addEos = (id, source, key) => {
    if (!Number.isSafeInteger(id) || id < 0) return;
    if (!eos.has(id)) eos.set(id, []);
    eos.get(id).push({ source, key });
  };
  if (gguf) {
    for (const key of GGUF_EOS_KEYS) addEos(metadata[key], "gguf", key);
  }
  if (generationConfig) {
    const configured = config.eos_token_id;
    for (const id of Array.isArray(configured) ? configured : [configured]) {
      addEos(id, "generation-config", "eos_token_id");
    }
  }
  const eosTokenIds = [...eos].map(([id, provenance]) => ({ id, provenance }));

  const reasoning = {};
  if (gguf) {
    const template = metadata["tokenizer.chat_template"];
    const markers = detectReasoningMarkers(gguf.tokens, template);
    if (markers) {
      const provenance = [{ source: "gguf", key: "tokenizer.ggml.tokens" }];
      reasoning.openToken = { ...markers.open, provenance };
      reasoning.closeToken = { ...markers.close, provenance };
    }
    const thinking = detectThinkingDefault(template);
    if (thinking) {
      reasoning.thinkingDefault = {
        ...thinking,
        provenance: [{ source: "gguf", key: "tokenizer.chat_template" }],
      };
    }
  }

  const profile = { sources };
  if (Object.keys(sampling).length > 0) profile.sampling = sampling;
  if (eosTokenIds.length > 0) profile.eosTokenIds = eosTokenIds;
  if (Object.keys(reasoning).length > 0) profile.reasoning = reasoning;
  return Object.keys(profile).length === 1 ? null : profile;
}

// --- Validation ------------------------------------------------------------------------------

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

function collectProvenance(value, found = []) {
  if (Array.isArray(value)) {
    value.forEach((item) => collectProvenance(item, found));
  } else if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (key === "provenance" || key === "conflicts") {
        found.push(...item);
      } else {
        collectProvenance(item, found);
      }
    }
  }
  return found;
}

export function validateModelProfiles(document, catalog) {
  if (
    document === null ||
    typeof document !== "object" ||
    document.schemaVersion !== PROFILE_SCHEMA_VERSION ||
    !Array.isArray(document.profiles)
  ) {
    throw new Error("catalog/model-profiles.json must be a schemaVersion 1 profile catalog");
  }
  if (canonical(document.memoryFitMethod) !== canonical(MEMORY_FIT_METHOD)) {
    throw new Error("memoryFitMethod does not match tools/model-profiles.mjs");
  }
  const models = new Map(catalog.models.map((model) => [model.id, model]));
  const seen = new Set();
  for (const profile of document.profiles) {
    const label = `model profile ${profile?.modelId}`;
    const model = models.get(profile?.modelId);
    if (model === undefined) throw new Error(`${label} references an unknown catalog model`);
    if (seen.has(profile.modelId)) throw new Error(`${label} is duplicated`);
    seen.add(profile.modelId);
    if (profile.artifactSha256 !== model.sha256) {
      throw new Error(`${label} artifactSha256 does not match the catalog artifact`);
    }
    if (profile.revision !== model.revision) {
      throw new Error(`${label} revision does not match the catalog pin`);
    }
    if (profile.generation === undefined && profile.memoryFit === undefined) {
      throw new Error(`${label} carries neither a generation profile nor a memory fit`);
    }
    if (profile.generation !== undefined) {
      const ids = new Set();
      for (const source of profile.generation.sources ?? []) {
        if (
          typeof source.id !== "string" ||
          typeof source.kind !== "string" ||
          typeof source.file !== "string" ||
          typeof source.uri !== "string" ||
          source.revision !== model.revision ||
          !/^[0-9a-f]{64}$/.test(source.sha256 ?? "")
        ) {
          throw new Error(`${label} has a generation source without pinned provenance`);
        }
        ids.add(source.id);
      }
      const references = collectProvenance(profile.generation);
      if (references.length === 0 || references.some((reference) => !ids.has(reference.source))) {
        throw new Error(`${label} generation value lacks declared provenance`);
      }
    }
    if (profile.memoryFit !== undefined) {
      if (model.format !== "gguf") {
        throw new Error(`${label} memoryFit requires a GGUF artifact`);
      }
      const expected = computeMemoryFit(profile.memoryFit.layout, model.sizeBytes);
      if (canonical(expected) !== canonical(profile.memoryFit)) {
        throw new Error(`${label} memoryFit does not recompute from its layout and sizeBytes`);
      }
    }
  }
  for (const entry of document.coverage ?? []) {
    if (!models.has(entry.modelId)) {
      throw new Error(`coverage references unknown catalog model ${entry.modelId}`);
    }
  }
  return true;
}
