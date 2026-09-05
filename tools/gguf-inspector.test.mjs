import assert from "node:assert/strict";
import test from "node:test";

import { inspectGguf } from "./gguf-inspector.mjs";

const SOPRANO_TOKEN_COUNT = 1_631_868;

test("inspects GGUF dimensions with a compact Soprano-sized embedded tokenizer", async () => {
  const gguf = sopranoFixture(SOPRANO_TOKEN_COUNT);
  const requests = [];
  const rangeFetch = async (_input, init) => {
    const match = /^bytes=(\d+)-(\d+)$/.exec(init.headers.Range);
    assert.ok(match, "inspector must use bounded range requests");
    const start = Number.parseInt(match[1], 10);
    const requestedEnd = Number.parseInt(match[2], 10);
    const end = Math.min(requestedEnd, gguf.length - 1);
    const body = gguf.subarray(start, end + 1);
    requests.push({ start, end, length: body.length });
    return new Response(body, {
      status: 206,
      headers: { "Content-Range": `bytes ${start}-${end}/${gguf.length}` },
    });
  };

  const parsed = await inspectGguf("https://example.test/soprano.gguf", {
    fetch: rangeFetch,
    chunkSize: 1_000_000,
    retainMetadataArrays: [
      "audiocpp.embedded_files.names",
      "audiocpp.embedded_files.offsets",
      "audiocpp.embedded_files.data",
    ],
  });

  assert.equal(parsed.metadata["general.architecture"], "audiocpp");
  assert.equal(parsed.metadata["audiocpp.model_spec.family"], "soprano_tts");
  assert.deepEqual(parsed.metadata["audiocpp.embedded_files.names"], ["config.json"]);
  assert.deepEqual(parsed.metadata["audiocpp.embedded_files.offsets"], [0, SOPRANO_TOKEN_COUNT]);
  assert.ok(parsed.metadata["audiocpp.embedded_files.data"] instanceof Uint8Array);
  assert.equal(
    parsed.metadata["audiocpp.embedded_files.data"].length,
    SOPRANO_TOKEN_COUNT,
  );
  assert.equal(parsed.parameterCount, 138_240);
  assert.deepEqual(
    parsed.tensorInfos.map(({ name, shape }) => ({ name, shape })),
    [
      { name: "audio_in.weight", shape: [80, 768] },
      { name: "audio_out.weight", shape: [768, 100] },
    ],
  );
  assert.ok(requests.length > 1, "large metadata must be streamed in chunks");
  assert.ok(
    requests.every(({ length }) => length <= SOPRANO_TOKEN_COUNT),
    "range responses must remain bounded by the retained byte array",
  );
});

test("rejects servers that ignore byte ranges before reading a complete model", async () => {
  await assert.rejects(
    inspectGguf("https://example.test/model.gguf", {
      fetch: async () => new Response(new Uint8Array(32), { status: 200 }),
    }),
    /HTTP byte ranges/,
  );
});

function sopranoFixture(tokenCount) {
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
  const embeddedData = Buffer.alloc(tokenCount, 0x20);
  config.copy(embeddedData);
  const parts = [
    Buffer.from("GGUF"),
    uint32(3),
    uint64(2),
    uint64(5),
    metadataString("general.architecture", "audiocpp"),
    metadataString("audiocpp.model_spec.family", "soprano_tts"),
    metadataStringArray("audiocpp.embedded_files.names", ["config.json"]),
    metadataUint64Array("audiocpp.embedded_files.offsets", [0, tokenCount]),
    metadataUint8Array("audiocpp.embedded_files.data", embeddedData),
    tensor("audio_in.weight", [80, 768]),
    tensor("audio_out.weight", [768, 100]),
  ];
  return Buffer.concat(parts);
}

function metadataString(key, value) {
  return Buffer.concat([ggufString(key), uint32(8), ggufString(value)]);
}

function metadataStringArray(key, values) {
  return Buffer.concat([
    ggufString(key),
    uint32(9),
    uint32(8),
    uint64(values.length),
    ...values.map(ggufString),
  ]);
}

function metadataUint64Array(key, values) {
  return Buffer.concat([
    ggufString(key),
    uint32(9),
    uint32(10),
    uint64(values.length),
    ...values.map(uint64),
  ]);
}

function metadataUint8Array(key, values) {
  return Buffer.concat([
    ggufString(key),
    uint32(9),
    uint32(0),
    uint64(values.length),
    values,
  ]);
}

function tensor(name, shape) {
  return Buffer.concat([
    ggufString(name),
    uint32(shape.length),
    ...shape.map(uint64),
    uint32(0),
    uint64(0),
  ]);
}

function ggufString(value) {
  const encoded = Buffer.from(value, "utf8");
  return Buffer.concat([uint64(encoded.length), encoded]);
}

function uint32(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32LE(value);
  return buffer;
}

function uint64(value) {
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(BigInt(value));
  return buffer;
}
