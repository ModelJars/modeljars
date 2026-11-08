import assert from "node:assert/strict";
import test from "node:test";
import {isVisionEvidence, qualificationSummary, visionJavaSnippet} from "./model-detail.js";
import {primaryQualification, qualificationLabel} from "./qualification-data.js";
import {verificationProfile} from "./taxonomy.js";

test("vision entries select image evidence and an image runtime example", () => {
  const entry = {qualified: true, useCaseTier: "IMAGE_QUESTION_ANSWERING", backendVersion: "fixture",
    datasets: {vqav2: {cases: 32, candidateScore: .7}, textvqa: {cases: 32, candidateScore: .4}},
    report: "https://example.invalid/report", reportSha256: "a".repeat(64)};
  const selected = primaryQualification({visionQualifications: [entry]});
  assert.equal(isVisionEvidence(selected), true);
  assert.equal(qualificationLabel(selected), "Image questions");
  assert.equal(qualificationSummary(selected).evidenceUri, entry.report);
  const snippet = visionJavaSnippet("llava_fixture");
  assert.match(snippet, /ModelJars\.openVisionRuntime/);
  assert.match(snippet, /Files\.readAllBytes/);
  assert.doesNotMatch(snippet, /ModelJars\.openRuntime\(/);
});

test("vision verification counts raw dataset cases for passing and rejected evidence", () => {
  for (const qualified of [true, false]) {
    const profile = verificationProfile({visionQualifications: [{qualified,
      datasets: {vqav2: {cases: 32}, textvqa: {cases: 32}}}]});
    assert.deepEqual(profile.checks, ["64-case image-question evaluation"]);
  }
  const malformed = verificationProfile({visionQualifications: [{qualified: false, datasets: {}}]});
  assert.deepEqual(malformed.checks, []);
});
