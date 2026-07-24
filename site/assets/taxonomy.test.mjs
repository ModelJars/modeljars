import assert from "node:assert/strict";
import test from "node:test";

import {
  buildFacets,
  modelTerms,
  relatedModels,
  sizeTier,
  verificationProfile,
} from "./taxonomy.js";

const qwenCoder = {
  id: "qwen_coder",
  name: "Qwen Coder 3B",
  description: "Compact code-completion model.",
  sourceId: "hf://Qwen/Qwen-Coder-GGUF",
  markerCoordinate: "org.modeljars.huggingface:qwen.coder:q4.1",
  architecture: "qwen2",
  format: "gguf",
  quantization: "Q4_K_M",
  license: "Apache-2.0",
  dimensions: { parameterCount: 3_000_000_000 },
  domains: ["coding"],
  capabilities: ["code-completion", "fim"],
  features: ["pinned-revision"],
  tags: ["developer-tools", "offline"],
  languages: ["en"],
  modalities: ["text"],
  backends: { "pure-java": true, "llama.cpp": true },
  sha256: "a".repeat(64),
  revision: "b".repeat(40),
  sizeBytes: 2_000_000_000,
  ragQualifications: [
    {
      qualified: true,
      attempts: 27,
      backend: "llama.cpp",
      performanceTier: "PRODUCTION_READY",
      verdict: "QUALIFIED",
      useCaseTier: "GENERATIVE_RAG",
    },
  ],
};

test("combines curated metadata and folksonomy tags into search terms", () => {
  const terms = modelTerms(qwenCoder);

  assert.ok(terms.includes("coding"));
  assert.ok(terms.includes("developer-tools"));
  assert.ok(terms.includes("pure-java"));
  assert.ok(terms.includes("small"));
  assert.ok(terms.includes("text"));
  assert.ok(terms.includes("generative_rag"));
});

test("classifies local resource tiers from parameter count", () => {
  assert.equal(sizeTier({ dimensions: { parameterCount: 600_000_000 } }), "tiny");
  assert.equal(sizeTier(qwenCoder), "small");
  assert.equal(sizeTier({ dimensions: { parameterCount: 8_000_000_000 } }), "medium");
  assert.equal(sizeTier({ dimensions: { parameterCount: 70_000_000_000 } }), "very-large");
});

test("builds stable facets with counts", () => {
  const facets = buildFacets([
    qwenCoder,
    {
      ...qwenCoder,
      id: "medical",
      architecture: "llama",
      domains: ["healthcare"],
      backends: { "llama.cpp": true },
    },
  ]);

  assert.deepEqual(facets.domains, [
    { value: "coding", count: 1 },
    { value: "healthcare", count: 1 },
  ]);
  assert.deepEqual(facets.backends, [
    { value: "llama.cpp", count: 2 },
    { value: "pure-java", count: 1 },
  ]);
  assert.deepEqual(facets.qualifications, [
    { value: "generative-rag", count: 2 },
  ]);
});

test("reports evidence without inventing a confidence score", () => {
  assert.deepEqual(verificationProfile(qwenCoder), {
    level: "qualified",
    label: "Generative RAG",
    checks: ["Pinned artifact", "Complete metadata", "27-request RAG qualification"],
  });
});

test("ranks related variants by family, architecture, and domain", () => {
  const related = relatedModels(
    qwenCoder,
    [
      qwenCoder,
      { ...qwenCoder, id: "same-family", quantization: "Q8_0" },
      { ...qwenCoder, id: "same-domain", sourceId: "hf://Other/Model", architecture: "llama" },
      {
        ...qwenCoder,
        id: "unrelated",
        sourceId: "hf://Other/Embedding",
        architecture: "bert",
        domains: ["retrieval"],
        capabilities: ["text-embedding"],
      },
    ],
    2,
  );

  assert.deepEqual(
    related.map((model) => model.id),
    ["same-family", "same-domain"],
  );
});
