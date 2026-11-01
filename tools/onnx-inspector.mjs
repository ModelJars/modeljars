/**
 * Reads what an ONNX artifact declares about itself: IR version, producer, opset imports, the
 * graph's typed inputs and outputs, and the operator histogram. Nothing is executed.
 *
 * ONNX is a protobuf `ModelProto`; the graph's `input` and `output` fields (11 and 12) are
 * serialized after its initializers, so unlike GGUF the whole file has to be read to reach the
 * declarations. The reader walks the wire format directly and skips initializer payloads without
 * copying them. Field numbers follow onnx/onnx.proto3.
 *
 *   node tools/onnx-inspector.mjs <file-or-url>
 */
import { readFileSync } from "node:fs";

const ELEM_TYPES = new Map([
  [1, "FLOAT"], [2, "UINT8"], [3, "INT8"], [4, "UINT16"], [5, "INT16"], [6, "INT32"],
  [7, "INT64"], [8, "STRING"], [9, "BOOL"], [10, "FLOAT16"], [11, "DOUBLE"], [12, "UINT32"],
  [13, "UINT64"], [14, "COMPLEX64"], [15, "COMPLEX128"], [16, "BFLOAT16"],
]);

const decoder = new TextDecoder("utf-8", { fatal: false });

/** Yields the fields of one protobuf message held in `u8[start, end)`. */
function* fields(u8, start, end) {
  let pos = start;
  while (pos < end) {
    const tag = readVarint(u8, pos, end);
    pos = tag.pos;
    const number = Number(tag.value >> 3n);
    const wireType = Number(tag.value & 7n);
    switch (wireType) {
      case 0: {
        const v = readVarint(u8, pos, end);
        pos = v.pos;
        yield { number, wireType, varint: v.value };
        break;
      }
      case 1:
        if (pos + 8 > end) throw new RangeError(`fixed64 field ${number} runs past the message`);
        yield { number, wireType, bytes: u8.subarray(pos, pos + 8) };
        pos += 8;
        break;
      case 2: {
        const len = readVarint(u8, pos, end);
        const length = Number(len.value);
        pos = len.pos;
        if (pos + length > end) throw new RangeError(`field ${number} declares ${length} bytes past the message`);
        yield { number, wireType, bytes: u8.subarray(pos, pos + length) };
        pos += length;
        break;
      }
      case 5:
        if (pos + 4 > end) throw new RangeError(`fixed32 field ${number} runs past the message`);
        yield { number, wireType, bytes: u8.subarray(pos, pos + 4) };
        pos += 4;
        break;
      default:
        throw new Error(`unsupported protobuf wire type ${wireType} for field ${number}`);
    }
  }
}

function readVarint(u8, pos, end) {
  let value = 0n;
  let shift = 0n;
  for (;;) {
    if (pos >= end) throw new RangeError("varint runs past the end of the message");
    const byte = u8[pos++];
    value |= BigInt(byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value, pos };
    shift += 7n;
    if (shift > 63n) throw new RangeError("varint longer than 64 bits");
  }
}

const text = (bytes) => decoder.decode(bytes);
const small = (big) => (big <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(big) : big.toString());

function parseDimension(bytes) {
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.number === 1 && f.wireType === 0) return small(BigInt.asIntN(64, f.varint));
    if (f.number === 2 && f.wireType === 2) return text(f.bytes);
  }
  return null;
}

function parseTensorType(bytes) {
  let elemType = null;
  let dims = null;
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.number === 1 && f.wireType === 0) elemType = Number(f.varint);
    if (f.number === 2 && f.wireType === 2) {
      dims = [];
      for (const d of fields(f.bytes, 0, f.bytes.length)) {
        if (d.number === 1 && d.wireType === 2) dims.push(parseDimension(d.bytes));
      }
    }
  }
  return { elemType: ELEM_TYPES.get(elemType) ?? (elemType === null ? null : `UNKNOWN(${elemType})`), dims };
}

function parseValueInfo(bytes) {
  const info = { name: "", elemType: null, dims: null };
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.number === 1 && f.wireType === 2) info.name = text(f.bytes);
    if (f.number === 2 && f.wireType === 2) {
      for (const t of fields(f.bytes, 0, f.bytes.length)) {
        if (t.number === 1 && t.wireType === 2) Object.assign(info, parseTensorType(t.bytes));
      }
    }
  }
  return info;
}

