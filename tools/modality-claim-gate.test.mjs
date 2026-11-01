import assert from "node:assert/strict";
import test from "node:test";

import {
  MODALITY_CLAIMS, backing, claimsOf, declaredTasks, onnxDeclaredTasks, runGate,
} from "./modality-claim-gate.mjs";

test("a tower in the artifact backs an encoder-side claim", () => {
  const r = backing("image-understanding", { towers: { vision: 24 }, declaredTasks: [] });
  assert.ok(r.ok);
  assert.match(r.why, /vision tower/);
});

test("the artifact's own declared task backs a generative claim with no tower", () => {
  // soprano, the one genuinely qualified TTS model in the catalog, carries NO tower by any name
  // pattern -- the generator IS the model, so there is no separate namespace to find. A
  // tower-only rule would fail the honest entry, which is the false positive this gate exists
  // not to produce.
  const r = backing("text-to-speech", { towers: {}, declaredTasks: ["tts"] });
  assert.ok(r.ok, "a declared tts task must back a text-to-speech claim without a tower");
  assert.match(r.why, /declares task "tts"/);
});

test("neither tower nor declared task fails the claim, and says what was missing", () => {
  const r = backing("image-understanding", { towers: {}, declaredTasks: [] });
  assert.equal(r.ok, false);
  assert.match(r.why, /no vision\/projector tower/);
  assert.match(r.why, /no towers/);
  assert.match(r.why, /no declared tasks/);
});

test("a tower for the wrong modality does not back the claim", () => {
  // An audio tower is not evidence of image understanding. Getting this wrong would let any
  // multimodal file launder every modality claim at once.
  const r = backing("image-understanding", { towers: { audio: 12 }, declaredTasks: [] });
  assert.equal(r.ok, false);
});

test("reads tasks out of a model_spec blob and out of namespace prefixes", () => {
  assert.deepEqual(
    declaredTasks({ "audiocpp.model_spec.json": '{"tasks":["TTS"],"display_name":"Soprano"}' }),
    ["tts"], "task strings are matched case-insensitively");
  assert.deepEqual(declaredTasks({ "stt.frontend.type": "mel" }), ["stt"]);
  assert.deepEqual(declaredTasks({ "tts.sample_rate": 24000 }), ["tts"]);
});

test("an unparseable spec blob yields no tasks rather than a guess", () => {
  assert.deepEqual(declaredTasks({ "audiocpp.model_spec.json": "{not json" }), []);
  // and therefore fails the claim closed
  assert.equal(backing("text-to-speech", { towers: {}, declaredTasks: [] }).ok, false);
});

test("only governed capabilities are treated as claims", () => {
  assert.deepEqual(claimsOf({ capabilities: ["text-generation", "chat", "reasoning"] }), []);
  assert.deepEqual(
    claimsOf({ capabilities: ["chat", "text-to-speech", "image-understanding"] }),
    ["image-understanding", "text-to-speech"]);
  assert.ok(MODALITY_CLAIMS.has("speech-to-text"), "stt must be governed before any stt lands");
});

test("a format the gate cannot read is UNVERIFIABLE, which is not a pass", () => {
  const findings = runGate(
    [{ id: "x", format: "safetensors", capabilities: ["text-to-speech"] }],
    () => null);
  assert.equal(findings[0].status, "UNVERIFIABLE");
  assert.notEqual(findings[0].status, "BACKED");
});

test("an ONNX entry without a record fails for want of evidence, not as unverifiable", () => {
  const findings = runGate(
    [{ id: "x", format: "onnx", sha256: "a".repeat(64), capabilities: ["text-to-speech"] }],
    () => null);
  assert.equal(findings[0].status, "NO_EVIDENCE");
});

const paradeeInterface = {
  inputs: [
    { name: "input_ids", elemType: "INT64", dims: [1, "T"] },
    { name: "speed", elemType: "FLOAT", dims: [1] },
    { name: "voice", elemType: "INT64", dims: [1] },
  ],
  outputs: [{ name: "waveform", elemType: "FLOAT", dims: ["n", "samples"] }],
};

test("token ids in, a float waveform out and a declared sample rate declare tts", () => {
  const declared = onnxDeclaredTasks(paradeeInterface, { sample_rate: 24000 });
  assert.deepEqual(declared.tasks, ["tts"]);
  assert.deepEqual(declared.evidence.tts, { input: "input_ids", output: "waveform", sampleRate: 24000 });
});

test("the same graph without a sample rate, or with a non-audio output, declares nothing", () => {
  assert.deepEqual(onnxDeclaredTasks(paradeeInterface, {}).tasks, []);
  assert.deepEqual(onnxDeclaredTasks(paradeeInterface, { sample_rate: "24000" }).tasks, ["tts"]);
  assert.deepEqual(onnxDeclaredTasks({
    inputs: paradeeInterface.inputs,
    outputs: [{ name: "logits", elemType: "FLOAT", dims: [1, "T", 178] }],
  }, { sample_rate: 24000 }).tasks, []);
  assert.deepEqual(onnxDeclaredTasks({
    inputs: [{ name: "mel", elemType: "FLOAT", dims: [1, 80, "T"] }],
    outputs: paradeeInterface.outputs,
  }, { sample_rate: 24000 }).tasks, []);
});

test("an ONNX record's declared task backs the speech claims and not a vision one", () => {
  const record = { id: "x", sha256: "a".repeat(64), format: "onnx", towers: {}, declaredTasks: ["tts"] };
  const findings = runGate(
    [{ id: "x", format: "onnx", sha256: "a".repeat(64),
       capabilities: ["text-to-speech", "audio-generation", "image-understanding"] }],
    () => record);
  assert.equal(findings[0].status, "UNBACKED_CLAIM");
  assert.deepEqual(findings[0].backed.map((b) => b.claim), ["audio-generation", "text-to-speech"]);
  assert.deepEqual(findings[0].unbacked.map((u) => u.claim), ["image-understanding"]);
});

test("a missing record fails rather than passing silently", () => {
  const findings = runGate(
    [{ id: "x", format: "gguf", sha256: "a".repeat(64), capabilities: ["image-understanding"] }],
    () => null);
  assert.equal(findings[0].status, "NO_EVIDENCE");
});

test("evidence recorded for a different artifact is refused", () => {
  // A swapped download must not inherit the old artifact's evidence.
  const findings = runGate(
    [{ id: "x", format: "gguf", sha256: "b".repeat(64), capabilities: ["image-understanding"] }],
    () => ({ id: "x", sha256: "a".repeat(64), towers: { vision: 10 }, declaredTasks: [] }));
  assert.equal(findings[0].status, "STALE_EVIDENCE");
  assert.match(findings[0].detail, /but the catalog now pins/);
});

test("an entry with no modality claim is not reported at all", () => {
  const findings = runGate(
    [{ id: "plain", format: "gguf", capabilities: ["text-generation", "chat"] }],
    () => null);
  assert.equal(findings.length, 0);
});

test("partially backed claims are reported as unbacked, not as backed", () => {
  const findings = runGate(
    [{ id: "x", format: "gguf", sha256: "a".repeat(64),
       capabilities: ["text-to-speech", "image-understanding"] }],
    () => ({ id: "x", sha256: "a".repeat(64), towers: {}, declaredTasks: ["tts"] }));
  assert.equal(findings[0].status, "UNBACKED_CLAIM");
  assert.deepEqual(findings[0].backed.map((b) => b.claim), ["text-to-speech"]);
  assert.deepEqual(findings[0].unbacked.map((u) => u.claim), ["image-understanding"]);
});
