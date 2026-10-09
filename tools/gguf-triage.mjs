/**
 * Triage a GGUF before implementing anything against it.
 *
 * This exists because the same questions were answered by hand-rolled throwaway scripts over and
 * over: what architecture is this, does the file actually carry the towers its model card claims,
 * which quantizations dominate its weights, and is there anything in the header we cannot read.
 * Range-fetching the real header answers all four in seconds and has repeatedly caught blockers
 * that no synthetic fixture could.
 *
 * Deliberately separate from the narrow attention-focused projection in `inspect-gguf.mjs`: that
 * one answers "what does the decoder need"; this one answers "should we touch this file at all".
 */
import { inspectGguf } from "./gguf-inspector.mjs";

/**
 * Tensor-name prefixes that mark a non-text tower.
 *
 * Secondary evidence only. Name conventions differ per converter -- a CLIP projector uses `v.` and
 * `mm.`, whisper uses `enc.`/`dec.`/`frontend.` -- so the header namespaces below are checked first
 * and these catch the rest. An earlier version of this tool used names alone and reported
 * whisper-tiny as "text only" while it carried 67 encoder tensors.
 */
const TOWER_TENSOR_PATTERNS = [
  ["vision", /^(?:v\.|vision|visual|patch_embed|img)/i],
  ["audio", /^(?:a\.|audio|frontend\.|enc\.|mel|speech)/i],
  ["projector", /^(?:mm\.|mmproj|multi_modal|resampler|merger)/i],
];

/**
 * Header namespaces that state a modality outright, which is stronger than any name heuristic.
 *
 * `clip.has_vision_encoder` and the `stt.*` family are declarations by the converter, not guesses
 * by us.
 */
const TOWER_METADATA_RULES = [
  ["vision", (metadata) => metadata["clip.has_vision_encoder"] === true
      || Object.keys(metadata).some((key) => key.startsWith("clip.vision."))],
  ["audio", (metadata) => Object.keys(metadata).some((key) => key.startsWith("stt.")
      || key.startsWith("clip.has_audio") || key.startsWith("tts."))],
  ["projector", (metadata) => typeof metadata["clip.projector_type"] === "string"],
];

/**
 * GGUF type id -> [name, block elements, block bytes].
 *
 * Generated from the library's own declaration -- `GgufTensorType` in
 * `models/backend-java/.../gguf/GgufTensorType.java` -- rather than written from memory, so the
 * tool and the loader cannot disagree about a block size. Regenerate when that enum gains a type.
 */
const GGUF_TYPES = {
  0: ['F32', 1, 4],
  1: ['F16', 1, 2],
  2: ['Q4_0', 32, 18],
  3: ['Q4_1', 32, 20],
  6: ['Q5_0', 32, 22],
  7: ['Q5_1', 32, 24],
  8: ['Q8_0', 32, 34],
  9: ['Q8_1', 32, 36],
  10: ['Q2_K', 256, 84],
  11: ['Q3_K', 256, 110],
  12: ['Q4_K', 256, 144],
  13: ['Q5_K', 256, 176],
  14: ['Q6_K', 256, 210],
  15: ['Q8_K', 256, 292],
  30: ['BF16', 1, 2],
  39: ['MXFP4', 32, 17],
  34: ['TQ1_0', 256, 54],
  35: ['TQ2_0', 256, 66],
  142: ['PQ2_0', 128, 34],
  143: ['PTQ1_0', 128, 28],
};

/** Collapses `blk.14.attn_q.weight` to `blk.N.attn_q.weight` so roots and shapes aggregate. */
export function normalizeTensorName(name) {
  return name.replace(/\.\d+\./g, ".N.");
}

/** The leading path element, which is what distinguishes a tower from the text stack. */
export function tensorRoot(name) {
  return normalizeTensorName(name).split(".")[0];
}

/**
 * Which non-text towers the file actually contains.
 *
 * A model card claiming image understanding is not evidence; a vision tensor is. This returns the
 * towers found, so a catalog `capabilities` list can be checked against the artifact.
 */
