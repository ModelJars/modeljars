import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  CONTEXT_POINTS,
  FIXED_OVERHEAD_BYTES,
  MEMORY_BUDGETS_BYTES,
  computeMemoryFit,
  detectReasoningMarkers,
  detectThinkingDefault,
  detectTurnTerminator,
  float32Decimal,
  generationProfile,
  kvCacheBytes,
  kvLayoutFromGguf,
  validateModelProfiles,
} from "./model-profiles.mjs";
import { renderChatTemplate } from "./chat-template-render.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const GIB = 1024 ** 3;

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

function tensors(layers, { withKey = () => true } = {}) {
  return Array.from({ length: layers }, (_, index) => index)
    .filter(withKey)
    .map((index) => ({ name: `blk.${index}.attn_k.weight`, shape: [1, 1] }));
}

// Hand-computed fixtures use the real header values of pinned catalog artifacts.
const qwen3_8b = {
  metadata: {
    "general.architecture": "qwen3",
    "qwen3.block_count": 36,
    "qwen3.context_length": 40960,
    "qwen3.embedding_length": 4096,
    "qwen3.attention.head_count": 32,
    "qwen3.attention.head_count_kv": 8,
    "qwen3.attention.key_length": 128,
    "qwen3.attention.value_length": 128,
  },
  tensorInfos: tensors(36),
  weightBytes: 5_027_783_488,
};

const gemma4_26b = {
  metadata: {
    "general.architecture": "gemma4",
    "gemma4.block_count": 30,
    "gemma4.context_length": 262144,
    "gemma4.embedding_length": 2816,
    "gemma4.attention.head_count": 16,
    "gemma4.attention.head_count_kv": [
      8, 8, 8, 8, 8, 2, 8, 8, 8, 8, 8, 2, 8, 8, 8, 8, 8, 2, 8, 8, 8, 8, 8, 2, 8, 8, 8, 8, 8, 2,
    ],
    "gemma4.attention.key_length": 512,
    "gemma4.attention.value_length": 512,
    "gemma4.attention.key_length_swa": 256,
    "gemma4.attention.value_length_swa": 256,
    "gemma4.attention.sliding_window": 1024,
    "gemma4.attention.shared_kv_layers": 0,
    "gemma4.attention.sliding_window_pattern": Array.from(
      { length: 30 },
      (_, index) => index % 6 !== 5,
    ),
  },
  tensorInfos: tensors(30),
  weightBytes: 16_796_015_136,
};

const gemma3_1b = {
  metadata: {
    "general.architecture": "gemma3",
    "gemma3.block_count": 26,
    "gemma3.context_length": 32768,
    "gemma3.embedding_length": 1152,
    "gemma3.attention.head_count": 4,
    "gemma3.attention.head_count_kv": 1,
    "gemma3.attention.key_length": 256,
    "gemma3.attention.value_length": 256,
    "gemma3.attention.sliding_window": 512,
  },
  tensorInfos: tensors(26),
  weightBytes: 806_058_496,
};

test("states the memory-fit constants explicitly", () => {
  assert.deepEqual(CONTEXT_POINTS, [4096, 32768, 131072, 262144]);
  assert.equal(FIXED_OVERHEAD_BYTES, GIB);
  assert.deepEqual(MEMORY_BUDGETS_BYTES, [8 * GIB, 16 * GIB, 24 * GIB]);
});

