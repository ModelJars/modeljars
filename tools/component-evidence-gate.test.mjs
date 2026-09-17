import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import { validateComponentEvidence } from "./component-evidence-gate.mjs";

const modelsRevision = "a".repeat(40);
const evidenceRevision = "b".repeat(40);
const modelsReleaseVersion = "0.3.38";
const requiredModelsModules = [
  "backend-java",
  "models-runtime",
  "models-spring-ai",
  "models-langchain4j",
];
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

function immutableArtifactFile(module, kind) {
  const bytes = Buffer.from(`${module}:${modelsReleaseVersion}:${kind}\n`);
  const extension = kind === "jar" ? "jar" : "pom";
  return {
    uri:
      `https://repo1.maven.org/maven2/com/integrallis/${module}/` +
      `${modelsReleaseVersion}/${module}-${modelsReleaseVersion}.${extension}`,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    sizeBytes: bytes.byteLength,
    bytes,
  };
}

const releasedModelsArtifacts = requiredModelsModules.map((module) => ({
  module,
  coordinate: `com.integrallis:${module}:${modelsReleaseVersion}`,
  jar: immutableArtifactFile(module, "jar"),
  pom: immutableArtifactFile(module, "pom"),
}));

function releasedModelsArtifactGate() {
  return {
    pass: true,
    version: modelsReleaseVersion,
    artifacts: releasedModelsArtifacts.map(({ module, coordinate, jar, pom }) => ({
      module,
      coordinate,
      jar: { ...jar, bytes: undefined },
      pom: { ...pom, bytes: undefined },
    })),
  };
}

async function releasedModelsArtifactLoader({ artifact, kind }) {
  const expected = releasedModelsArtifacts.find(
    (candidate) => candidate.module === artifact.module,
  )?.[kind];
  if (expected === undefined || artifact[kind].uri !== expected.uri) {
    throw new Error("Unknown immutable release artifact");
  }
  return expected.bytes;
}

const cleanHostOutputBytes = Buffer.from(
  "java=25\nmodels=0.3.38\nexternalInference=false\nexit=0\n",
);
const cleanHostOutput = {
  uri:
    `https://raw.githubusercontent.com/integrallis/models/${evidenceRevision}/` +
    "benchmark-results/alora/clean-host.log",
  sha256: createHash("sha256").update(cleanHostOutputBytes).digest("hex"),
  sizeBytes: cleanHostOutputBytes.byteLength,
};

function cleanHostRun() {
  return {
    pass: true,
    freshMachine: true,
    externalInference: false,
    javaMajor: 25,
    exitCode: 0,
    modelsVersion: modelsReleaseVersion,
    startedAt: "2026-09-13T13:15:00Z",
    completedAt: "2026-09-13T13:15:12Z",
    command: ["java", "--add-modules", "jdk.incubator.vector", "-jar", "smoke.jar"],
    resolvedClasspathSha256: "7".repeat(64),
    outputLog: { ...cleanHostOutput },
  };
}

async function evidenceFileLoader({ evidence }) {
  if (evidence.uri !== cleanHostOutput.uri) {
    throw new Error("Unknown immutable evidence file");
  }
  return cleanHostOutputBytes;
}

