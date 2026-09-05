import assert from "node:assert/strict";
import test from "node:test";

import {
  assertGgufIdentity,
  extractGgufDimensions,
} from "./gguf-metadata.mjs";

test("extracts architecture-specific dimensions from GGUF metadata", () => {
  const metadata = {
    "general.architecture": "qwen2",
    "qwen2.context_length": 32_768,
    "qwen2.embedding_length": 3_584,
    "qwen2.block_count": 28,
    "qwen2.attention.head_count": 28,
    "qwen2.attention.head_count_kv": 4,
    "qwen2.attention.key_length": 128,
    "qwen2.attention.value_length": 128,
    "qwen2.feed_forward_length": 18_944,
  };

  const tensorInfos = [
    { name: "blk.0.attn_q.weight" },
    { name: "blk.1.attn_q.weight" },
    { name: "blk.1.ffn_up.weight" },
  ];

  assert.deepEqual(extractGgufDimensions(metadata, 7_615_616_000, tensorInfos), {
    parameterCount: 7_615_616_000,
    contextLength: 32_768,
    embeddingLength: 3_584,
    blockCount: 28,
    attentionBlockCount: 2,
    attentionHeadCount: 28,
    keyValueHeadCount: 4,
    keyLength: 128,
    valueLength: 128,
    feedForwardLength: 18_944,
  });
});

test("keeps explicit asymmetric attention dimensions", () => {
  const metadata = {
    "general.architecture": "qwen35",
    "qwen35.context_length": 262_144,
    "qwen35.embedding_length": 5_120,
    "qwen35.block_count": 64,
    "qwen35.attention.head_count": 48,
    "qwen35.attention.head_count_kv": 8,
    "qwen35.attention.key_length": 256,
    "qwen35.attention.value_length": 128,
  };

  const tensorInfos = [
    { name: "blk.3.attn_q.weight" },
    { name: "blk.7.attn_q.weight" },
    { name: "blk.0.ssm_a" },
  ];

  assert.deepEqual(extractGgufDimensions(metadata, 27_000_000_000, tensorInfos), {
    parameterCount: 27_000_000_000,
    contextLength: 262_144,
    embeddingLength: 5_120,
    blockCount: 64,
    attentionBlockCount: 2,
    attentionHeadCount: 48,
    keyValueHeadCount: 8,
    keyLength: 256,
    valueLength: 128,
  });
});

test("keeps MoE dimensions and omits unavailable values", () => {
  const metadata = {
    "general.architecture": "qwen3moe",
    "qwen3moe.context_length": 131_072,
    "qwen3moe.expert_count": 128,
    "qwen3moe.expert_used_count": 8,
  };

  assert.deepEqual(extractGgufDimensions(metadata, 30_500_000_000, []), {
    parameterCount: 30_500_000_000,
    contextLength: 131_072,
    expertCount: 128,
    expertUsedCount: 8,
  });
});

test("verifies a catalog family inside an audio.cpp GGUF package", () => {
  const model = {
    architecture: "soprano",
    ggufArchitecture: "audiocpp",
    ggufModelFamily: "soprano_tts",
  };
  const metadata = {
    "general.architecture": "audiocpp",
    "audiocpp.model_spec.family": "soprano_tts",
  };

  assert.doesNotThrow(() => assertGgufIdentity(model, metadata));
  assert.throws(
    () =>
      assertGgufIdentity(model, {
        ...metadata,
        "audiocpp.model_spec.family": "different_model",
      }),
    /model family mismatch/,
  );
});

test("extracts Soprano dimensions from the config embedded by audio.cpp", () => {
  const config = Buffer.from(
    JSON.stringify({
      model_type: "qwen3",
      hidden_size: 512,
      intermediate_size: 2_304,
      num_hidden_layers: 17,
      num_attention_heads: 4,
      num_key_value_heads: 1,
      head_dim: 128,
      vocab_size: 8_192,
      max_position_embeddings: 1_024,
    }),
  );
  const metadata = {
    "general.architecture": "audiocpp",
    "audiocpp.model_spec.family": "soprano_tts",
    "audiocpp.embedded_files.names": ["config.json"],
    "audiocpp.embedded_files.offsets": [0, config.length],
    "audiocpp.embedded_files.data": new Uint8Array(config),
  };

  assert.deepEqual(extractGgufDimensions(metadata, 110_068_738, []), {
    parameterCount: 110_068_738,
    contextLength: 1_024,
    embeddingLength: 512,
    blockCount: 17,
    attentionBlockCount: 17,
    attentionHeadCount: 4,
    keyValueHeadCount: 1,
    keyLength: 128,
    valueLength: 128,
    feedForwardLength: 2_304,
  });
});
