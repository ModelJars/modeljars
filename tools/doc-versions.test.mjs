import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/** The versions every document must agree with, read from the build rather than restated here. */
async function authoritativeVersions() {
  const properties = await readFile(path.join(repositoryRoot, "gradle.properties"), "utf8");
  const read = (key) => {
    const match = properties.match(new RegExp(`^${key}\\s*=\\s*(\\d+\\.\\d+\\.\\d+)`, "m"));
    assert.ok(match, `gradle.properties must declare a concrete ${key}`);
    return match[1];
  };
  return { modeljars: read("modeljarsVersion"), models: read("modelsVersion"), vectors: read("vectorsVersion") };
}

const SKIP_DIRECTORIES = new Set([
  "build", "node_modules", ".git", ".gradle", "benchmark-results", "catalog",
]);
const TEXT = new Set([".md", ".html", ".js", ".yml", ".yaml", ".adoc", ".json", ".txt"]);

async function* documents(dir) {
  for (const item of await readdir(dir, { withFileTypes: true })) {
    if (item.isDirectory()) {
      if (SKIP_DIRECTORIES.has(item.name)) continue;
      yield* documents(path.join(dir, item.name));
    } else if (TEXT.has(path.extname(item.name)) && !/CHANGELOG/i.test(item.name)) {
      yield path.join(dir, item.name);
    }
  }
}

test("every document cites the current version of each library", async () => {
  // AUDITED 2026-10-23. docs/github-setup.md documented the release graph as modeljars 0.1.57 ->
  // models 0.3.58 -> vectors-core 0.1.29 against a published 0.1.64 -> 0.5.4 -> 0.1.30, and
  // site/apple/index.html told a reader to depend on backend-apple 0.3.42. Nothing checked either,
  // so both drifted for releases at a time while naming coordinates a reader would copy.
  const expected = await authoritativeVersions();
  const coordinate =
    /(org\.modeljars|com\.integrallis):([A-Za-z0-9.-]+):(\d+\.\d+\.\d+)/g;
  const stale = [];

  for await (const file of documents(repositoryRoot)) {
    const relative = path.relative(repositoryRoot, file);
    const text = await readFile(file, "utf8");
    for (const match of text.matchAll(coordinate)) {
      const [, group, artifact, cited] = match;
      const family =
        group === "org.modeljars"
          ? "modeljars"
          : artifact.startsWith("vectors")
            ? "vectors"
            : "models";
      if (cited !== expected[family]) {
        stale.push(`${relative}: ${group}:${artifact}:${cited} (current ${expected[family]})`);
      }
    }
  }

  assert.deepEqual(
    [...new Set(stale)].sort(),
    [],
    `Documents cite versions that are not current:\n  ${[...new Set(stale)].sort().join("\n  ")}`,
  );
});
