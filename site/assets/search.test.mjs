import assert from "node:assert/strict";
import test from "node:test";

import { matches } from "./search.js";

const model = {
  name: "Nexus Medical",
  description: "A compact model for clinical question answering.",
  sourceId: "hf://example/nexus-medical",
  markerCoordinate: "org.modeljars.huggingface:example.nexus-medical.q4_k_m:1.0.0-q4_k_m.1",
  architecture: "qwen2",
  format: "gguf",
  quantization: "Q4_K_M",
  capabilities: ["text-generation", "chat"],
  features: ["pinned-revision"],
  backends: { "llama.cpp": true, "pure-java": false },
  domains: ["healthcare"],
};

test("matches catalog domains and descriptions", () => {
  assert.equal(matches(model, "healthcare", ""), true);
  assert.equal(matches(model, "clinical question", ""), true);
  assert.equal(matches(model, "insurance", ""), false);
});

test("applies backend filters independently of text", () => {
  assert.equal(matches(model, "medical", "llama.cpp"), true);
  assert.equal(matches(model, "medical", "pure-java"), false);
});