async function validate(document, bytes, options = {}) {
  return validateComponentEvidence({
    qualifications: document,
    models: options.models ?? models,
    loadReport: async () => bytes,
    loadReleasedArtifact:
      options.loadReleasedArtifact ?? releasedModelsArtifactLoader,
    loadEvidenceFile: options.loadEvidenceFile ?? evidenceFileLoader,
  });
}

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
        sizeBytes: 1_700_000_000,
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
        plainJava: {
          pass: true,
          realWeights: true,
          cases: 14,
          zipcodeRegression: true,
          argumentThreshold: true,
          abstention: true,
          sixTurnConversation: true,
        },
        springAi: {
          pass: true,
          realWeights: true,
          toolInvocations: 1,
          naturalLanguageResult: true,
          versions: ["1.1.4", "1.1.8", "2.0.0"],
        },
        langChain4j: {
          pass: true,
          realWeights: true,
          toolInvocations: 1,
          naturalLanguageResult: true,
          versions: ["1.0.0", "1.13.1", "1.17.2"],
        },
        toolResultLoop: {
          pass: true,
          secondSelectionCompleted: true,
          repeatedToolCall: false,
          naturalLanguageResult: true,
        },
        projectionOracle: {
          pass: true,
          comparedProjections: 28,
          maximumAbsoluteDelta: 0.00001,
          tolerance: 0.0001,
        },
        jvmMechanics: {
          pass: true,
          realWeights: true,
          physicalStorageIdentity: true,
          exactBaseContinuation: true,
          repeatedTurns: true,
          disabledAdapterNoOp: true,
        },
        longContext: {
          pass: true,
          cases: 8,
          prefixTokens: 4096,
          physicalStorageIdentity: true,
          nativeCorrectCases: 7,
          retainedNativeCorrectCases: 7,
          exactBaseOutput: true,
          correctTool: true,
        },
        performanceAndMemory: {
          pass: true,
          physicalSharing: true,
          tokenExactAtAllTiers: true,
          memoryComplete: true,
          crossoverPrefixTokens: 256,
          prefixTiers: [256, 1024, 4096],
          recomputedIndependence: true,
          jvmNativeMemoryAvailable: true,
          fourKImprovement: 0.25,
          peakRssBytes: 4_000_000_000,
        },
        modelsArtifact: {
          ...releasedModelsArtifactGate(),
        },
        cleanHostRun: cleanHostRun(),
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
          baseArtifactSha256: "b".repeat(64),
          baseArtifactSizeBytes: 1_700_000_000,
          artifactSha256: "c".repeat(64),
          artifactSizeBytes: 42_000_000,
          artifactFiles: files,
          artifactBundleSizeBytes: bundleSizeBytes,
          artifactBundleSha256: bundleSha256,
          minimumSharedPrefixTokens: 256,
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
    await validate(document, bytes),
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
    validate(document, bytes),
    /composition-component/,
  );
});

test("rejects stale bytes or an incomplete artifact bundle", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    artifactFiles: [files[1]],
  });
  await assert.rejects(
    validate(document, bytes),
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
    validate(document, bytes, { models: incompleteModel }),
    /runtime metadata, attribution notice, and license/,
  );
});

test("rejects mutable evidence and unresolved work", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    unresolvedRequiredWork: ["Run real Spring AI integration"],
    reportUri: "https://github.com/integrallis/models/blob/main/report.json",
  });
  await assert.rejects(
    validate(document, bytes),
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
    validate(document, bytes),
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
    validate(document, bytes),
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
    validate(document, bytes),
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
    validate(document, bytes),
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
    validate(document, bytes),
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
    validate(document, bytes),
    /report Models revision does not match/,
  );
});

test("rejects a crossover that is not bound to the performance evidence", async () => {
  const { bytes, document } = qualificationDocument(report(), {
    minimumSharedPrefixTokens: 1_024,
  });
  await assert.rejects(
    validate(document, bytes),
    /physical-sharing performance and memory evidence/,
  );
});

test("rejects missing long-context correctness counts", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        longContext: {
          ...good.evaluation.gates.longContext,
          nativeCorrectCases: undefined,
          retainedNativeCorrectCases: undefined,
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(validate(document, bytes), /fixed long-context gate/);
});

test("rejects zero long-context correctness counts", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        longContext: {
          ...good.evaluation.gates.longContext,
          nativeCorrectCases: 0,
          retainedNativeCorrectCases: 0,
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(validate(document, bytes), /fixed long-context gate/);
});

test("rejects a long-context result below the frozen six-case floor", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        longContext: {
          ...good.evaluation.gates.longContext,
          nativeCorrectCases: 5,
          retainedNativeCorrectCases: 5,
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(validate(document, bytes), /fixed long-context gate/);
});

