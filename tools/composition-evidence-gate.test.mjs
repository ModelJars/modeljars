import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { validateCompositionEvidence } from "./composition-evidence-gate.mjs";

const revision = "a".repeat(40);
const modelCatalog = {
  schemaVersion: 2,
  models: [
    {
      id: "source",
      markerCoordinate: "org.modeljars.huggingface:source:1.0.0",
      sha256: "b".repeat(64),
    },
    {
      id: "target",
      markerCoordinate: "org.modeljars.huggingface:target:1.0.0",
      sha256: "c".repeat(64),
    },
  ],
};

function report(overrides = {}) {
  return {
    schemaVersion: 2,
    implementation: {
      runtime: "java",
      stateHandoffMechanism: "exact-kv-block-sharing",
      publicApiExercised: true,
      externalInference: false,
    },
    evaluation: {
      controlMedianMillis: 53_166,
      hybridMedianMillis: 35_151,
      improvement: 0.3388443742241282,
      qualified: true,
      correctnessPassed: true,
      handoffCostIncluded: true,
      publishedArtifacts: [
        {
          modelId: "source",
          coordinate: "org.modeljars.huggingface:source:1.0.0",
          sha256: "b".repeat(64),
          resolvedFromCentral: true,
          runViaPublicApi: true,
        },
        {
          modelId: "target",
          coordinate: "org.modeljars.huggingface:target:1.0.0",
          sha256: "c".repeat(64),
          resolvedFromCentral: true,
          runViaPublicApi: true,
        },
      ],
      gates: {
        provenance: {
          pass: true,
          frozenEvaluation: true,
          selectionManifestSha256: "d".repeat(64),
          adapterManifestSha256: "e".repeat(64),
        },
        taskCorrectness: {
          pass: true,
          attempts: 300,
          syntaxValidityRate: 1,
          schemaValidityRate: 1,
          selectionExactRate: 0.9,
          baseSelectionExactRate: 0.86,
          irrelevanceAttempts: 100,
          falseToolCalls: 4,
          falseToolCallRate: 0.04,
        },
        plainJava: { pass: true, realWeights: true },
        springAi: { pass: true, realWeights: true, toolInvocations: 1 },
        langChain4j: { pass: true, realWeights: true, toolInvocations: 1 },
        toolResultLoop: { pass: true, secondSelectionCompleted: true, repeatedToolCall: false },
        conversationState: { pass: true, turns: 6, oneCacheLineage: true },
        longContextRetrieval: {
          pass: true,
          cases: 8,
          contextTokens: 4096,
          nativeCorrect: 8,
          retainedNativeCorrect: 8,
          tokenExactCases: 8,
          physicallySharedCases: 8,
        },
        performanceCrossover: {
          pass: true,
          contextTokens: 4096,
          improvement: 0.3388443742241282,
          physicallySharedAllTiers: true,
          recomputedIndependentAllTiers: true,
          tokenExactAllTiers: true,
        },
        memoryAccounting: {
          pass: true,
          complete: true,
          peakRssBytes: 3_348_934_656,
          sharedUniqueStateBytes: 700_000_000,
          recomputedUniqueStateBytes: 1_100_000_000,
        },
      },
    },
    ...overrides,
  };
}

function documentFor(value, overrides = {}) {
  const bytes = Buffer.from(JSON.stringify(value));
  return {
    bytes,
    document: {
      schemaVersion: 1,
      compositions: [
        {
          id: "qwen3_chat_tools_composite",
          members: [
            { role: "chat", modelId: "source" },
            { role: "tools", modelId: "target" },
          ],
          compositionQualifications: [
            {
              qualified: true,
              unresolvedRequiredWork: [],
              controlMedianMillis: 53_166,
              compositeMedianMillis: 35_151,
              latencyImprovement: 0.3388443742241282,
              reportUri:
                `https://raw.githubusercontent.com/integrallis/models/${revision}/` +
                "benchmark-results/qwen/comparison.json",
              reportSha256: createHash("sha256").update(bytes).digest("hex"),
              ...overrides,
            },
          ],
        },
      ],
    },
  };
}

test("accepts complete, immutable, reproducible composite evidence", async () => {
  const { bytes, document } = documentFor(report());
  const checked = await validateCompositionEvidence({
    compositions: document,
    models: modelCatalog,
    loadReport: async () => bytes,
  });
  assert.deepEqual(checked, ["qwen3_chat_tools_composite"]);
});

