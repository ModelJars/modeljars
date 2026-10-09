import assert from "node:assert/strict";
import test from "node:test";

import { detectTowers, normalizeTensorName, tensorRoot, weightShare } from "./gguf-triage.mjs";

test("collapses layer indices so roots and shapes aggregate", () => {
  assert.equal(normalizeTensorName("blk.14.attn_q.weight"), "blk.N.attn_q.weight");
  assert.equal(tensorRoot("blk.14.attn_q.weight"), "blk");
  assert.equal(tensorRoot("v.blk.3.ln1.weight"), "v");
});

test("believes the header over the tensor names", () => {
  // whisper names its towers enc./dec./frontend., which no vision/audio word matches. An earlier
  // version used names alone and called whisper-tiny "text only" while it carried 67 encoder
  // tensors, so the header namespace is primary evidence.
  const towers = detectTowers(["enc.blk.0.attn_q.weight", "dec.blk.0.cross_attn_k.weight"], {
    "stt.frontend.type": "mel",
  });
  assert.ok(towers.has("audio"), "stt.* in the header must mark the file as audio");
});

test("reports a vision tower from clip metadata and from v. tensors", () => {
  assert.ok(detectTowers([], { "clip.has_vision_encoder": true }).has("vision"));
  assert.ok(detectTowers(["v.blk.0.attn_q.weight"], {}).has("vision"));
  assert.ok(detectTowers([], { "clip.projector_type": "qwen3vl_merger" }).has("projector"));
});

test("finds no tower in a text-only file", () => {
  // The case that matters: a model card claiming vision is not evidence, a vision tensor is.
  const towers = detectTowers(
    ["token_embd.weight", "blk.0.attn_q.weight", "output_norm.weight", "altup_proj.weight"],
    { "general.architecture": "gemma3n" },
  );
  assert.equal(towers.size, 0);
});

test("excludes the lookup embedding from the weight-share denominator", () => {
  // token_embd is usually the widest tensor in the file and is read by lookup, not multiplied.
  // Counting it once produced a share table that was wrong by about a factor of two.
  const infos = [
    { name: "token_embd.weight", shape: [1024, 65536], dtype: 8 },
    { name: "blk.0.attn_q.weight", shape: [1024, 1024], dtype: 12 },
    { name: "blk.0.ffn_up.weight", shape: [1024, 1024], dtype: 7 },
  ];
  const { rows, totalElements } = weightShare(infos);
  assert.equal(totalElements, 1024 * 1024 * 2);
  assert.deepEqual(rows.map((r) => r.type).sort(), ["Q4_K", "Q5_1"]);
  for (const row of rows) {
    assert.ok(Math.abs(row.share - 0.5) < 1e-9, `${row.type} should be half the weights`);
  }
  const included = weightShare(infos, { excludeLookups: false });
  assert.ok(included.totalElements > totalElements, "opting in must count the embedding");
});

test("names an unknown dtype instead of silently dropping it", () => {
  const { rows } = weightShare([{ name: "blk.0.w", shape: [32], dtype: 999 }]);
  assert.equal(rows[0].type, "dtype:999");
  assert.equal(rows[0].bytes, 0);
});