test("rejects missing released Models artifacts", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        modelsArtifact: {
          ...good.evaluation.gates.modelsArtifact,
          artifacts: good.evaluation.gates.modelsArtifact.artifacts.slice(1),
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(validate(document, bytes), /immutable Maven Central Models artifacts/);
});

test("rejects SNAPSHOT or noncanonical released Models artifact evidence", async () => {
  const good = report();
  const snapshot = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        modelsArtifact: {
          ...good.evaluation.gates.modelsArtifact,
          version: "0.3.38-SNAPSHOT",
        },
      },
    },
  });
  const snapshotDocument = qualificationDocument(snapshot);
  await assert.rejects(
    validate(snapshotDocument.document, snapshotDocument.bytes),
    /immutable Maven Central Models artifacts/,
  );

  const wrongUri = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        modelsArtifact: {
          ...good.evaluation.gates.modelsArtifact,
          artifacts: good.evaluation.gates.modelsArtifact.artifacts.map((artifact) =>
            artifact.module === "backend-java"
              ? {
                  ...artifact,
                  jar: { ...artifact.jar, uri: "https://example.invalid/backend-java.jar" },
                }
              : artifact,
          ),
        },
      },
    },
  });
  const wrongUriDocument = qualificationDocument(wrongUri);
  await assert.rejects(
    validate(wrongUriDocument.document, wrongUriDocument.bytes),
    /immutable Maven Central backend-java jar evidence/,
  );
});

test("rejects released Models artifact byte hash or size mismatches", async () => {
  const good = report();
  const wrongHash = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        modelsArtifact: {
          ...good.evaluation.gates.modelsArtifact,
          artifacts: good.evaluation.gates.modelsArtifact.artifacts.map((artifact) =>
            artifact.module === "models-runtime"
              ? {
                  ...artifact,
                  jar: { ...artifact.jar, sha256: "0".repeat(64) },
                }
              : artifact,
          ),
        },
      },
    },
  });
  const wrongHashDocument = qualificationDocument(wrongHash);
  await assert.rejects(
    validate(wrongHashDocument.document, wrongHashDocument.bytes),
    /released models-runtime jar bytes do not match immutable evidence/,
  );

  const wrongSize = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        modelsArtifact: {
          ...good.evaluation.gates.modelsArtifact,
          artifacts: good.evaluation.gates.modelsArtifact.artifacts.map((artifact) =>
            artifact.module === "models-runtime"
              ? {
                  ...artifact,
                  jar: { ...artifact.jar, sizeBytes: artifact.jar.sizeBytes + 1 },
                }
              : artifact,
          ),
        },
      },
    },
  });
  const wrongSizeDocument = qualificationDocument(wrongSize);
  await assert.rejects(
    validate(wrongSizeDocument.document, wrongSizeDocument.bytes),
    /released models-runtime jar bytes do not match immutable evidence/,
  );
});

test("rejects an absent or inconsistent clean-host run", async () => {
  const good = report();
  const absent = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        cleanHostRun: undefined,
      },
    },
  });
  const absentDocument = qualificationDocument(absent);
  await assert.rejects(
    validate(absentDocument.document, absentDocument.bytes),
    /completed immutable clean-host Java 25 run/,
  );

  const inconsistent = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        cleanHostRun: {
          ...good.evaluation.gates.cleanHostRun,
          externalInference: true,
          completedAt: "2026-09-13T13:14:59Z",
        },
      },
    },
  });
  const inconsistentDocument = qualificationDocument(inconsistent);
  await assert.rejects(
    validate(inconsistentDocument.document, inconsistentDocument.bytes),
    /completed immutable clean-host Java 25 run/,
  );
});