test("derives a dense GQA KV layout and hand-computed Qwen3 8B fit", () => {
  const layout = kvLayoutFromGguf(qwen3_8b.metadata, qwen3_8b.tensorInfos);
  assert.deepEqual(layout, {
    contextLength: 40960,
    groups: [{ layers: 36, kvHeads: 8, keyLength: 128, valueLength: 128, slidingWindow: null }],
    slidingWindow: null,
    slidingWindowPatternDeclared: false,
    upperBound: false,
  });
  // f16: 36 layers x 8 heads x (128 + 128) x 2 bytes = 147,456 bytes/token.
  assert.equal(kvCacheBytes(layout, 1, "f16"), 147_456);
  // q8_0: row width 8 x 128 = 1,024 = 32 blocks x 34 bytes = 1,088 per K and V row.
  assert.equal(kvCacheBytes(layout, 1, "q8_0"), 78_336);

  const fit = computeMemoryFit(layout, qwen3_8b.weightBytes);
  assert.equal(fit.weightBytes, 5_027_783_488);
  assert.equal(fit.fixedOverheadBytes, GIB);
  const f16 = fit.kvCache.find((entry) => entry.type === "f16");
  const q8 = fit.kvCache.find((entry) => entry.type === "q8_0");
  assert.equal(f16.bytesPerToken, 147_456);
  assert.equal(f16.slidingWindowBytesPerToken, 0);
  assert.deepEqual(f16.contexts, [
    { contextTokens: 4096, kvBytes: 603_979_776, totalBytes: 6_705_505_088 },
    { contextTokens: 32768, kvBytes: 4_831_838_208, totalBytes: 10_933_363_520 },
    { contextTokens: 40960, kvBytes: 6_039_797_760, totalBytes: 12_141_323_072 },
  ]);
  // (8 GiB - 1 GiB - 5,027,783,488) / 147,456 = 16,875.5 -> 16,875 tokens.
  assert.deepEqual(f16.maxContextByBudget, [
    { budgetBytes: 8 * GIB, maxContextTokens: 16_875, limitedBy: "memory" },
    { budgetBytes: 16 * GIB, maxContextTokens: 40_960, limitedBy: "context-length" },
    { budgetBytes: 24 * GIB, maxContextTokens: 40_960, limitedBy: "context-length" },
  ]);
  assert.equal(q8.contexts[0].totalBytes, 6_422_389_568);
  assert.equal(q8.maxContextByBudget[0].maxContextTokens, 31_765);
});

test("charges sliding-window layers only up to the declared window (Gemma 4 26B)", () => {
  const layout = kvLayoutFromGguf(gemma4_26b.metadata, gemma4_26b.tensorInfos);
  assert.deepEqual(layout.groups, [
    { layers: 25, kvHeads: 8, keyLength: 256, valueLength: 256, slidingWindow: 1024 },
    { layers: 5, kvHeads: 2, keyLength: 512, valueLength: 512, slidingWindow: null },
  ]);
  assert.equal(layout.slidingWindowPatternDeclared, true);
  assert.equal(layout.upperBound, false);

  const fit = computeMemoryFit(layout, gemma4_26b.weightBytes);
  const f16 = fit.kvCache.find((entry) => entry.type === "f16");
  // Global: 5 x 2 x 1,024 x 2 = 20,480/token. SWA: 25 x 8 x 512 x 2 = 204,800/token for 1,024 tokens.
  assert.equal(f16.bytesPerToken, 20_480);
  assert.equal(f16.slidingWindowBytesPerToken, 204_800);
  assert.deepEqual(f16.contexts[0], {
    contextTokens: 4096,
    kvBytes: 293_601_280,
    totalBytes: 18_163_358_240,
  });
  assert.deepEqual(
    f16.contexts.map((entry) => entry.contextTokens),
    [4096, 32768, 131072, 262144],
  );
  assert.equal(f16.contexts[3].totalBytes, 23_448_181_280);
  assert.deepEqual(f16.maxContextByBudget, [
    { budgetBytes: 8 * GIB, maxContextTokens: 0, limitedBy: "memory" },
    { budgetBytes: 16 * GIB, maxContextTokens: 0, limitedBy: "memory" },
    { budgetBytes: 24 * GIB, maxContextTokens: 262_144, limitedBy: "context-length" },
  ]);
});