test("rejects a qualified composition with unresolved required work", async () => {
  const { bytes, document } = documentFor(report(), {
    unresolvedRequiredWork: ["Evaluate predictive cross-model KV transfer"],
  });
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /unresolved required work/,
  );
});

test("rejects a mutable or human-facing evidence URL", async () => {
  const { bytes, document } = documentFor(report(), {
    reportUri:
      "https://github.com/integrallis/models/blob/main/benchmark-results/qwen/comparison.json",
  });
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /immutable raw GitHub URL/,
  );
});

test("rejects missing or changed evidence bytes", async () => {
  const { document } = documentFor(report());
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => Buffer.from("not the recorded report"),
    }),
    /SHA-256 does not match/,
  );
});

test("rejects catalog metrics that differ from the evidence report", async () => {
  const { bytes, document } = documentFor(report(), { compositeMedianMillis: 12_345 });
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /metrics do not match/,
  );
});

test("rejects a report that did not pass correctness", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      correctnessPassed: false,
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /must pass correctness/,
  );
});

test("rejects semantic routing presented as state handoff", async () => {
  const badReport = report({
    implementation: {
      runtime: "java",
      stateHandoffMechanism: "semantic-history",
      publicApiExercised: true,
      externalInference: false,
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /implemented cache-state handoff/,
  );
});

test("rejects cache-translation labels until that handoff has an implemented production path", async () => {
  const badReport = report({
    implementation: {
      runtime: "java",
      stateHandoffMechanism: "cross-model-kv-translation",
      publicApiExercised: true,
      externalInference: false,
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /implemented cache-state handoff/,
  );
});

test("rejects a report without exact long-context retention", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        longContextRetrieval: {
          pass: false,
          nativeCorrect: 8,
          retainedNativeCorrect: 5,
        },
      },
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /longContextRetrieval gate must pass/,
  );
});

test("rejects nominal shared KV evidence without physical sharing and token equality", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        performanceCrossover: {
          ...good.evaluation.gates.performanceCrossover,
          physicallySharedAllTiers: false,
        },
      },
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /physical KV sharing and token equality/,
  );
});

test("rejects task qualification above the fixed false-call ceiling", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        taskCorrectness: {
          ...good.evaluation.gates.taskCorrectness,
          falseToolCalls: 6,
          falseToolCallRate: 0.06,
        },
      },
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /fixed task-correctness thresholds/,
  );
});

test("rejects a hybrid not exercised with real weights through each Java adapter", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        springAi: { pass: true, realWeights: false, toolInvocations: 1 },
      },
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /real-weight plain Java, Spring AI, and LangChain4j/,
  );
});

test("rejects a nominal memory pass that does not account for a sharing reduction", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        memoryAccounting: {
          ...good.evaluation.gates.memoryAccounting,
          sharedUniqueStateBytes: 1_100_000_000,
          recomputedUniqueStateBytes: 1_100_000_000,
        },
      },
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /memory accounting must prove fewer unique inference-state bytes/,
  );
});

test("rejects evidence that did not exercise Central artifacts through the public API", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      publishedArtifacts: [
        {
          ...good.evaluation.publishedArtifacts[0],
          resolvedFromCentral: false,
        },
        good.evaluation.publishedArtifacts[1],
      ],
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /published artifacts must be resolved from Central and run through the public API/,
  );
});

test("rejects a member artifact that differs from the physical catalog", async () => {
  const good = report();
  const badReport = report({
    evaluation: {
      ...good.evaluation,
      publishedArtifacts: [
        {
          ...good.evaluation.publishedArtifacts[0],
          coordinate: "org.modeljars.huggingface:substitute:1.0.0",
        },
        good.evaluation.publishedArtifacts[1],
      ],
    },
  });
  const { bytes, document } = documentFor(badReport);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /must match the physical model catalog/,
  );
});