test("accepts a clean-host log pinned to an earlier immutable Models commit", async () => {
  // The report embeds the log URI, so the log cannot be pinned to the commit that also holds the
  // report: that commit's hash would depend on its own contents. The log is immutable by its
  // 40-hex commit pin plus the recorded sha256 and size, which the gate byte-verifies.
  const good = report();
  const earlierRevision = "e".repeat(40);
  const earlierLog = {
    ...cleanHostOutput,
    uri:
      `https://raw.githubusercontent.com/integrallis/models/${earlierRevision}/` +
      "benchmark-results/alora/clean-host.log",
  };
  const earlier = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        cleanHostRun: { ...good.evaluation.gates.cleanHostRun, outputLog: earlierLog },
      },
    },
  });
  const { bytes, document } = qualificationDocument(earlier);
  const loader = async ({ evidence }) => {
    if (evidence.uri !== earlierLog.uri) {
      throw new Error("Unknown immutable evidence file");
    }
    return cleanHostOutputBytes;
  };
  assert.deepEqual(await validate(document, bytes, { loadEvidenceFile: loader }), ["adapter"]);
});

test("rejects a clean-host log that is not an immutable integrallis/models commit URL", async () => {
  const good = report();
  for (const uri of [
    "https://raw.githubusercontent.com/integrallis/models/main/benchmark-results/alora/clean-host.log",
    "https://raw.githubusercontent.com/someone/models/" + "e".repeat(40) + "/clean-host.log",
    "https://example.com/clean-host.log",
  ]) {
    const mutable = report({
      evaluation: {
        ...good.evaluation,
        gates: {
          ...good.evaluation.gates,
          cleanHostRun: {
            ...good.evaluation.gates.cleanHostRun,
            outputLog: { ...cleanHostOutput, uri },
          },
        },
      },
    });
    const { bytes, document } = qualificationDocument(mutable);
    await assert.rejects(validate(document, bytes), /completed immutable clean-host Java 25 run/);
  }
});

test("rejects a clean-host output hash mismatch", async () => {
  const good = report();
  const invalid = report({
    evaluation: {
      ...good.evaluation,
      gates: {
        ...good.evaluation.gates,
        cleanHostRun: {
          ...good.evaluation.gates.cleanHostRun,
          outputLog: {
            ...good.evaluation.gates.cleanHostRun.outputLog,
            sha256: "0".repeat(64),
          },
        },
      },
    },
  });
  const { bytes, document } = qualificationDocument(invalid);
  await assert.rejects(
    validate(document, bytes),
    /clean-host output bytes do not match immutable evidence/,
  );
});

function upstreamReport(overrides = {}) {
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
          upstreamRevision: "3".repeat(40),
          adapterSha256: "c".repeat(64),
          adapterConfigSha256: "5".repeat(64),
          modelCardSha256: "6".repeat(64),
          license: "Apache-2.0",
          tokenizerFiles: [{ name: "tokenizer.json", sha256: "8".repeat(64) }],
        },
        taskCorrectness: {
          pass: true,
          windowSha256: "9".repeat(64),
          promptOracleIdentical: true,
          backend: "rust-ffm",
          suites: [
            {
              name: "mtrag-human-rag",
              cases: 110,
              structuredRate: 1,
              balancedAccuracy: 0.88,
              baseBalancedAccuracy: 0.71,
              physicallySharedCases: 110,
            },
            {
              name: "squad-v2-dev",
              cases: 200,
              structuredRate: 1,
              balancedAccuracy: 0.84,
              baseBalancedAccuracy: 0.66,
              physicallySharedCases: 200,
            },
          ],
        },
        kernelIdentity: {
          pass: true,
          backend: "rust-ffm",
          casesPerSuitePerArm: 10,
          identicalOutputs: true,
        },
        plainJava: {
          pass: true,
          realWeights: true,
          conformanceOracle: "llama.cpp b9960-a935fbffe",
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
        longContext: {
          pass: true,
          cases: 8,
          prefixTokens: 4096,
          physicalStorageIdentity: true,
          nativeCorrectCases: 7,
          retainedNativeCorrectCases: 7,
          exactBaseOutput: true,
          specialistCorrectCases: 7,
        },
        performanceAndMemory: base.evaluation.gates.performanceAndMemory,
        modelsArtifact: base.evaluation.gates.modelsArtifact,
        cleanHostRun: base.evaluation.gates.cleanHostRun,
      },
    },
    ...overrides,
  };
}