test("labels an undeclared sliding-window pattern as a full-attention upper bound", () => {
  const layout = kvLayoutFromGguf(gemma3_1b.metadata, gemma3_1b.tensorInfos);
  assert.equal(layout.slidingWindow, 512);
  assert.equal(layout.slidingWindowPatternDeclared, false);
  assert.equal(layout.upperBound, true);
  assert.deepEqual(layout.groups, [
    { layers: 26, kvHeads: 1, keyLength: 256, valueLength: 256, slidingWindow: null },
  ]);
  const fit = computeMemoryFit(layout, gemma3_1b.weightBytes);
  assert.equal(fit.upperBound, true);
  // 26 x 1 x 512 x 2 = 26,624 bytes/token.
  assert.equal(fit.kvCache[0].bytesPerToken, 26_624);
  assert.equal(fit.kvCache[0].contexts[0].totalBytes, 806_058_496 + 26_624 * 4096 + GIB);
});

test("counts only blocks that carry attention tensors in hybrid recurrent models", () => {
  const layout = kvLayoutFromGguf(
    {
      "general.architecture": "qwen35",
      "qwen35.block_count": 24,
      "qwen35.context_length": 262144,
      "qwen35.attention.head_count": 8,
      "qwen35.attention.head_count_kv": 2,
      "qwen35.attention.key_length": 256,
      "qwen35.attention.value_length": 256,
      "qwen35.ssm.state_size": 128,
    },
    Array.from({ length: 24 }, (_, index) =>
      index % 4 === 3
        ? [{ name: `blk.${index}.attn_q.weight` }, { name: `blk.${index}.attn_k.weight` }]
        : // Gated delta-net layers carry a fused attn_qkv projection but recurrent state, not KV.
          [{ name: `blk.${index}.attn_qkv.weight` }, { name: `blk.${index}.ssm_conv1d.weight` }],
    ).flat(),
  );
  assert.deepEqual(layout.groups, [
    { layers: 6, kvHeads: 2, keyLength: 256, valueLength: 256, slidingWindow: null },
  ]);
  assert.equal(layout.recurrentStateExcluded, true);
});

test("refuses q8_0 KV rows whose width is not a whole number of 32-element blocks", () => {
  const layout = {
    contextLength: 2048,
    groups: [{ layers: 2, kvHeads: 3, keyLength: 7, valueLength: 7, slidingWindow: null }],
    slidingWindow: null,
    slidingWindowPatternDeclared: false,
    upperBound: false,
  };
  assert.equal(kvCacheBytes(layout, 1, "q8_0"), null);
  const fit = computeMemoryFit(layout, 1_000);
  assert.deepEqual(
    fit.kvCache.map((entry) => entry.type),
    ["f16"],
  );
  assert.match(fit.notes.join(" "), /q8_0/);
});

test("returns no layout when the header lacks the KV dimensions", () => {
  assert.equal(
    kvLayoutFromGguf({ "general.architecture": "bert", "bert.block_count": 6 }, []),
    null,
  );
});

test("renders stored float32 sampling values as their shortest decimal", () => {
  assert.equal(float32Decimal(Math.fround(0.95)), 0.95);
  assert.equal(float32Decimal(Math.fround(0.7)), 0.7);
  assert.equal(float32Decimal(1), 1);
});

test("detects thinking defaults only from recognised chat-template idioms", () => {
  assert.deepEqual(
    detectThinkingDefault(
      "{%- if add_generation_prompt %}{%- if enable_thinking is defined and enable_thinking is false %}{{- '<think>\\n\\n</think>\\n\\n' }}{%- endif %}",
    ),
    { value: true, rule: "opt-out-enable-thinking" },
  );
  assert.deepEqual(
    detectThinkingDefault(
      "{%- if enable_thinking is not defined -%}\n{%- set enable_thinking = true -%}\n{%- endif -%}",
    ),
    { value: true, rule: "enable-thinking-defaults-true" },
  );
  assert.deepEqual(
    detectThinkingDefault(
      "{%- if enable_thinking is defined and enable_thinking is true %}{{- '<think>\\n' }}{%- else %}{{- '<think>\\n\\n</think>\\n\\n' }}{%- endif %}",
    ),
    { value: false, rule: "opt-in-enable-thinking" },
  );
  assert.deepEqual(
    detectThinkingDefault("{%- if not enable_thinking | default(false) -%}"),
    { value: false, rule: "enable-thinking-default-false" },
  );
  // MiniCPM5 leaves an undefined flag to the model: not determinable from the file.
  assert.equal(
    detectThinkingDefault(
      "{%- if enable_thinking is defined %}{%- if enable_thinking is false %}x{%- elif enable_thinking is true %}y{%- endif %}{%- endif %}",
    ),
    null,
  );
  assert.equal(detectThinkingDefault(""), null);
});