export function detectTowers(tensorNames, metadata = {}) {
  const found = new Map();
  for (const [tower, rule] of TOWER_METADATA_RULES) {
    if (rule(metadata)) {
      found.set(tower, "declared in header");
    }
  }
  for (const name of tensorNames) {
    for (const [tower, pattern] of TOWER_TENSOR_PATTERNS) {
      if (pattern.test(name)) {
        const existing = found.get(tower);
        found.set(tower, typeof existing === "number" ? existing + 1 : existing ?? 1);
      }
    }
  }
  return found;
}

/**
 * Element and byte totals per quantization, plus each type's share of the weights.
 *
 * `excludeLookups` drops tensors that are read by lookup rather than multiplied -- the token
 * embedding is usually the single widest tensor in a file and counting it makes every matmul
 * share look negligible. Getting this denominator wrong once produced a share table that was off
 * by a factor of two.
 */
export function weightShare(tensorInfos, { excludeLookups = true } = {}) {
  const rows = new Map();
  let total = 0;
  for (const tensor of tensorInfos) {
    const name = tensor.name ?? "";
    const isLookup = /(?:^|\.)token_embd|(?:^|\.)tok_embeddings/.test(name);
    if (excludeLookups && isLookup) {
      continue;
    }
    const dims = tensor.shape ?? [];
    const elements = dims.reduce((product, dimension) => product * Number(dimension), 1);
    const known = GGUF_TYPES[tensor.dtype];
    const type = known ? known[0] : `dtype:${tensor.dtype}`;
    const bytes = known ? (elements / known[1]) * known[2] : 0;
    const row = rows.get(type) ?? { type, tensors: 0, elements: 0, bytes: 0 };
    row.tensors += 1;
    row.elements += elements;
    row.bytes += bytes;
    rows.set(type, row);
    total += elements;
  }
  const ordered = [...rows.values()].sort((left, right) => right.elements - left.elements);
  for (const row of ordered) {
    row.share = total === 0 ? 0 : row.elements / total;
  }
  return { rows: ordered, totalElements: total };
}

/** Everything the header says, minus the tokenizer bulk, with arrays summarized not discarded. */
export function summarizeMetadata(metadata) {
  const out = {};
  for (const [key, value] of Object.entries(metadata)) {
    if (key.startsWith("tokenizer.ggml.") && /tokens|merges|scores|token_type/.test(key)) {
      out[key] = `[omitted: ${Array.isArray(value) ? value.length : "bulk"}]`;
      continue;
    }
    if (Array.isArray(value)) {
      // Arrays are NOT dropped. A per-layer kv head count, a rope section layout and a deepstack
      // layer list are all arrays and all decide whether a file loads at all.
      out[key] = value.length <= 12 ? value : `[${value.length} values: ${value.slice(0, 6).join(", ")}, ...]`;
      continue;
    }
    out[key] = value;
  }
  return out;
}

/** Full triage for one already-inspected GGUF. */
export function triage({ metadata, tensorInfos }) {
  const names = tensorInfos.map((tensor) => tensor.name ?? "");
  const roots = new Map();
  for (const name of names) {
    const root = tensorRoot(name);
    roots.set(root, (roots.get(root) ?? 0) + 1);
  }
  return {
    architecture: metadata["general.architecture"] ?? "(absent)",
    name: metadata["general.name"] ?? undefined,
    license: metadata["general.license"] ?? undefined,
    tensorCount: tensorInfos.length,
    towers: Object.fromEntries(detectTowers(names, metadata)),
    tensorRoots: Object.fromEntries([...roots.entries()].sort((a, b) => b[1] - a[1])),
    weights: weightShare(tensorInfos),
    metadata: summarizeMetadata(metadata),
  };
}

/** Fetches and triages a GGUF by URI. */
export async function triageUri(uri, options = {}) {
  return triage(await inspectGguf(uri, options));
}