function upstreamDocument(value) {
  const { bytes, document } = qualificationDocument(value);
  document.entries[0].specialistKind = "upstream-rag-specialist";
  return { bytes, document };
}

test("accepts an upstream RAG specialist bound to a frozen window and kernel identity", async () => {
  const { bytes, document } = upstreamDocument(upstreamReport());
  await validate(document, bytes);
});

test("rejects an upstream specialist whose report claims the trained-tool shape", async () => {
  const { bytes, document } = upstreamDocument(report());
  await assert.rejects(validate(document, bytes), /must declare upstream-rag-specialist/);
});

test("rejects an upstream specialist below the balanced-accuracy floor or without sharing", async () => {
  const low = upstreamReport();
  low.evaluation.gates.taskCorrectness.suites[1].balancedAccuracy = 0.79;
  const lowDocument = upstreamDocument(low);
  await assert.rejects(validate(lowDocument.document, lowDocument.bytes), /answerability window/);

  const worseThanBase = upstreamReport();
  worseThanBase.evaluation.gates.taskCorrectness.suites[0].baseBalancedAccuracy = 0.9;
  const worseDocument = upstreamDocument(worseThanBase);
  await assert.rejects(validate(worseDocument.document, worseDocument.bytes), /answerability window/);

  const unshared = upstreamReport();
  unshared.evaluation.gates.taskCorrectness.suites[0].physicallySharedCases = 109;
  const unsharedDocument = upstreamDocument(unshared);
  await assert.rejects(validate(unsharedDocument.document, unsharedDocument.bytes), /answerability window/);
});

test("rejects a kernel-backed window without proven identity to pure Java", async () => {
  const missing = upstreamReport();
  delete missing.evaluation.gates.kernelIdentity;
  const missingDocument = upstreamDocument(missing);
  await assert.rejects(validate(missingDocument.document, missingDocument.bytes), /without proven identity/);

  const tooFew = upstreamReport();
  tooFew.evaluation.gates.kernelIdentity.casesPerSuitePerArm = 9;
  const fewDocument = upstreamDocument(tooFew);
  await assert.rejects(validate(fewDocument.document, fewDocument.bytes), /without proven identity/);

  const pureJava = upstreamReport();
  pureJava.evaluation.gates.taskCorrectness.backend = "pure-java";
  delete pureJava.evaluation.gates.kernelIdentity;
  const pureDocument = upstreamDocument(pureJava);
  await validate(pureDocument.document, pureDocument.bytes);
});

test("rejects an unknown specialist kind and a mismatched trained report", async () => {
  const { bytes, document } = qualificationDocument(report());
  document.entries[0].specialistKind = "distilled-router";
  await assert.rejects(validate(document, bytes), /unknown specialistKind/);

  const mismatched = qualificationDocument({ ...report(), specialistKind: "upstream-rag-specialist" });
  await assert.rejects(validate(mismatched.document, mismatched.bytes), /does not match the catalog entry/);
});


const trainingRevision = "4".repeat(40);
const trainingManifestBytes = Buffer.from(
  JSON.stringify({
    base: "ibm-granite/granite-4.1-3b",
    adapterFiles: {
      "adapter_config.json": { bytes: 1_217, sha256: "5".repeat(64) },
      "adapter_model.safetensors": { bytes: 42_000_000, sha256: "c".repeat(64) },
    },
  }),
);
const preparedDataManifestBytes = Buffer.from(
  JSON.stringify({ salt: "fixture", train: { records: 24_000, sha256: "a".repeat(64) } }),
);

function pinnedTrainingFile(path, bytes, revision = trainingRevision) {
  return {
    uri: `https://raw.githubusercontent.com/integrallis/models/${revision}/${path}`,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    sizeBytes: bytes.byteLength,
  };
}

const trainingManifestPath = "benchmark-results/answerability-alora/training-manifest.json";
const preparedDataManifestPath = "benchmark-results/answerability-alora/prepared-manifest.json";

