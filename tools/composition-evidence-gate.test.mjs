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
