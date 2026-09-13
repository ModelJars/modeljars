import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { validateComponentEvidence } from "./component-evidence-gate.mjs";

const modelsRevision = "a".repeat(40);
const evidenceRevision = "b".repeat(40);
const files = [
  {
    path: "models-activated-lora.json",
    role: "adapter-configuration",
    sha256: "d".repeat(64),
    sizeBytes: 4_096,
  },
  {
    path: "adapter_model.safetensors",
    role: "adapter-weights",
    sha256: "c".repeat(64),
    sizeBytes: 42_000_000,
  },
  {
    path: "NOTICE",
    role: "attribution-notice",
    sha256: "e".repeat(64),
    sizeBytes: 2_048,
  },
  {
    path: "LICENSE",
    role: "license",
    sha256: "f".repeat(64),
    sizeBytes: 11_344,
  },
];
const bundleSizeBytes = files.reduce((total, file) => total + file.sizeBytes, 0);
const bundleIdentity = [...files]
  .sort((left, right) => (left.path < right.path ? -1 : left.path > right.path ? 1 : 0))
  .map((file) => `${file.path}\t${file.sizeBytes}\t${file.sha256}\n`)
  .join("");
const bundleSha256 = createHash("sha256")
  .update(bundleIdentity, "utf8")
  .digest("hex");
const models = {
  schemaVersion: 2,
  models: [
    {
      id: "base",
      markerCoordinate: "org.modeljars.huggingface:base:1.0.0",
      revision: "9".repeat(40),
      sha256: "b".repeat(64),
      sizeBytes: 1_700_000_000,
      capabilities: ["chat", "generation", "tool-calling"],
    },
    {
      id: "adapter",
      markerCoordinate: "org.modeljars.huggingface:adapter:1.0.0-alora.1",
      sha256: "c".repeat(64),
      sizeBytes: 42_000_000,
      capabilities: ["composition-component"],
      features: ["multi-file-artifact", "activated-lora-adapter"],
      files,
    },
  ],
};

function report(overrides = {}) {
  return {
    schemaVersion: 1,
    implementation: {
      runtime: "java",
      publicApiExercised: true,
      externalInference: false,
      modelsRevision,
    },
    evaluation: {
      qualified: true,
      artifact: {
        modelId: "adapter",
        sha256: "c".repeat(64),
        sizeBytes: 42_000_000,
        files,
        bundleSizeBytes,
        bundleSha256,
      },
      base: {
        modelId: "base",
        revision: "9".repeat(40),
        sha256: "b".repeat(64),
      },
      gates: {
        provenance: {
          pass: true,
          frozenEvaluation: true,
          selectionManifestSha256: "e".repeat(64),
          trainingManifestSha256: "f".repeat(64),
          formatterSha256: "1".repeat(64),
          trainerSha256: "2".repeat(64),
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
        toolResultLoop: {
          pass: true,
          secondSelectionCompleted: true,
          repeatedToolCall: false,
        },
      },
    },
    ...overrides,
  };
}

function qualificationDocument(value, overrides = {}) {
  const bytes = Buffer.from(JSON.stringify(value));
  return {
    bytes,
    document: {
      schemaVersion: 1,
      generatedAt: "2026-09-13T13:00:00Z",
      policyVersion: "activated-adapter-component-v1",
      modelsRevision,
      evidenceRevision,
      qualifiedModels: 1,
      rejectedModels: 0,
      entries: [
        {
          modelId: "adapter",
          baseModelId: "base",
          artifactSha256: "c".repeat(64),
          artifactSizeBytes: 42_000_000,
          artifactFiles: files,
          artifactBundleSizeBytes: bundleSizeBytes,
          artifactBundleSha256: bundleSha256,
          qualified: true,
          unresolvedRequiredWork: [],
          reportUri:
            `https://raw.githubusercontent.com/integrallis/models/${evidenceRevision}/` +
            "benchmark-results/alora/component-qualification.json",
          reportSha256: createHash("sha256").update(bytes).digest("hex"),
          ...overrides,
        },
      ],
    },
  };
}

test("accepts a component bound to frozen correctness and real Java adapters", async () => {
  const { bytes, document } = qualificationDocument(report());
  assert.deepEqual(
    await validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    ["adapter"],
  );
});

test("rejects a normal public model in the component channel", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    modelId: "base",
    artifactSha256: "b".repeat(64),
    artifactSizeBytes: 1_700_000_000,
  });
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /composition-component/,
  );
});

test("rejects stale bytes or an incomplete artifact bundle", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    artifactFiles: [files[1]],
  });
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /complete runtime file list/,
  );
});

test("rejects an adapter bundle without runtime metadata, notice, and license", async () => {
  const incompleteFiles = files.filter((file) => file.path !== "LICENSE");
  const incompleteModel = {
    ...models,
    models: models.models.map((model) =>
      model.id === "adapter" ? { ...model, files: incompleteFiles } : model,
    ),
  };
  const { bytes, document } = qualificationDocument(report(), {
    artifactFiles: incompleteFiles,
    artifactBundleSizeBytes: incompleteFiles.reduce(
      (total, file) => total + file.sizeBytes,
      0,
    ),
    artifactBundleSha256: createHash("sha256")
      .update(
        incompleteFiles
          .sort((left, right) =>
            left.path < right.path ? -1 : left.path > right.path ? 1 : 0,
          )
          .map((file) => `${file.path}\t${file.sizeBytes}\t${file.sha256}\n`)
          .join(""),
        "utf8",
      )
      .digest("hex"),
  });
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models: incompleteModel,
      loadReport: async () => bytes,
    }),
    /runtime metadata, attribution notice, and license/,
  );
});

test("rejects mutable evidence and unresolved work", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    unresolvedRequiredWork: ["Run real Spring AI integration"],
    reportUri: "https://github.com/integrallis/models/blob/main/report.json",
  });
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /unresolved required work/,
  );
});

test("rejects non-Java or synthetic framework evidence", async () => {
  const good = report();
  const invalid = report({
    implementation: {
      runtime: "python",
      publicApiExercised: false,
      externalInference: true,
    },
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        springAi: { pass: true, realWeights: false, toolInvocations: 1 },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /Java production runtime/,
  );
});

test("rejects correctness below the fixed thresholds", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        taskCorrectness: {
          ...good.evaluation.gates.taskCorrectness,
          selectionExactRate: 0.84,
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /task-correctness thresholds/,
  );
});

test("rejects incomplete training-pipeline provenance", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        provenance: {
          ...good.evaluation.gates.provenance,
          trainerSha256: undefined,
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /formatter and trainer/,
  );
});

test("rejects a report that does not match the catalog and qualification identity", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      artifact: { ...good.evaluation.artifact, sha256: "0".repeat(64) },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /report artifact identity/,
  );
});

test("rejects evidence that is not pinned to the declared evidence revision", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    reportUri:
      `https://raw.githubusercontent.com/integrallis/models/${"c".repeat(40)}/` +
      "benchmark-results/alora/component-qualification.json",
  });
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /declared evidence revision/,
  );
});

test("rejects a report produced from a different Models revision", async () => {
  const invalid = report({
    implementation: {
      runtime: "java",
      publicApiExercised: true,
      externalInference: false,
      modelsRevision: "c".repeat(40),
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validateComponentEvidence({
      qualifications: document,
      models,
      loadReport: async () => bytes,
    }),
    /report Models revision does not match/,
  );
});
