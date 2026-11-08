import assert from "node:assert/strict";
import test from "node:test";
import {isVisionEvidence, qualificationSummary, visionJavaSnippet} from "./model-detail.js";
import {primaryQualification, qualificationLabel} from "./qualification-data.js";

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
