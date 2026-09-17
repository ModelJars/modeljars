import assert from "node:assert/strict";
import test from "node:test";

import { generationViolations } from "./qualification-generation-gate.mjs";

const base = {
  schemaVersion: 1,
  generatedAt: "2026-09-16T09:54:54Z",
  policyVersion: "production-rag-model-contribution-v6",
  targetQualifiedModels: 33,
  entries: [{ modelId: "granite", promptTemplate: "granite-documents" }],
};

test("rejects the metadata edit that shipped without advancing generatedAt", () => {
  const head = { ...base, targetQualifiedModels: 25 };

  const violations = generationViolations("catalog/qualifications.json", base, head);

  assert.equal(violations.length, 1);
  assert.match(violations[0], /changed without advancing generatedAt/);
});

test("rejects an entry edit at the same instant", () => {
  const head = { ...base, entries: [{ modelId: "granite", promptTemplate: "granite" }] };

  assert.equal(generationViolations("catalog/qualifications.json", base, head).length, 1);
});

test("accepts a change that advances generatedAt", () => {
  const head = { ...base, generatedAt: "2026-09-16T15:34:54Z", targetQualifiedModels: 25 };

  assert.deepEqual(generationViolations("catalog/qualifications.json", base, head), []);
});

test("ignores key order and formatting", () => {
  const { entries, ...rest } = base;
  const head = { entries, ...rest };

  assert.deepEqual(generationViolations("catalog/qualifications.json", base, head), []);
});

test("rejects generatedAt moving backwards even without other changes", () => {
  const head = { ...base, generatedAt: "2026-09-15T00:00:00Z" };

  assert.equal(generationViolations("catalog/qualifications.json", base, head).length, 1);
});

test("accepts manifests that are new or removed", () => {
  assert.deepEqual(generationViolations("catalog/new.json", undefined, base), []);
  assert.deepEqual(generationViolations("catalog/old.json", base, undefined), []);
});