function firstPartyEvidenceLoader(overrides = {}) {
  const served = new Map([
    [pinnedTrainingFile(trainingManifestPath, trainingManifestBytes).uri, trainingManifestBytes],
    [
      pinnedTrainingFile(preparedDataManifestPath, preparedDataManifestBytes).uri,
      preparedDataManifestBytes,
    ],
    ...Object.entries(overrides),
  ]);
  return async ({ evidence, kind }) => {
    if (kind === "clean-host-output") {
      return evidenceFileLoader({ evidence });
    }
    const bytes = served.get(evidence.uri);
    if (bytes === undefined) {
      throw new Error(`Unknown immutable training evidence ${evidence.uri}`);
    }
    return bytes;
  };
}

function firstPartyReport() {
  const base = upstreamReport();
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
          trainingManifest: pinnedTrainingFile(trainingManifestPath, trainingManifestBytes),
          preparedDataManifest: pinnedTrainingFile(
            preparedDataManifestPath,
            preparedDataManifestBytes,
          ),
          adapterSha256: "c".repeat(64),
          adapterConfigSha256: "5".repeat(64),
          modelCardSha256: "6".repeat(64),
          license: "Apache-2.0",
          trainingDataLicenses: [
            { dataset: "SQuAD 2.0", license: "CC-BY-SA-4.0" },
            { dataset: "QuAC", license: "CC-BY-SA-4.0" },
          ],
          tokenizerFiles: [{ name: "tokenizer.json", sha256: "8".repeat(64) }],
        },
        taskCorrectness: {
          ...gates.taskCorrectness,
          suites: gates.taskCorrectness.suites.map((suite, index) =>
            index === 0
              ? { ...suite, labelSource: "dataset" }
              : {
                  ...suite,
                  labelSource: "confirmed",
                  labelsSha256: "7".repeat(64),
                  originalBalancedAccuracy: 0.79,
                },
          ),
        },
      },
    },
  };
}

function firstPartyDocument(value) {
  const { bytes, document } = qualificationDocument(value);
  document.entries[0].specialistKind = "first-party-rag-specialist";
  return { bytes, document };
}

async function validateFirstParty(value, loader = firstPartyEvidenceLoader()) {
  const { bytes, document } = firstPartyDocument(value);
  return validate(document, bytes, { loadEvidenceFile: loader });
}

test("accepts a first-party RAG specialist bound to its pinned training commit", async () => {
  assert.deepEqual(await validateFirstParty(firstPartyReport()), ["adapter"]);
});

test("rejects a first-party specialist whose report claims another kind", async () => {
  await assert.rejects(
    validateFirstParty({ ...firstPartyReport(), specialistKind: "upstream-rag-specialist" }),
    /must declare first-party-rag-specialist/,
  );
  const upstreamClaim = qualificationDocument(firstPartyReport());
  upstreamClaim.document.entries[0].specialistKind = "upstream-rag-specialist";
  await assert.rejects(
    validate(upstreamClaim.document, upstreamClaim.bytes, {
      loadEvidenceFile: firstPartyEvidenceLoader(),
    }),
    /must declare upstream-rag-specialist/,
  );
});

test("rejects first-party provenance that claims to be upstream or omits training identity", async () => {
  const mutations = [
    (provenance) => {
      provenance.upstream = true;
    },
    (provenance) => {
      delete provenance.upstream;
    },
    (provenance) => {
      provenance.upstreamRepository = "ibm-granite/granitelib-rag-r1.0";
    },
    (provenance) => {
      provenance.publisher = "  ";
    },
    (provenance) => {
      provenance.trainingRevision = "4".repeat(39);
    },
    (provenance) => {
      provenance.trainingRepository = "integrallis";
    },
    (provenance) => {
      delete provenance.trainingManifest;
    },
    (provenance) => {
      delete provenance.preparedDataManifest;
    },
    (provenance) => {
      provenance.trainingManifest.sizeBytes = 0;
    },
    (provenance) => {
      provenance.modelCardSha256 = "6".repeat(63);
    },
    (provenance) => {
      provenance.license = "";
    },
    (provenance) => {
      provenance.trainingDataLicenses = [];
    },
    (provenance) => {
      provenance.trainingDataLicenses = [{ dataset: "QuAC" }];
    },
    (provenance) => {
      provenance.tokenizerFiles = [];
    },
  ];
  for (const [index, mutate] of mutations.entries()) {
    const value = firstPartyReport();
    mutate(value.evaluation.gates.provenance);
    await assert.rejects(
      validateFirstParty(value),
      /provenance must pin the first-party training commit/,
      `mutation ${index}`,
    );
  }
});

