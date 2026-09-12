import assert from "node:assert/strict";
import test from "node:test";

import { fetchStarCount, formatStarCount, readCachedStarCount } from "./github-stars.js";

test("reads the public repository star count", async () => {
  const count = await fetchStarCount(async (url, options) => {
    assert.equal(url, "https://api.github.com/repos/ModelJars/modeljars");
    assert.equal(options.headers.Accept, "application/vnd.github+json");
    return { ok: true, json: async () => ({ stargazers_count: 1234 }) };
  });

  assert.equal(count, 1234);
  assert.equal(formatStarCount(count, "en-US"), "1,234");
});

test("rejects failed or malformed GitHub responses", async () => {
  await assert.rejects(
    fetchStarCount(async () => ({ ok: false, status: 403 })),
    /GitHub repository request failed: 403/,
  );
  await assert.rejects(
    fetchStarCount(async () => ({ ok: true, json: async () => ({ stargazers_count: -1 }) })),
    /valid stargazers_count/,
  );
});

test("uses only a fresh, repository-specific cached count", () => {
  const now = 2_000_000;
  const fresh = {
    getItem: () => JSON.stringify({ repository: "ModelJars/modeljars", count: 42, storedAt: now - 1 }),
  };
  const stale = {
    getItem: () => JSON.stringify({ repository: "ModelJars/modeljars", count: 42, storedAt: now - 3_600_001 }),
  };
  const wrongRepository = {
    getItem: () => JSON.stringify({ repository: "someone/else", count: 42, storedAt: now - 1 }),
  };

  assert.equal(readCachedStarCount(fresh, now), 42);
  assert.equal(readCachedStarCount(stale, now), null);
  assert.equal(readCachedStarCount(wrongRepository, now), null);
  assert.equal(readCachedStarCount({ getItem: () => { throw new Error("blocked"); } }, now), null);
});
