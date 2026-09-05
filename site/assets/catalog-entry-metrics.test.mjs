import assert from "node:assert/strict";
import test from "node:test";

import { qualificationMetrics } from "./catalog-entry-metrics.js";

test("renders speech probes as speech metrics instead of embedding metrics", () => {
  const metrics = qualificationMetrics({
    qualified: true,
    useCaseTier: "TEXT_TO_SPEECH",
    probes: 2,
    minimumPcmCosine: 0.998389315,
    p95TimeToFirstAudioMillis: 676.696,
    p95RealTimeFactor: 1.225912,
  });

  assert.deepEqual(metrics, [
    { label: "TTFA p95", value: "677 ms" },
    { label: "RTF p95", value: "1.23" },
  ]);
});

test("omits absent optional metrics without throwing", () => {
  assert.deepEqual(
    qualificationMetrics({ qualified: true, useCaseTier: "SEMANTIC_SEARCH", probes: 2 }),
    [],
  );
});