test("rejects a training manifest not pinned to the declared training commit", async () => {
  const otherRevision = firstPartyReport();
  otherRevision.evaluation.gates.provenance.trainingManifest = pinnedTrainingFile(
    trainingManifestPath,
    trainingManifestBytes,
    "5".repeat(40),
  );
  await assert.rejects(
    validateFirstParty(otherRevision),
    /provenance must pin the first-party training commit/,
  );

  const otherRepository = firstPartyReport();
  otherRepository.evaluation.gates.provenance.preparedDataManifest.uri =
    `https://raw.githubusercontent.com/someone/else/${trainingRevision}/${preparedDataManifestPath}`;
  await assert.rejects(
    validateFirstParty(otherRepository),
    /provenance must pin the first-party training commit/,
  );
});

test("rejects training or prepared-data manifests whose bytes do not match", async () => {
  const trainingUri = pinnedTrainingFile(trainingManifestPath, trainingManifestBytes).uri;
  await assert.rejects(
    validateFirstParty(
      firstPartyReport(),
      firstPartyEvidenceLoader({ [trainingUri]: Buffer.from("{}") }),
    ),
    /training manifest bytes do not match/,
  );
  const preparedUri = pinnedTrainingFile(preparedDataManifestPath, preparedDataManifestBytes).uri;
  await assert.rejects(
    validateFirstParty(
      firstPartyReport(),
      firstPartyEvidenceLoader({ [preparedUri]: Buffer.from("tampered") }),
    ),
    /prepared-data manifest bytes do not match/,
  );
  await assert.rejects(
    validateFirstParty(firstPartyReport(), async ({ evidence, kind }) =>
      kind === "clean-host-output" ? evidenceFileLoader({ evidence }) : "not bytes",
    ),
    /training manifest loader did not return bytes/,
  );
});

test("rejects a first-party adapter that is not the one the training manifest recorded", async () => {
  const mismatched = firstPartyReport();
  mismatched.evaluation.gates.provenance.adapterSha256 = "d".repeat(64);
  await assert.rejects(
    validateFirstParty(mismatched),
    /adapter weights do not match the training manifest/,
  );

  const configMismatch = firstPartyReport();
  configMismatch.evaluation.gates.provenance.adapterConfigSha256 = "9".repeat(64);
  await assert.rejects(
    validateFirstParty(configMismatch),
    /adapter configuration does not match the training manifest/,
  );

  const unrecorded = Buffer.from(JSON.stringify({ adapterFiles: {} }));
  const value = firstPartyReport();
  value.evaluation.gates.provenance.trainingManifest = pinnedTrainingFile(
    trainingManifestPath,
    unrecorded,
  );
  await assert.rejects(
    validateFirstParty(
      value,
      firstPartyEvidenceLoader({
        [pinnedTrainingFile(trainingManifestPath, unrecorded).uri]: unrecorded,
      }),
    ),
    /adapter weights do not match the training manifest/,
  );

  const notJson = Buffer.from("not json");
  const garbage = firstPartyReport();
  garbage.evaluation.gates.provenance.trainingManifest = pinnedTrainingFile(
    trainingManifestPath,
    notJson,
  );
  await assert.rejects(
    validateFirstParty(
      garbage,
      firstPartyEvidenceLoader({
        [pinnedTrainingFile(trainingManifestPath, notJson).uri]: notJson,
      }),
    ),
    /training manifest is not JSON/,
  );
});