test("records think markers only when the vocabulary defines them and the template uses them", () => {
  const tokens = ["a", "<think>", "</think>"];
  assert.deepEqual(detectReasoningMarkers(tokens, "split('</think>')"), {
    open: { text: "<think>", id: 1 },
    close: { text: "</think>", id: 2 },
  });
  assert.equal(detectReasoningMarkers(tokens, "no markers here"), null);
  assert.equal(detectReasoningMarkers(["a"], "</think>"), null);
});

test("builds a provenance-carrying profile from GGUF metadata and generation_config.json", () => {
  const gguf = {
    kind: "gguf-metadata",
    file: "UmarTransit-1B.gguf",
    uri: "https://huggingface.co/x/y/resolve/rev/UmarTransit-1B.gguf",
    revision: "rev",
    sha256: "a".repeat(64),
  };
  const config = {
    kind: "hf-generation-config",
    file: "generation_config.json",
    uri: "https://huggingface.co/x/y/resolve/rev/generation_config.json",
    revision: "rev",
    sha256: "b".repeat(64),
  };
  const profile = generationProfile({
    gguf: {
      source: gguf,
      metadata: {
        "general.sampling.temp": Math.fround(0.7),
        "general.sampling.top_p": Math.fround(0.8),
        "general.sampling.top_k": 20,
        "tokenizer.ggml.eos_token_id": 151645,
        "tokenizer.chat_template":
          "{%- if enable_thinking is defined and enable_thinking is false %}</think>",
      },
      tokens: Object.assign(new Array(151_669).fill("x"), {
        151667: "<think>",
        151668: "</think>",
      }),
    },
    generationConfig: {
      source: config,
      document: {
        eos_token_id: [151645, 151643],
        repetition_penalty: 1.1,
        temperature: 0.7,
        top_k: 20,
        top_p: 0.8,
      },
    },
  });

  assert.deepEqual(profile.sources, [
    { id: "gguf", ...gguf },
    { id: "generation-config", ...config },
  ]);
  assert.deepEqual(profile.sampling.temperature, {
    value: 0.7,
    provenance: [
      { source: "generation-config", key: "temperature" },
      { source: "gguf", key: "general.sampling.temp" },
    ],
  });
  assert.deepEqual(profile.sampling.repetitionPenalty, {
    value: 1.1,
    provenance: [{ source: "generation-config", key: "repetition_penalty" }],
  });
  assert.equal(profile.sampling.minP, undefined);
  assert.deepEqual(profile.eosTokenIds, [
    {
      id: 151645,
      provenance: [
        { source: "gguf", key: "tokenizer.ggml.eos_token_id" },
        { source: "generation-config", key: "eos_token_id" },
      ],
    },
    { id: 151643, provenance: [{ source: "generation-config", key: "eos_token_id" }] },
  ]);
  assert.deepEqual(profile.reasoning, {
    openToken: {
      text: "<think>",
      id: 151667,
      provenance: [{ source: "gguf", key: "tokenizer.ggml.tokens" }],
    },
    closeToken: {
      text: "</think>",
      id: 151668,
      provenance: [{ source: "gguf", key: "tokenizer.ggml.tokens" }],
    },
    thinkingDefault: {
      value: true,
      rule: "opt-out-enable-thinking",
      provenance: [{ source: "gguf", key: "tokenizer.chat_template" }],
    },
  });
});

