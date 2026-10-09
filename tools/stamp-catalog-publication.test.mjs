import assert from "node:assert/strict";
import test from "node:test";

import { qualifiedIds, withPublicationInstant } from "./stamp-catalog-publication.mjs";

const instant = "2026-10-09T10:00:00Z";

test("inserts the instant directly after sizeBytes and leaves every other key in place", () => {
  const model = {
    id: "vendor_model",
    name: "Vendor Model",
    sha256: "a".repeat(64),
    sizeBytes: 10,
    license: "apache-2.0",
  };
  const stamped = withPublicationInstant(model, instant);
  assert.deepEqual(Object.keys(stamped), [
    "id",
    "name",
    "sha256",
    "sizeBytes",
    "catalogPublishedAt",
    "license",
  ]);
  assert.equal(stamped.catalogPublishedAt, instant);
});

test("never moves an instant that is already recorded", () => {
  // It is the FIRST publication instant: moving it would rewrite when a public marker appeared.
  const model = { id: "x", sizeBytes: 1, catalogPublishedAt: "2026-01-01T00:00:00Z" };
  assert.equal(withPublicationInstant(model, instant), model);
});

test("refuses a model with nowhere to record the instant", () => {
  assert.throws(() => withPublicationInstant({ id: "x" }, instant), /no sizeBytes/);
});

test("takes the union of qualified ids across manifests, never the sum", () => {
  // Two ids qualified on two workloads each must count once, which is the mistake the campaign
  // runbook made when it reported the catalog total.
  const rag = { entries: [{ modelId: "a", qualified: true }, { modelId: "b", qualified: true }] };
  const tool = { entries: [{ modelId: "a", summary: { qualified: true } }] };
  const embedding = { entries: [{ modelId: "c", qualified: true }, { modelId: "d", qualified: false }] };
  const ids = qualifiedIds([
    [rag, (e) => e.qualified === true],
    [tool, (e) => e.summary?.qualified === true],
    [embedding, (e) => e.qualified === true],
  ]);
  assert.deepEqual([...ids].sort(), ["a", "b", "c"]);
});