function ragReport(overrides = {}) {
  const base = report();
  return {
    ...base,
    specialistKind: "upstream-rag-specialist",
    evaluation: {
      ...base.evaluation,
      gates: {
        provenance: {
          pass: true,
          upstream: true,
          upstreamRepository: "ibm-granite/granitelib-rag-r1.0",
          upstreamRevision: "2".repeat(40),
          adapterSha256: "3".repeat(64),
          adapterConfigSha256: "4".repeat(64),
        },
        taskCorrectness: {
          pass: true,
          windowSha256: "5".repeat(64),
          promptOracleIdentical: true,
          backend: "pure-java",
          suites: [
            {
              name: "mtrag-human-rag",
              cases: 110,
              structuredRate: 1,
              balancedAccuracy: 0.86,
              baseBalancedAccuracy: 0.6,
              physicallySharedCases: 110,
            },
            {
              name: "squad-v2-dev",
              cases: 200,
              structuredRate: 1,
              balancedAccuracy: 0.81,
              baseBalancedAccuracy: 0.55,
              physicallySharedCases: 200,
            },
          ],
        },
        plainJava: {
          pass: true,
          realWeights: true,
          conformanceOracle: "llama.cpp b9960",
          greedyOracles: 2,
          markerRoundTrip: true,
        },
        jvmMechanics: {
          pass: true,
          realWeights: true,
          physicalStorageIdentity: true,
          exactBaseContinuation: true,
          disabledAdapterNoOp: true,
          batchedPrefillIdentity: true,
        },
        longContextRetrieval: base.evaluation.gates.longContextRetrieval,
        performanceCrossover: base.evaluation.gates.performanceCrossover,
        memoryAccounting: base.evaluation.gates.memoryAccounting,
      },
    },
    ...overrides,
  };
}

function ragDocumentFor(value, overrides = {}) {
  const { bytes, document } = documentFor(value, overrides);
  document.compositions[0].specialistKind = "upstream-rag-specialist";
  return { bytes, document };
}

test("accepts an upstream RAG specialist composition on the fixed answerability gates", async () => {
  const { bytes, document } = ragDocumentFor(ragReport());
  assert.deepEqual(
    await validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    ["qwen3_chat_tools_composite"],
  );
});

test("rejects an upstream RAG composition whose window has one unstructured output", async () => {
  const good = ragReport();
  const suites = good.evaluation.gates.taskCorrectness.suites.map((suite, index) =>
    index === 1 ? { ...suite, structuredRate: 0.995 } : suite,
  );
  const bad = ragReport({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        taskCorrectness: { ...good.evaluation.gates.taskCorrectness, suites },
      },
    },
  });
  const { bytes, document } = ragDocumentFor(bad);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /fixed answerability window thresholds/,
  );
});

test("rejects an upstream RAG window on a kernel backend without proven identity", async () => {
  const good = ragReport();
  const bad = ragReport({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        taskCorrectness: { ...good.evaluation.gates.taskCorrectness, backend: "rust-ffm" },
      },
    },
  });
  const { bytes, document } = ragDocumentFor(bad);
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /without proven identity to pure Java/,
  );
});

test("rejects a report whose specialist kind differs from the catalog entry", async () => {
  const { bytes, document } = documentFor(ragReport());
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      models: modelCatalog,
      loadReport: async () => bytes,
    }),
    /specialistKind does not match/,
  );
  const unknown = ragDocumentFor(ragReport());
  unknown.document.compositions[0].specialistKind = "distilled-router";
  await assert.rejects(
    validateCompositionEvidence({
      compositions: unknown.document,
      models: modelCatalog,
      loadReport: async () => unknown.bytes,
    }),
    /unknown specialistKind/,
  );
});

const trainingRevision = "6".repeat(40);
const trainingManifestBytes = Buffer.from(
  JSON.stringify({
    adapterFiles: {
      "adapter_config.json": { sha256: "4".repeat(64) },
      "adapter_model.safetensors": { sha256: "3".repeat(64) },
    },
  }),
);
const preparedDataManifestBytes = Buffer.from(JSON.stringify({ salt: "fixture" }));

function pinned(path, bytes) {
  return {
    uri: `https://raw.githubusercontent.com/integrallis/models/${trainingRevision}/${path}`,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    sizeBytes: bytes.byteLength,
  };
}

const trainingManifest = pinned("results/training-manifest.json", trainingManifestBytes);
const preparedDataManifest = pinned("results/prepared-manifest.json", preparedDataManifestBytes);

function trainingEvidenceLoader(overrides = {}) {
  const served = new Map([
    [trainingManifest.uri, trainingManifestBytes],
    [preparedDataManifest.uri, preparedDataManifestBytes],
    ...Object.entries(overrides),
  ]);
  return async ({ evidence }) => {
    const bytes = served.get(evidence.uri);
    if (bytes === undefined) {
      throw new Error(`Unknown immutable training evidence ${evidence.uri}`);
    }
    return bytes;
  };
}