test("keeps disagreeing sampling values visible instead of silently choosing one", () => {
  const profile = generationProfile({
    gguf: {
      source: { kind: "gguf-metadata", file: "m.gguf", uri: "u", revision: "r", sha256: "a".repeat(64) },
      metadata: { "general.sampling.temp": 1 },
      tokens: [],
    },
    generationConfig: {
      source: {
        kind: "hf-generation-config",
        file: "generation_config.json",
        uri: "u",
        revision: "r",
        sha256: "b".repeat(64),
      },
      document: { temperature: 0.6 },
    },
  });
  assert.deepEqual(profile.sampling.temperature, {
    value: 0.6,
    provenance: [{ source: "generation-config", key: "temperature" }],
    conflicts: [{ source: "gguf", key: "general.sampling.temp", value: 1 }],
  });
});

test("omits every value the pinned files do not publish", () => {
  const profile = generationProfile({
    gguf: {
      source: { kind: "gguf-metadata", file: "m.gguf", uri: "u", revision: "r", sha256: "a".repeat(64) },
      metadata: {},
      tokens: [],
    },
  });
  assert.equal(profile, null);
});

test("the committed profile catalog binds exact artifacts and recomputes exactly", async () => {
  const [profiles, catalog] = await Promise.all([
    read("catalog/model-profiles.json").then(JSON.parse),
    read("catalog/models.json").then(JSON.parse),
  ]);
  assert.doesNotThrow(() => validateModelProfiles(profiles, catalog));
  assert.ok(profiles.profiles.length > 0);
});

test("validation rejects stale artifact bindings and hand-edited memory tables", async () => {
  const [profiles, catalog] = await Promise.all([
    read("catalog/model-profiles.json").then(JSON.parse),
    read("catalog/models.json").then(JSON.parse),
  ]);
  const withFit = profiles.profiles.find((profile) => profile.memoryFit);
  assert.ok(withFit, "at least one committed profile must carry a memory fit");

  const stale = structuredClone(profiles);
  stale.profiles.find((profile) => profile.modelId === withFit.modelId).artifactSha256 =
    "0".repeat(64);
  assert.throws(() => validateModelProfiles(stale, catalog), /artifactSha256/);

  const edited = structuredClone(profiles);
  edited.profiles.find((profile) => profile.modelId === withFit.modelId).memoryFit.kvCache[0]
    .contexts[0].totalBytes += 1;
  assert.throws(() => validateModelProfiles(edited, catalog), /memoryFit/);
});

async function templateFixture(id) {
  const fixture = JSON.parse(await read("tools/fixtures/chat-templates/gguf-chat-templates.json"))[id];
  const tokens = [];
  const tokenTypes = [];
  for (const [index, text] of Object.entries(fixture.controlTokens)) {
    tokens[Number(index)] = text;
    tokenTypes[Number(index)] = 3; // GGUF token_type CONTROL
  }
  return {
    fixture,
    input: {
      template: fixture.chatTemplate,
      tokens,
      tokenTypes,
      bosToken: tokens[fixture.bosTokenId],
      eosToken: tokens[fixture.eosTokenId],
    },
  };
}

function ggufWithTemplate(fixture, input) {
  return {
    source: { kind: "gguf-metadata", file: "model.gguf", uri: "https://example", revision: fixture.revision, sha256: fixture.artifactSha256 },
    metadata: {
      "tokenizer.ggml.eos_token_id": fixture.eosTokenId,
      "tokenizer.chat_template": fixture.chatTemplate,
    },
    tokens: input.tokens,
  };
}

test("reads the assistant-turn terminator by rendering the pinned chat template (Gemma 3 1B, MiniCPM5)", async () => {
  for (const [id, text, tokenId] of [
    ["bartowski_google_gemma_3_1b_it_gguf_q4_k_m", "<end_of_turn>", 106],
    ["minicpm5_1b_q4_k_m", "<|im_end|>", 130073],
  ]) {
    const { fixture, input } = await templateFixture(id);
    const terminator = detectTurnTerminator(input, renderChatTemplate);
    assert.deepEqual(terminator, { status: "detected", text, id: tokenId }, id);

    const profile = generationProfile({ gguf: ggufWithTemplate(fixture, input), turnTerminator: terminator });
    assert.deepEqual(
      profile.eosTokenIds,
      [
        { id: fixture.eosTokenId, provenance: [{ source: "gguf", key: "tokenizer.ggml.eos_token_id" }] },
        { id: tokenId, provenance: [{ source: "gguf", key: "tokenizer.chat_template", token: text }] },
      ],
      id,
    );
  }
});

