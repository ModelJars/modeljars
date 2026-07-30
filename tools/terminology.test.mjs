import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const execute = promisify(execFile);
const textExtensions = new Set([
  ".css",
  ".html",
  ".java",
  ".js",
  ".json",
  ".kts",
  ".md",
  ".mjs",
  ".properties",
  ".toml",
  ".txt",
  ".yaml",
  ".yml",
]);

test("uses JVM Runtime terminology throughout the repository", async () => {
  const legacyTerm = ["fa", "cade"].join("");
  const inconsistentCapitalization = ["JVM", "runtime"].join(" ");
  const { stdout } = await execute(
    "git",
    ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
    {
      cwd: repositoryRoot,
      encoding: "utf8",
    },
  );
  const paths = stdout
    .split("\0")
    .filter(Boolean)
    .filter(
      (relativePath) =>
        textExtensions.has(path.extname(relativePath)) ||
        path.basename(relativePath).toLowerCase().startsWith("readme"),
    );
  const violations = [];

  for (const relativePath of paths) {
    let contents;
    try {
      contents = await readFile(path.join(repositoryRoot, relativePath), "utf8");
    } catch (error) {
      if (error.code === "ENOENT") {
        continue;
      }
      throw error;
    }
    if (relativePath.toLowerCase().includes(legacyTerm)) {
      violations.push(`${relativePath}: path`);
    }
    contents.split("\n").forEach((line, index) => {
      if (
        line.toLowerCase().includes(legacyTerm) ||
        line.includes(inconsistentCapitalization)
      ) {
        violations.push(`${relativePath}:${index + 1}`);
      }
    });
  }

  assert.deepEqual(violations, []);
});
