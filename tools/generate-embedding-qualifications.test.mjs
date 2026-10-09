import assert from "node:assert/strict";
import test from "node:test";

import { entryFromReport } from "./generate-embedding-qualifications.mjs";

const model = {
  id: "vendor_model_gguf_q8_0",
  sha256: "a".repeat(64),
  sizeBytes: 1_234_567,
};

function report(overrides = {}) {
  return {
    workload: "oracle-equivalence-v1",
    model: "Vendor Model GGUF Q8_0",
    backend: "pure-java",
    artifactSha256: model.sha256,
    artifactSizeBytes: model.sizeBytes,
    probeSetSha256: "b".repeat(64),
    probes: 8,
    embeddingDimension: 768,
    pooling: "mean",
    normalized: true,
    oracleBackend: "llama.cpp",
    oracleVersion: "c".repeat(40),
    minimumOracleCosine: 0.9995,
    meanOracleCosine: 0.9997,
    maxComponentDelta: 0.004,
    maxNormDeviation: 2.7e-9,
    qualified: true,
    environment: { hostname: "rockhopper" },
    ...overrides,
  };
}

test("derives every entry field from the report", () => {
  const entry = entryFromReport(
    model.id,
    report(),
    "benchmark-results/embedding/vendor-model-gguf-q8-0.json",
    "d".repeat(64),
    "models-0.3.54",
    model,
  );
  assert.equal(entry.modelId, model.id);
  assert.equal(entry.minimumOracleCosine, 0.9995);
  assert.equal(entry.backendVersion, "models-0.3.54");
  assert.equal(entry.report, "benchmark-results/embedding/vendor-model-gguf-q8-0.json");
  assert.equal(entry.reportSha256, "d".repeat(64));
  assert.deepEqual(entry.environment, { hostname: "rockhopper" });
});

test("refuses a report whose artifact digest disagrees with the catalog", () => {
  // The whole point of checking rather than copying: an entry whose digest does not match the
  // catalog is a claim about a different file than the one users download.
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ artifactSha256: "e".repeat(64) }),
        "report.json",
        "d".repeat(64),
        "models-0.3.54",
        model,
      ),
    /does not match catalog/,
  );
});

test("refuses a report whose artifact size disagrees with the catalog", () => {
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ artifactSizeBytes: 999 }),
        "report.json",
        "d".repeat(64),
        "models-0.3.54",
        model,
      ),
    /artifactSizeBytes/,
  );
});

test("refuses a report that did not pass", () => {
  // A failing measurement is a result worth keeping, but not a catalog entry.
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ qualified: false }),
        "report.json",
        "d".repeat(64),
        "models-0.3.54",
        model,
      ),
    /only passes land/,
  );
});

test("refuses a report missing any field the entry must carry", () => {
  for (const field of ["pooling", "oracleVersion", "minimumOracleCosine", "embeddingDimension"]) {
    const incomplete = report();
    delete incomplete[field];
    assert.throws(
      () =>
        entryFromReport(model.id, incomplete, "report.json", "d".repeat(64), "models-0.3.54", model),
      new RegExp(`missing ${field}`),
      `${field} must be required`,
    );
  }
});