test("adds no end-of-generation token the header already declares (Qwen3 8B)", async () => {
  const { fixture, input } = await templateFixture("qwen3_8b_q4_k_m");
  const terminator = detectTurnTerminator(input, renderChatTemplate);
  assert.deepEqual(terminator, { status: "detected", text: "<|im_end|>", id: 151645 });
  const profile = generationProfile({ gguf: ggufWithTemplate(fixture, input), turnTerminator: terminator });
  assert.deepEqual(profile.eosTokenIds, [
    { id: 151645, provenance: [{ source: "gguf", key: "tokenizer.ggml.eos_token_id" }] },
  ]);
});

test("never guesses a terminator the template does not render", async () => {
  // Nexus Medical ships a placeholder string, not a Jinja template: the assistant content never
  // appears in the rendering, so nothing can be read from it.
  const { input } = await templateFixture("king3djbl_nexus_medical_gguf_q4_k_m");
  const terminator = detectTurnTerminator(input, renderChatTemplate);
  assert.equal(terminator.status, "not determined");
  assert.match(terminator.reason, /assistant content/);

  assert.equal(detectTurnTerminator({ ...input, template: undefined }, renderChatTemplate).status, "not determined");
  // Plain text after the content is not a token, so it is not a terminator.
  assert.equal(
    detectTurnTerminator(
      { ...input, template: "{% for m in messages %}{{ m.content }} END{% endfor %}" },
      renderChatTemplate,
    ).status,
    "not determined",
  );
  // A renderer failure is reported, not swallowed into a value.
  const failing = detectTurnTerminator(input, () => {
    throw new Error("unsupported filter");
  });
  assert.deepEqual(failing, { status: "not determined", reason: "chat template did not render: unsupported filter" });
});

test("the committed profiles carry every rendered terminator the declared EOS missed", async () => {
  const profiles = JSON.parse(await read("catalog/model-profiles.json"));
  const eos = (id) => profiles.profiles.find((profile) => profile.modelId === id).generation.eosTokenIds;
  for (const [id, text, tokenId] of [
    ["bartowski_google_gemma_3_1b_it_gguf_q4_k_m", "<end_of_turn>", 106],
    ["minicpm5_1b_q4_k_m", "<|im_end|>", 130073],
    ["ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m", "<turn|>", 106],
    ["bartowski_yi_coder_1_5b_chat_gguf_q4_k_m", "<|im_end|>", 7],
  ]) {
    assert.deepEqual(
      eos(id).find((entry) => entry.id === tokenId),
      { id: tokenId, provenance: [{ source: "gguf", key: "tokenizer.chat_template", token: text }] },
      id,
    );
  }
  assert.deepEqual(eos("qwen3_8b_q4_k_m").map((entry) => entry.id), [151645]);
  const coverage = profiles.coverage.find((entry) => entry.modelId === "king3djbl_nexus_medical_gguf_q4_k_m");
  assert.match(coverage.turnTerminator, /^not determined/);
});

test("model profiles stay outside every marker identity input", async () => {
  const [catalog, workflow, build] = await Promise.all([
    read("catalog/models.json").then(JSON.parse),
    read(".github/workflows/model-artifacts.yml"),
    read("build.gradle.kts"),
  ]);
  for (const model of catalog.models) {
    assert.equal(model.generationProfile, undefined, model.id);
    assert.equal(model.memoryFit, undefined, model.id);
    assert.equal(model.modelProfile, undefined, model.id);
  }
  assert.doesNotMatch(workflow, /model-profiles/);
  const markerSection = build.slice(build.indexOf("catalogEntries.forEach { entry ->\n        val suffix"));
  assert.ok(markerSection.length > 0);
  assert.doesNotMatch(
    markerSection.slice(0, markerSection.indexOf("val aggregateCatalogJar")),
    /model-profiles|catalogModelProfile|modelProfile\./,
  );
});
