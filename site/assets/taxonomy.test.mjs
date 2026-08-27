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
      backend: "pure-java",
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
      ragQualifications: [
        {
          ...qwenCoder.ragQualifications[0],
          backend: "rust-ffm",
        },
      ],
    },
  ]);

  assert.deepEqual(facets.domains, [
    { value: "coding", count: 1 },
    { value: "healthcare", count: 1 },
  ]);
  assert.deepEqual(facets.backends, [
    { value: "pure-java", count: 1 },
    { value: "rust-ffm", count: 1 },
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

const embeddingModel = {
  id: "qwen_qwen3_embedding_0_6b_gguf_q8_0",
  name: "Qwen3-Embedding-0.6B GGUF Q8_0",
  sourceId: "hf://Qwen/Qwen3-Embedding-0.6B-GGUF",
  markerCoordinate: "org.modeljars.huggingface:qwen.qwen3-embedding-0.6b-gguf.q8_0:3.0.0-q8_0.1",
  architecture: "qwen3",
  format: "gguf",
  quantization: "Q8_0",
  license: "apache-2.0",
  sizeBytes: 639_150_592,
  sha256: "0".repeat(64),
  revision: "main",
  capabilities: ["text-embedding", "semantic-search"],
  domains: ["embeddings", "retrieval"],
  dimensions: { parameterCount: 600_000_000 },
  embeddingQualifications: [
    {
      qualified: true,
      useCaseTier: "SEMANTIC_SEARCH",
      backend: "pure-java",
      probes: 8,
      minimumOracleCosine: 0.9995014,
      oracleBackend: "llama.cpp",
      oracleVersion: "6ea215d17",
    },
  ],
};

test("facets an embedding model under its semantic-search tier", () => {
  const facets = buildFacets([embeddingModel]);

  assert.deepEqual(
    facets.qualifications.map((facet) => facet.value),
    ["semantic-search"],
  );
  assert.ok(facets.domains.some((facet) => facet.value === "embeddings"));
});

test("describes embedding evidence without borrowing RAG wording", () => {
  const profile = verificationProfile(embeddingModel);

  assert.equal(profile.level, "qualified");
  assert.equal(profile.label, "Semantic search");
  assert.ok(
    profile.checks.some((check) => check.includes("probe")),
    `expected a probe-based check, got ${JSON.stringify(profile.checks)}`,
  );
  assert.ok(
    !profile.checks.some((check) => check.includes("undefined")),
    `checks must not contain undefined: ${JSON.stringify(profile.checks)}`,
  );
});

test("surfaces embedding evidence in search terms", () => {
  const terms = modelTerms(embeddingModel);

  assert.ok(terms.includes("text-embedding"));
  assert.ok(terms.includes("semantic_search") || terms.includes("semantic-search"));
});

test("facets and describes tool-calling conformance evidence", () => {
  const toolModel = {
    ...qwenCoder,
    id: "needle2",
    format: "cact",
    architecture: "needle2",
    ragQualifications: [],
    toolQualifications: [
      {
        qualified: true,
        useCaseTier: "TOOL_CALLING",
        backend: "pure-java",
        verdict: "PASS",
        workload: "needle2-upstream-playground-v1",
        attempts: 13,
        structuredOutputRate: 1,
      },
    ],
  };

  assert.deepEqual(buildFacets([toolModel]).qualifications, [
    { value: "tool-calling", count: 1 },
  ]);
  assert.deepEqual(verificationProfile(toolModel), {
    level: "qualified",
    label: "Tool calling",
    checks: ["Pinned artifact", "Complete metadata", "13-case tool-calling qualification"],
  });
  assert.ok(modelTerms(toolModel).includes("needle2-upstream-playground-v1"));
});
