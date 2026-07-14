import assert from "node:assert/strict";
import test from "node:test";

import { estimateMemory, formatBytes, formatParameters } from "./resource-profile.js";

test("estimates weights plus a full-precision KV cache", () => {
  const model = {
    sizeBytes: 1_000_000_000,
    dimensions: {
      contextLength: 32_768,
      embeddingLength: 4_096,
      blockCount: 32,
      attentionBlockCount: 32,
      attentionHeadCount: 32,
      keyValueHeadCount: 8,
    },
  };

  assert.deepEqual(estimateMemory(model, 4_096, 2), {
    contextTokens: 4_096,
    weightBytes: 1_000_000_000,
    kvCacheBytes: 536_870_912,
    minimumBytes: 1_536_870_912,
    kvElementBytes: 2,
  });
});

test("does not invent an estimate when dimensions are incomplete", () => {
  assert.equal(estimateMemory({ sizeBytes: 42, dimensions: {} }, 4_096, 2), null);
});

test("formats binary sizes for catalog cards", () => {
  assert.equal(formatBytes(1_536_870_912), "1.43 GiB");
});

test("uses attention blocks rather than total hybrid blocks", () => {
  const estimate = estimateMemory(
    {
      sizeBytes: 1_000,
      dimensions: {
        contextLength: 262_144,
        embeddingLength: 5_120,
        blockCount: 64,
        attentionBlockCount: 16,
        attentionHeadCount: 24,
        keyValueHeadCount: 4,
        keyLength: 256,
        valueLength: 256,
      },
    },
    1,
    2,
  );

  assert.equal(estimate.kvCacheBytes, 65_536);
});

test("formats parameter counts", () => {
  assert.equal(formatParameters(3_075_098_624), "3.08B");
  assert.equal(formatParameters(596_049_920), "596M");
});
