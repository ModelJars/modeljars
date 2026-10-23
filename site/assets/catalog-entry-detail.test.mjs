import assert from "node:assert/strict";
import test from "node:test";
test("a typed-decision model renders its own evidence instead of breaking the page", async () => {
  // MEASURED 2026-10-23: selecting laya_q8_0 on the live site rendered "Catalog unavailable:
  // Cannot read properties of null (reading 'backend')". primaryQualification() enumerated six
  // qualification kinds and not the decision kind, so a model carrying only decision evidence had
  // no primary qualification at all. Fixing that alone was not enough -- the summary then fell
  // into the RAG-shaped branch and formatDuration(p95TtftMillis) threw, because a decision has no
  // TTFT. Both halves are pinned here.
  const { primaryQualification, qualificationLabel } = await import("./qualification-data.js");
  const { qualificationSummary, isDecisionEvidence } = await import("./model-detail.js");

  const model = {
    id: "laya_q8_0",
    decisionQualifications: [
      {
        modelId: "laya_q8_0",
        model: "Laya GGUF Q8_0",
        backend: "pure-java",
        backendVersion: "models-0.5.4",
        tier: "RANKED_DECISION",
        cohort: "JevBench v1.1, MIT, 120 cases",
        cases: 120,
        chanceAccuracy: 0.300417,
        accuracy: 0.783333,
        calibrationError: 0.16377,
        exportsConfidenceQuality: false,
        reportUri: "https://example.invalid/report.json",
        reportSha256: "f".repeat(64),
      },
    ],
  };

  const qualification = primaryQualification(model);
  assert.ok(qualification, "a decision-only model must have a primary qualification");
  assert.equal(qualification.useCaseTier, "RANKED_DECISION");
  assert.equal(qualification.qualified, true, "both publishable tiers are qualified");
  assert.equal(qualificationLabel(qualification), "Ranked decisions");
  assert.ok(isDecisionEvidence(qualification), "chanceAccuracy identifies decision evidence");

  const summary = qualificationSummary(qualification);
  assert.equal(summary.accuracy, "78.3%");
  assert.equal(summary.chance, "30.0%");
  assert.equal(summary.calibrationError, "0.1638");
  assert.equal(summary.exportsConfidenceQuality, false, "RANKED_DECISION exports no confidence");

  // The stronger tier is labelled and reported differently, which is the whole point of the tier.
  const calibrated = primaryQualification({
    decisionQualifications: [
      { ...model.decisionQualifications[0], tier: "CALIBRATED_DECISION", exportsConfidenceQuality: true },
    ],
  });
  assert.equal(qualificationLabel(calibrated), "Calibrated decisions");
  assert.equal(qualificationSummary(calibrated).exportsConfidenceQuality, true);

  // A cohort that measured no calibration reports that, never a zero that reads as perfect.
  const unmeasured = qualificationSummary(
    primaryQualification({
      decisionQualifications: [{ ...model.decisionQualifications[0], calibrationError: null }],
    }),
  );
  assert.equal(unmeasured.calibrationError, "not measured");
});