function firstPartyRagReport() {
  const base = ragReport();
  const gates = base.evaluation.gates;
  return {
    ...base,
    specialistKind: "first-party-rag-specialist",
    evaluation: {
      ...base.evaluation,
      gates: {
        ...gates,
        provenance: {
          pass: true,
          upstream: false,
          publisher: "Integrallis",
          trainingRepository: "integrallis/models",
          trainingRevision,
          trainingManifest,
          preparedDataManifest,
          adapterSha256: "3".repeat(64),
          adapterConfigSha256: "4".repeat(64),
          modelCardSha256: "7".repeat(64),
          license: "Apache-2.0",
          trainingDataLicenses: [{ dataset: "SQuAD 2.0", license: "CC-BY-SA-4.0" }],
          tokenizerFiles: [{ name: "tokenizer.json", sha256: "8".repeat(64) }],
        },
        taskCorrectness: {
          ...gates.taskCorrectness,
          suites: gates.taskCorrectness.suites.map((suite) => ({
            ...suite,
            labelSource: "confirmed",
            labelsSha256: "9".repeat(64),
            originalBalancedAccuracy: suite.balancedAccuracy - 0.01,
          })),
        },
      },
    },
  };
}

async function validateFirstPartyComposition(value, options = {}) {
  const { bytes, document } = documentFor(value);
  document.compositions[0].specialistKind = options.kind ?? "first-party-rag-specialist";
  return validateCompositionEvidence({
    compositions: document,
    models: modelCatalog,
    loadReport: async () => bytes,
    loadEvidenceFile:
      "loadEvidenceFile" in options ? options.loadEvidenceFile : trainingEvidenceLoader(),
  });
}

test("accepts a first-party RAG specialist composition bound to its training commit", async () => {
  assert.deepEqual(await validateFirstPartyComposition(firstPartyRagReport()), [
    "qwen3_chat_tools_composite",
  ]);
});

test("requires an evidence loader to verify a first-party composition", async () => {
  await assert.rejects(
    validateFirstPartyComposition(firstPartyRagReport(), { loadEvidenceFile: undefined }),
    /requires an evidence-file loader/,
  );
});

test("rejects a first-party composition that claims upstream provenance or another kind", async () => {
  const upstream = firstPartyRagReport();
  upstream.evaluation.gates.provenance.upstream = true;
  await assert.rejects(
    validateFirstPartyComposition(upstream),
    /provenance must pin the first-party training commit/,
  );
  await assert.rejects(
    validateFirstPartyComposition(ragReport()),
    /must declare first-party-rag-specialist/,
  );
  await assert.rejects(
    validateFirstPartyComposition(firstPartyRagReport(), { kind: "upstream-rag-specialist" }),
    /must declare upstream-rag-specialist/,
  );
  const unpinned = firstPartyRagReport();
  unpinned.evaluation.gates.provenance.trainingManifest = {
    ...trainingManifest,
    uri: trainingManifest.uri.replace(trainingRevision, "5".repeat(40)),
  };
  await assert.rejects(
    validateFirstPartyComposition(unpinned),
    /provenance must pin the first-party training commit/,
  );
});

test("rejects a first-party composition whose manifests or adapter do not verify", async () => {
  await assert.rejects(
    validateFirstPartyComposition(firstPartyRagReport(), {
      loadEvidenceFile: trainingEvidenceLoader({ [trainingManifest.uri]: Buffer.from("{}") }),
    }),
    /training manifest bytes do not match/,
  );
  await assert.rejects(
    validateFirstPartyComposition(firstPartyRagReport(), {
      loadEvidenceFile: trainingEvidenceLoader({ [preparedDataManifest.uri]: Buffer.from("x") }),
    }),
    /prepared-data manifest bytes do not match/,
  );
  const mismatched = firstPartyRagReport();
  mismatched.evaluation.gates.provenance.adapterSha256 = "d".repeat(64);
  await assert.rejects(
    validateFirstPartyComposition(mismatched),
    /adapter weights do not match the training manifest/,
  );
});

test("rejects a first-party composition that does not strictly beat base or lacks label identity", async () => {
  const tie = firstPartyRagReport();
  const suite = tie.evaluation.gates.taskCorrectness.suites[1];
  suite.baseBalancedAccuracy = suite.balancedAccuracy;
  await assert.rejects(validateFirstPartyComposition(tie), /strictly beat its base/);

  const unlabeled = firstPartyRagReport();
  delete unlabeled.evaluation.gates.taskCorrectness.suites[0].labelsSha256;
  await assert.rejects(validateFirstPartyComposition(unlabeled), /confirmed labels/);

  const sharing = firstPartyRagReport();
  sharing.evaluation.gates.memoryAccounting.sharedUniqueStateBytes = 2_000_000_000;
  await assert.rejects(validateFirstPartyComposition(sharing), /memory accounting/);
});
