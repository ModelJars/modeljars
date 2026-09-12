import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { validateCompositionEvidence } from "./composition-evidence-gate.mjs";

const revision = "a".repeat(40);

function report(overrides = {}) {
  return {
    schemaVersion: 2,
    implementation: {
      runtime: "java",
      stateHandoffMechanism: "cross-model-kv-translation",
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
          coordinate: "org.modeljars.huggingface:source:1.0.0",
          sha256: "b".repeat(64),
          resolvedFromCentral: true,
          runViaPublicApi: true,
        },
        {
          coordinate: "org.modeljars.huggingface:target:1.0.0",
          sha256: "c".repeat(64),
          resolvedFromCentral: true,
          runViaPublicApi: true,
        },
      ],
      gates: {
        taskCorrectness: { pass: true, attempts: 36 },
        longContextRetrieval: { pass: true, nativeCorrect: 8, retainedNativeCorrect: 8 },
        performanceCrossover: { pass: true, contextTokens: 4096 },
        memoryAccounting: { pass: true, peakRssBytes: 3_348_934_656 },
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
    loadReport: async () => bytes,
  });
  assert.deepEqual(checked, ["qwen3_chat_tools_composite"]);
});

test("rejects a qualified composition with unresolved required work", async () => {
  const { bytes, document } = documentFor(report(), {
    unresolvedRequiredWork: ["Evaluate predictive cross-model KV transfer"],
  });
  await assert.rejects(
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
    /unresolved required work/,
  );
});

test("rejects a mutable or human-facing evidence URL", async () => {
  const { bytes, document } = documentFor(report(), {
    reportUri:
      "https://github.com/integrallis/models/blob/main/benchmark-results/qwen/comparison.json",
  });
  await assert.rejects(
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
    /immutable raw GitHub URL/,
  );
});

test("rejects missing or changed evidence bytes", async () => {
  const { document } = documentFor(report());
  await assert.rejects(
    validateCompositionEvidence({
      compositions: document,
      loadReport: async () => Buffer.from("not the recorded report"),
    }),
    /SHA-256 does not match/,
  );
});

test("rejects catalog metrics that differ from the evidence report", async () => {
  const { bytes, document } = documentFor(report(), { compositeMedianMillis: 12_345 });
  await assert.rejects(
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
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
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
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
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
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
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
    /longContextRetrieval gate must pass/,
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
    validateCompositionEvidence({ compositions: document, loadReport: async () => bytes }),
    /published artifacts must be resolved from Central and run through the public API/,
  );
});
