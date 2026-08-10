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
  backends: { "llama.cpp": true, "rust-ffm": true },
  domains: ["healthcare"],
  tags: ["clinical", "on-device"],
  dimensions: { parameterCount: 3_000_000_000 },
  ragQualifications: [
    {
      qualified: true,
      useCaseTier: "GUARDED_RAG",
      backend: "rust-ffm",
      performanceTier: "USABLE",
    },
  ],
};

test("matches catalog domains and descriptions", () => {
  assert.equal(matches(model, "healthcare", ""), true);
  assert.equal(matches(model, "clinical question", ""), true);
  assert.equal(matches(model, "insurance", ""), false);
});

test("applies backend filters independently of text", () => {
  assert.equal(matches(model, "medical", "rust-ffm"), true);
  assert.equal(matches(model, "medical", "pure-java"), false);
});

test("searches folksonomy tags and common discovery aliases", () => {
  assert.equal(matches(model, "clinical", ""), true);
  assert.equal(matches(model, "medical", ""), true);
  assert.equal(matches(model, "java", "pure-java"), false);
  assert.equal(matches({ ...model, domains: ["finance"] }, "fintech", ""), true);
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
      ragQualifications: [
        {
          qualified: true,
          useCaseTier: "GENERATIVE_RAG",
          backend: "pure-java",
          performanceTier: "USABLE",
        },
      ],
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

test("filters by evidence-backed RAG qualification", () => {
  assert.equal(
    filterCatalog([model], { qualification: "guarded-rag" }).length,
    1,
  );
  assert.equal(
    filterCatalog([model], { qualification: "generative-rag" }).length,
    0,
  );
});

const embedder = {
  name: "Qwen3-Embedding-0.6B GGUF Q8_0",
  description: "Text embedding model for retrieval.",
  sourceId: "hf://Qwen/Qwen3-Embedding-0.6B-GGUF",
  markerCoordinate: "org.modeljars.huggingface:qwen.qwen3-embedding-0.6b-gguf.q8_0:3.0.0-q8_0.1",
  architecture: "qwen3",
  format: "gguf",
  quantization: "Q8_0",
  capabilities: ["text-embedding", "semantic-search"],
  backends: { "pure-java": true },
  domains: ["embeddings", "retrieval"],
  tags: [],
  dimensions: { parameterCount: 600_000_000 },
  embeddingQualifications: [{ qualified: true, useCaseTier: "SEMANTIC_SEARCH" }],
};

test("matches a multi-word query against hyphenated metadata", () => {
  // The capability is "semantic-search"; a person types "semantic search".
  assert.equal(matches(embedder, "semantic search", ""), true);
  assert.equal(matches(embedder, "text embedding", ""), true);
});

test("requires every word of a multi-word query to match", () => {
  assert.equal(matches(model, "clinical question", ""), true);
  assert.equal(matches(model, "clinical insurance", ""), false);
});

test("finds embedding models through common vocabulary", () => {
  for (const query of ["embedding", "embeddings", "vector", "vectors", "vector search"]) {
    assert.equal(matches(embedder, query, ""), true, `expected "${query}" to match`);
  }
});

test("does not drag generative models into embedding vocabulary", () => {
  assert.equal(matches(model, "vector", ""), false);
  assert.equal(matches(model, "embeddings", ""), false);
});

test("filters embedding models by their semantic-search tier", () => {
  const catalog = [model, embedder];

  assert.deepEqual(
    filterCatalog(catalog, { qualification: "semantic-search" }).map((entry) => entry.name),
    [embedder.name],
  );
});
