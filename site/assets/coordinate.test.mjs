import assert from "node:assert/strict";
import test from "node:test";

import { describeCoordinate } from "./coordinate.js";

test("a HuggingFace marker becomes a badge plus the part that distinguishes it", () => {
  const d = describeCoordinate(
    "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1",
  );
  assert.equal(d.label, "HuggingFace");
  assert.equal(d.short, "ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1");
});

test("GitHub and composite markers get their own labels", () => {
  assert.equal(describeCoordinate("org.modeljars.github:a.b:1.0.0").label, "GitHub");
  assert.equal(describeCoordinate("org.modeljars.composite:harriet:0.1.48").label, "ModelJars");
});

test("the full coordinate always survives, because that is what gets copied", () => {
  const full = "org.modeljars.huggingface:qwen.qwen3-1.7b-gguf.q8_0:3.0.0-q8_0.2";
  assert.equal(describeCoordinate(full).full, full);
});

test("an unrecognised group is shown whole rather than guessed at", () => {
  const other = "com.example:thing:1.0.0";
  const d = describeCoordinate(other);
  assert.equal(d.label, null);
  assert.equal(d.short, other);
});

test("a malformed coordinate is shown whole and not split", () => {
  const d = describeCoordinate("not-a-coordinate");
  assert.equal(d.label, null);
  assert.equal(d.short, "not-a-coordinate");
  assert.equal(d.full, "not-a-coordinate");
});
