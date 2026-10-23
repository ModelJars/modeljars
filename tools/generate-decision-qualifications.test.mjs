import assert from "node:assert/strict";
import test from "node:test";

import { entryFromReport } from "./generate-decision-qualifications.mjs";

const model = {
  id: "vendor_decision_gguf_q8_0",
  name: "Vendor Decision GGUF Q8_0",
  sha256: "a".repeat(64),
  sizeBytes: 449_397_600,
};

function report(overrides = {}) {
  return {
    policy: "decision-calibration-v2",
    cohort: "JevBench v1.1, MIT, 120 cases",
    cohortSha256: "b".repeat(64),
    artifactSha256: model.sha256,
    artifactSizeBytes: model.sizeBytes,
    cases: 120,
    chanceAccuracy: 0.300417,
    accuracy: 0.783333,
    calibrationError: 0.16377,
    uncalibratedAnswers: 0,
    uncalibratedBucketsExercised: 0,
    tier: "RANKED_DECISION",
    publishable: true,
    exportsConfidenceQuality: false,
    byType: { choice: { cases: 72, accuracy: 0.791667, chance: 0.209028 } },
    limitations: ["calibration error 0.1638 > 0.10"],
    environment: { osArch: "x86_64" },
    ...overrides,
  };
}

const PATH = "experiments/laya-modern-bert/evidence/jevbench-120-decision-calibration-v2.json";

test("derives every entry field from the report", () => {
  const entry = entryFromReport(model.id, report(), PATH, "c".repeat(64), "models-0.5.3", model);
  assert.equal(entry.modelId, model.id);
  assert.equal(entry.model, model.name);
  assert.equal(entry.tier, "RANKED_DECISION");
  assert.equal(entry.cases, 120);
  assert.equal(entry.accuracy, 0.783333);
  assert.equal(entry.chanceAccuracy, 0.300417);
  assert.equal(entry.calibrationError, 0.16377);
  assert.equal(entry.uncalibratedBucketsExercised, 0);
  assert.equal(entry.report, PATH);
  assert.equal(entry.reportSha256, "c".repeat(64));
  assert.equal(entry.backendVersion, "models-0.5.3");
  assert.deepEqual(entry.environment, { osArch: "x86_64" });
});

test("refuses a report whose artifact digest disagrees with the catalog", () => {
  // The point of checking rather than copying: an entry whose digest does not match the catalog
  // is a claim about a different file than the one users download.
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ artifactSha256: "e".repeat(64) }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
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
        report({ artifactSizeBytes: 1 }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /artifactSizeBytes/,
  );
});

test("refuses an unpublishable report", () => {
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ tier: "UNQUALIFIED", publishable: false }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /does not publish/,
  );
});

test("refuses a report whose tier and confidence claim disagree", () => {
  // Two independent statements of the same fact in one report. Letting them disagree is how a
  // model ends up advertising a thresholdable probability its tier does not support.
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ exportsConfidenceQuality: true }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /exportsConfidenceQuality=true/,
  );
});

test("refuses CALIBRATED_DECISION with no measured calibration error", () => {
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({
          tier: "CALIBRATED_DECISION",
          exportsConfidenceQuality: true,
          calibrationError: null,
        }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /no measured calibration error/,
  );
});

test("refuses an accuracy that does not beat chance", () => {
  // Not a quality threshold -- a model no better than guessing has not been shown to decide
  // anything, whichever policy admitted it.
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ accuracy: 0.3 }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /does not beat chance/,
  );
});

test("refuses a report missing its cohort digest", () => {
  const without = report();
  delete without.cohortSha256;
  assert.throws(
    () => entryFromReport(model.id, without, PATH, "c".repeat(64), "models-0.5.3", model),
    /missing cohortSha256/,
  );
});

test("refuses a report from another policy", () => {
  assert.throws(
    () =>
      entryFromReport(
        model.id,
        report({ policy: "decision-calibration-v1" }),
        PATH,
        "c".repeat(64),
        "models-0.5.3",
        model,
      ),
    /is not decision-calibration-v2/,
  );
});
