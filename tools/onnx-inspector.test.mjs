import assert from "node:assert/strict";
import test from "node:test";

import { parseOnnxModel, summarizeOnnx } from "./onnx-inspector.mjs";

// A hand-rolled protobuf encoder, enough to build a ModelProto the way an exporter would.
const varint = (n) => {
  const out = [];
  let v = BigInt(n);
  do {
    let b = Number(v & 0x7fn);
    v >>= 7n;
    if (v) b |= 0x80;
    out.push(b);
  } while (v);
  return out;
};
const field = (num, wt, payload) => [...varint((num << 3) | wt), ...payload];
const vint = (num, n) => field(num, 0, varint(n));
const str = (num, s) => { const b = [...Buffer.from(s, "utf8")]; return field(num, 2, [...varint(b.length), ...b]); };
const msg = (num, bytes) => field(num, 2, [...varint(bytes.length), ...bytes]);

const dimValue = (n) => msg(1, vint(1, n));
const dimParam = (s) => msg(1, str(2, s));
const valueInfo = (name, elem, dims) =>
  [...str(1, name), ...msg(2, msg(1, [...vint(1, elem), ...msg(2, dims.flat())]))];
const node = (op, domain = "") => [...str(4, op), ...(domain ? str(7, domain) : [])];
const initializer = (name) => [...vint(2, 1), ...str(8, name)];

function paradeeShapedModel() {
  const graph = [
    ...msg(1, node("Conv")), ...msg(1, node("LSTM")), ...msg(1, node("Conv")),
    ...msg(1, node("RandomNormalLike")), ...msg(1, node("Custom", "com.example")),
    ...str(2, "main_graph"),
    ...msg(5, initializer("w0")), ...msg(5, initializer("w1")),
    ...msg(11, valueInfo("input_ids", 7, [dimValue(1), dimParam("T")])),
    ...msg(11, valueInfo("speed", 1, [dimValue(1)])),
    ...msg(11, valueInfo("voice", 7, [dimValue(1)])),
    ...msg(12, valueInfo("waveform", 1, [dimParam("Addwaveform_dim_0"), dimParam("samples")])),
  ];
  return Uint8Array.from([
    ...vint(1, 8), ...str(2, "pytorch"), ...str(3, "2.14.1"),
    ...msg(7, graph),
    ...msg(8, [...str(1, ""), ...vint(2, 17)]),
  ]);
}

test("reads the declared interface, opset, producer and operator histogram", () => {
  const summary = summarizeOnnx(parseOnnxModel(paradeeShapedModel()));
  assert.equal(summary.irVersion, 8);
  assert.equal(summary.producer, "pytorch 2.14.1");
  assert.deepEqual(summary.opset, { "ai.onnx": 17 });
  assert.deepEqual(summary.inputs, [
    { name: "input_ids", elemType: "INT64", dims: [1, "T"] },
    { name: "speed", elemType: "FLOAT", dims: [1] },
    { name: "voice", elemType: "INT64", dims: [1] },
  ]);
  assert.deepEqual(summary.outputs, [
    { name: "waveform", elemType: "FLOAT", dims: ["Addwaveform_dim_0", "samples"] },
  ]);
  assert.equal(summary.nodeCount, 5);
  assert.equal(summary.initializerCount, 2);
  assert.equal(summary.operatorTypes, 4);
  assert.deepEqual(summary.opTypes, { Conv: 2, LSTM: 1, RandomNormalLike: 1, "com.example.Custom": 1 });
});

test("refuses bytes with no graph rather than guessing", () => {
  assert.throws(() => parseOnnxModel(Uint8Array.from([...vint(1, 8)])), /no graph/);
});

test("refuses a length that runs past the message", () => {
  const broken = Uint8Array.from([...field(7, 2, [...varint(1000), 1, 2, 3])]);
  assert.throws(() => parseOnnxModel(broken), /past the message/);
});
