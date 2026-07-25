import assert from "node:assert/strict";
import test from "node:test";

import {
  changedGgufModelIds,
  createRetryingFetch,
} from "./catalog-enrichment.mjs";

test("retries transient range fetch failures without changing the request", async () => {
  const requests = [];
  const delays = [];
  let attempts = 0;
  const retryingFetch = createRetryingFetch({
    fetchImpl: async (input, init) => {
      attempts += 1;
      requests.push({ input, init });
      if (attempts === 1) {
        throw new TypeError("fetch failed", {
          cause: Object.assign(new Error("connect timeout"), {
            code: "UND_ERR_CONNECT_TIMEOUT",
          }),
        });
      }
      if (attempts === 2) {
        return new Response("unavailable", { status: 503 });
      }
      return new Response(new Uint8Array([1, 2, 3]), { status: 206 });
    },
    maxAttempts: 4,
    sleep: async (delayMillis) => delays.push(delayMillis),
  });

  const response = await retryingFetch("https://example.test/model.gguf", {
    headers: { Range: "bytes=0-1999999" },
  });

  assert.equal(response.status, 206);
  assert.equal(attempts, 3);
  assert.deepEqual(delays, [1_000, 2_000]);
  assert.deepEqual(
    requests.map(({ input, init }) => ({ input, range: init.headers.Range })),
    [
      { input: "https://example.test/model.gguf", range: "bytes=0-1999999" },
      { input: "https://example.test/model.gguf", range: "bytes=0-1999999" },
      { input: "https://example.test/model.gguf", range: "bytes=0-1999999" },
    ],
  );
});

test("does not retry permanent HTTP failures", async () => {
  let attempts = 0;
  const retryingFetch = createRetryingFetch({
    fetchImpl: async () => {
      attempts += 1;
      return new Response("missing", { status: 404 });
    },
    sleep: async () => assert.fail("permanent failures must not be delayed"),
  });

  await assert.rejects(
    retryingFetch("https://example.test/missing.gguf"),
    /HTTP 404/,
  );
  assert.equal(attempts, 1);
});

test("selects only changed and newly added GGUF models", () => {
  const baseline = {
    models: [
      { id: "unchanged", format: "gguf", dimensions: { blockCount: 16 } },
      { id: "backend_changed", format: "gguf", backends: { "pure-java": true } },
      { id: "removed", format: "gguf" },
      { id: "non_gguf", format: "safetensors", revision: "old" },
    ],
  };
  const current = {
    models: [
      { id: "unchanged", format: "gguf", dimensions: { blockCount: 16 } },
      {
        id: "backend_changed",
        format: "gguf",
        backends: { "pure-java": true, "rust-ffm": true },
      },
      { id: "new_model", format: "gguf" },
      { id: "non_gguf", format: "safetensors", revision: "new" },
    ],
  };

  assert.deepEqual(changedGgufModelIds(baseline, current), [
    "backend_changed",
    "new_model",
  ]);
});