test("rejects a first-party adapter whose trained weights differ from the catalog bundle", async () => {
  // The training manifest and the report agree with each other, but not with the weights file
  // the catalog publishes: the training commit describes some other adapter.
  const trainedOn = Buffer.from(
    JSON.stringify({
      adapterFiles: {
        "adapter_config.json": { sha256: "5".repeat(64) },
        "adapter_model.safetensors": { sha256: "d".repeat(64) },
      },
    }),
  );
  const value = firstPartyReport();
  value.evaluation.gates.provenance.adapterSha256 = "d".repeat(64);
  value.evaluation.gates.provenance.trainingManifest = pinnedTrainingFile(
    trainingManifestPath,
    trainedOn,
  );
  await assert.rejects(
    validateFirstParty(
      value,
      firstPartyEvidenceLoader({
        [pinnedTrainingFile(trainingManifestPath, trainedOn).uri]: trainedOn,
      }),
    ),
    /adapter weights do not match the catalog bundle/,
  );
});

test("rejects a first-party specialist that does not strictly beat its base", async () => {
  const tie = firstPartyReport();
  const suite = tie.evaluation.gates.taskCorrectness.suites[0];
  suite.baseBalancedAccuracy = suite.balancedAccuracy;
  await assert.rejects(validateFirstParty(tie), /strictly beat its base/);

  // The same tie is admissible for an upstream specialist, whose rule is "no worse than base".
  const upstreamTie = upstreamReport();
  const upstreamSuite = upstreamTie.evaluation.gates.taskCorrectness.suites[0];
  upstreamSuite.baseBalancedAccuracy = upstreamSuite.balancedAccuracy;
  const { bytes, document } = upstreamDocument(upstreamTie);
  await validate(document, bytes);

  const missingBase = firstPartyReport();
  delete missingBase.evaluation.gates.taskCorrectness.suites[1].baseBalancedAccuracy;
  await assert.rejects(validateFirstParty(missingBase), /answerability window/);
});

test("rejects confirmed labels without their identity and unknown label sources", async () => {
  const withoutHash = firstPartyReport();
  delete withoutHash.evaluation.gates.taskCorrectness.suites[1].labelsSha256;
  await assert.rejects(validateFirstParty(withoutHash), /confirmed labels/);

  const withoutOriginal = firstPartyReport();
  delete withoutOriginal.evaluation.gates.taskCorrectness.suites[1].originalBalancedAccuracy;
  await assert.rejects(validateFirstParty(withoutOriginal), /confirmed labels/);

  const unknownSource = firstPartyReport();
  unknownSource.evaluation.gates.taskCorrectness.suites[0].labelSource = "judge";
  await assert.rejects(validateFirstParty(unknownSource), /labelSource/);

  const absentSource = firstPartyReport();
  delete absentSource.evaluation.gates.taskCorrectness.suites[0].labelSource;
  await validateFirstParty(absentSource);
});

test("a first-party specialist still requires every upstream runtime gate", async () => {
  const cases = [
    [(gates) => delete gates.kernelIdentity, /without proven identity/],
    [(gates) => (gates.plainJava.markerRoundTrip = false), /plain Java conformance/],
    [(gates) => (gates.jvmMechanics.batchedPrefillIdentity = false), /JVM mechanics/],
    [(gates) => (gates.longContext.specialistCorrectCases = 5), /long-context gate/],
    [(gates) => (gates.performanceAndMemory.fourKImprovement = 0.19), /performance and memory/],
    [(gates) => (gates.taskCorrectness.suites[0].balancedAccuracy = 0.79), /answerability window/],
    [(gates) => delete gates.modelsArtifact, /Maven Central Models artifacts/],
    [(gates) => delete gates.cleanHostRun, /clean-host Java 25 run/],
  ];
  for (const [mutate, expected] of cases) {
    const value = firstPartyReport();
    mutate(value.evaluation.gates);
    await assert.rejects(validateFirstParty(value), expected);
  }
});
