import assert from "node:assert/strict";
import test from "node:test";

import {
  copyDependencySnippet,
  gradleDependencySnippet,
  mavenDependencySnippet,
} from "./dependency-snippets.js";
import { renderDependencyCopyActions } from "./catalog-entry-actions.js";

const coordinate =
  "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1";

test("renders marker dependencies for Gradle and Maven", () => {
  assert.equal(
    gradleDependencySnippet(coordinate),
    `implementation("${coordinate}")`,
  );
  assert.equal(
    mavenDependencySnippet(coordinate),
    `<dependency>
  <groupId>org.modeljars.huggingface</groupId>
  <artifactId>ggml-org.qwen3-0.6b-gguf.q4_0</artifactId>
  <version>3.0.0-q4_0.1</version>
</dependency>`,
  );
});

test("copies the selected build-tool dependency", async () => {
  const copied = [];
  const clipboard = { writeText: async (value) => copied.push(value) };

  const result = await copyDependencySnippet("gradle", coordinate, clipboard);

  assert.equal(result, `implementation("${coordinate}")`);
  assert.deepEqual(copied, [result]);
});

test("renders exactly two icon-only dependency controls per catalog row", () => {
  const markup = renderDependencyCopyActions({
    name: "Qwen3 0.6B",
    markerCoordinate: coordinate,
  });

  assert.equal((markup.match(/<button/g) || []).length, 2);
  assert.match(markup, /data-build-tool="maven"/);
  assert.match(markup, /data-build-tool="gradle"/);
  assert.match(markup, /src="\/assets\/apachemaven\.svg"/);
  assert.match(markup, /src="\/assets\/gradle\.svg"/);
  assert.match(markup, /aria-label="Copy Maven dependency for Qwen3 0\.6B"/);
  assert.doesNotMatch(markup, />\s*(Maven|Gradle)\s*</);
});