function parseNodeOpType(bytes) {
  let opType = "";
  let domain = "";
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.number === 4 && f.wireType === 2) opType = text(f.bytes);
    if (f.number === 7 && f.wireType === 2) domain = text(f.bytes);
  }
  return domain ? `${domain}.${opType}` : opType;
}

function parseGraph(bytes) {
  const graph = { name: "", inputs: [], outputs: [], nodeCount: 0, initializerCount: 0, opTypes: {} };
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.wireType !== 2) continue;
    switch (f.number) {
      case 1: {
        graph.nodeCount++;
        const op = parseNodeOpType(f.bytes);
        graph.opTypes[op] = (graph.opTypes[op] ?? 0) + 1;
        break;
      }
      case 2: graph.name = text(f.bytes); break;
      case 5: graph.initializerCount++; break;
      case 11: graph.inputs.push(parseValueInfo(f.bytes)); break;
      case 12: graph.outputs.push(parseValueInfo(f.bytes)); break;
      default: break;
    }
  }
  // Initializers also appear in `input` in older exporters; the declared interface is the inputs
  // that are not initializers, but without names of initializers (skipped above) the heuristic
  // would need a second pass. Paradee and every opset>=17 exporter list only real inputs.
  graph.opTypes = Object.fromEntries(Object.entries(graph.opTypes).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])));
  return graph;
}

/** Parses a serialized `ModelProto`. */
export function parseOnnxModel(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const model = { irVersion: null, producerName: "", producerVersion: "", domain: "", opsetImports: [], graph: null };
  for (const f of fields(u8, 0, u8.length)) {
    switch (f.number) {
      case 1: if (f.wireType === 0) model.irVersion = Number(f.varint); break;
      case 2: if (f.wireType === 2) model.producerName = text(f.bytes); break;
      case 3: if (f.wireType === 2) model.producerVersion = text(f.bytes); break;
      case 4: if (f.wireType === 2) model.domain = text(f.bytes); break;
      case 7: if (f.wireType === 2) model.graph = parseGraph(f.bytes); break;
      case 8: {
        if (f.wireType !== 2) break;
        const opset = { domain: "", version: null };
        for (const o of fields(f.bytes, 0, f.bytes.length)) {
          if (o.number === 1 && o.wireType === 2) opset.domain = text(o.bytes);
          if (o.number === 2 && o.wireType === 0) opset.version = Number(o.varint);
        }
        model.opsetImports.push(opset);
        break;
      }
      default: break;
    }
  }
  if (!model.graph) throw new Error("not an ONNX model: no graph");
  return model;
}

/** The flat record the modality gate stores: what the artifact declares, nothing inferred. */
export function summarizeOnnx(model) {
  const opset = {};
  for (const o of model.opsetImports) opset[o.domain || "ai.onnx"] = o.version;
  return {
    irVersion: model.irVersion,
    producer: [model.producerName, model.producerVersion].filter(Boolean).join(" "),
    opset,
    inputs: model.graph.inputs,
    outputs: model.graph.outputs,
    nodeCount: model.graph.nodeCount,
    initializerCount: model.graph.initializerCount,
    operatorTypes: Object.keys(model.graph.opTypes).length,
    opTypes: model.graph.opTypes,
  };
}

/** Downloads and summarizes an ONNX artifact. The whole file is read; see the module comment. */
export async function inspectOnnx(uri, { fetch: fetchImpl = globalThis.fetch } = {}) {
  if (typeof uri !== "string" || uri.length === 0) throw new TypeError("uri must be a non-empty string");
  const response = await fetchImpl(uri, { redirect: "follow" });
  if (!response.ok) throw new Error(`HTTP ${response.status} fetching ${uri}`);
  return summarizeOnnx(parseOnnxModel(new Uint8Array(await response.arrayBuffer())));
}

/** Summarizes a local ONNX file. */
export function inspectOnnxFile(path) {
  return summarizeOnnx(parseOnnxModel(new Uint8Array(readFileSync(path))));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const target = process.argv[2];
  if (!target) {
    console.error("usage: onnx-inspector.mjs <file-or-url>");
    process.exit(2);
  }
  const summary = /^https?:/.test(target) ? await inspectOnnx(target) : inspectOnnxFile(target);
  console.log(JSON.stringify(summary, null, 1));
}
