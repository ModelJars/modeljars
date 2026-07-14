import assert from "node:assert/strict";
import test from "node:test";

import { filterCatalog, matches } from "./search.js";

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
  tags: ["clinical", "on-device"],
  dimensions: { parameterCount: 3_000_000_000 },
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

test("searches folksonomy tags and common discovery aliases", () => {
  assert.equal(matches(model, "clinical", ""), true);
  assert.equal(matches(model, "medical", ""), true);
  assert.equal(matches(model, "java", "pure-java"), false);
});

test("combines category, backend, architecture, size, and sort filters", () => {
  const catalog = [
    model,
    {
      ...model,
      id: "coder",
      name: "Coder",
      domains: ["coding"],
      architecture: "qwen2",
      backends: { "pure-java": true, "llama.cpp": true },
      dimensions: { parameterCount: 600_000_000 },
      sizeBytes: 500_000_000,
    },
  ];

  const filtered = filterCatalog(catalog, {
    query: "code",
    domain: "coding",
    backend: "pure-java",
    architecture: "qwen2",
    size: "tiny",
    sort: "smallest",
  });

  assert.deepEqual(filtered.map((candidate) => candidate.id), ["coder"]);
});
