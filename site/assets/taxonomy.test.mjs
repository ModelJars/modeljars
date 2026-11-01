import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { filterCatalog } from "./search.js";
import { MODALITY_CLAIMS } from "../../tools/modality-claim-gate.mjs";
import {
  buildFacets,
  catalogDomains,
  leadingDomains,
  modelTerms,
  relatedModels,
  sizeTier,
  kindProfile,
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

test("facets a reranker under its measured use-case tier", () => {
  const reranker = {
    ...embeddingModel,
    id: "minilm-reranker",
    capabilities: ["reranking", "text-ranking"],
    rerankingQualifications: [
      {
        qualified: true,
        useCaseTier: "SECOND_STAGE_RERANKING",
        backend: "pure-java",
        workload: "reranking-oracle-and-latency-v1",
        pairs: 6,
        maximumSameArtifactOracleLogitDelta: 0.036392,
      },
    ],
    embeddingQualifications: [],
  };

  const facets = buildFacets([reranker]);

  assert.deepEqual(
    facets.qualifications.map((facet) => facet.value),
    ["second-stage-reranking"],
  );
  assert.ok(modelTerms(reranker).includes("reranking-oracle-and-latency-v1"));
  assert.equal(verificationProfile(reranker).label, "Second-stage reranking");
});

test("offers the emitted reranking evidence tier in the production evidence filter", async () => {
  const facets = buildFacets([
    {
      ...embeddingModel,
      embeddingQualifications: [],
      rerankingQualifications: [
        {
          qualified: true,
          useCaseTier: "SECOND_STAGE_RERANKING",
          backend: "pure-java",
        },
      ],
    },
  ]);
  const [tier] = facets.qualifications;
  const index = await readFile(new URL("../index.html", import.meta.url), "utf8");

  assert.equal(tier.value, "second-stage-reranking");
  assert.match(
    index,
    new RegExp(`<option value="${tier.value}">Second-stage reranking</option>`),
  );
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

test("a hybrid carries its kind in its own pill, not in the verification label", () => {
  const hybrid = {
    kind: "hybrid",
    members: [{ role: "base" }, { role: "specialist" }],
    backends: { "pure-java": true },
  };
  assert.deepEqual(kindProfile(hybrid), { kind: "hybrid", label: "Hybrid" });
});

test("an unqualified hybrid is still labelled a hybrid", () => {
  // Regression: kind and verification were one badge, so a hybrid missing any check rendered as
  // "Cataloged" and stopped being identifiable as a hybrid at all.
  const partial = { kind: "hybrid", members: [{ role: "base" }] };
  assert.equal(kindProfile(partial).label, "Hybrid");
  assert.equal(verificationProfile(partial).label, "Cataloged");
});

test("a fully qualified hybrid reads Qualified, with the kind carried separately", () => {
  const qualified = {
    kind: "hybrid",
    members: [{ role: "base" }, { role: "specialist" }],
    backends: { "pure-java": true },
    ragQualifications: [{ qualified: true }],
  };
  assert.equal(kindProfile(qualified).label, "Hybrid");
  assert.equal(verificationProfile(qualified).level, "qualified");
});

test("an ordinary model has no kind pill", () => {
  assert.equal(kindProfile({ name: "Qwen3.5 4B" }), null);
});

/**
 * A DELIBERATE CATEGORY MUST CONTAIN EVERYTHING THAT BELONGS TO IT.
 *
 * MEASURED 2026-10-23: filtering modeljars.org by System One did not list laya_q8_0, the only
 * typed-decision model in the catalogue. The facet reads `domains`, the two compositions carry
 * domains: ["system-1"] by hand, and Laya's domain is "general" -- correctly, since it is a
 * general-purpose decision model. Hand-tagging each new one is a step somebody forgets, and the
 * domain is also packaged into the published marker, so tagging it would rewrite immutable bytes
 * for a presentation fix. The capability is the real discriminator, so the facet derives from it.
 */
test("every typed-decision artifact is discoverable as System One", async () => {
  const [models, compositions] = await Promise.all([
    readFile(new URL("../../catalog/models.json", import.meta.url), "utf8").then(JSON.parse),
    readFile(new URL("../../catalog/compositions.json", import.meta.url), "utf8").then(JSON.parse),
  ]);
  const catalog = [...models.models, ...compositions.compositions];
  const decisionArtifacts = catalog.filter((entry) =>
    (entry.capabilities || []).includes("typed-decision"),
  );
  assert.ok(decisionArtifacts.length > 0, "the catalogue must hold a typed-decision artifact");

  for (const entry of decisionArtifacts) {
    assert.ok(
      catalogDomains(entry).includes("system-1"),
      `${entry.id} is a typed-decision artifact but is not discoverable under System One`,
    );
  }

  // A facet button that shows a count and then returns a different number of rows is worse than
  // no button: the count comes from buildFacets and the rows from filterCatalog, so the two must
  // read the same predicate. They did not, which is the other half of the same defect.
  const facet = buildFacets(catalog).domains.find((entry) => entry.value === "system-1");
  assert.ok(facet, "the System One facet must exist");
  assert.equal(
    facet.count,
    filterCatalog(catalog, { domain: "system-1" }).length,
    "the System One count must equal the number of entries the filter returns",
  );
});

test("a featured domain outside the nine most common still leads the pill row", () => {
  const domains = [
    "general",
    "retrieval",
    "embeddings",
    "coding",
    "reasoning",
    "healthcare",
    "legal",
    "rag",
    "system-1",
  ].map((value, index) => ({ value, count: 70 - index * 7 }));
  const facets = { domains: [...domains, { value: "audio", count: 2 }] };

  const pills = leadingDomains(facets, { featured: ["audio", "absent"] });
  assert.equal(pills[0].value, "audio", "the featured domain leads the row");
  assert.equal(pills.length, 10, "nine by count plus the one featured domain");
  assert.ok(!pills.some((facet) => facet.value === "absent"), "a featured domain the catalogue lacks renders nothing");

  const repeated = leadingDomains(facets, { featured: ["general"] });
  assert.equal(repeated.filter((facet) => facet.value === "general").length, 1, "a featured domain already in the top nine is not shown twice");
  assert.equal(repeated.length, 9);
});

/**
 * A NON-TEXT MODALITY MUST BE REACHABLE FROM THE PILL ROW.
 *
 * MEASURED 2026-11-01: modeljars.org listed the two qualified Text-to-Speech entries, tagged them,
 * and offered no pill that filtered to them. Their domains (speech, audio, text-to-speech) each
 * count two; the row is the nine most common domains, and from a991a8f (2026-10-23) System One's
 * derived third entry pushed `audio` from ninth to tenth. Nothing touched the speech entries; the
 * regression was arithmetic. This test reads the gate's own list of non-text modality claims, so
 * the next modality (speech-to-text, image understanding, OCR) fails here on the day its first
 * entry lands, instead of shipping invisible.
 */
test("every non-text modality in the catalogue is reachable from the pill row", async () => {
  const models = await readFile(new URL("../../catalog/models.json", import.meta.url), "utf8").then(
    JSON.parse,
  );
  const catalog = models.models;
  const modalityCapabilities = new Set(MODALITY_CLAIMS.keys());
  const modal = catalog.filter((entry) =>
    (entry.capabilities || []).some((capability) => modalityCapabilities.has(capability)),
  );
  assert.ok(modal.length > 0, "the catalogue must hold a non-text-modality entry");

  const pills = new Set(leadingDomains(buildFacets(catalog)).map((facet) => facet.value));
  for (const entry of modal) {
    assert.ok(
      catalogDomains(entry).some((domain) => pills.has(domain)),
      `${entry.id} (${(entry.capabilities || []).join(", ")}) has no domain in the pill row: ${[...pills].join(", ")}`,
    );
  }
});
