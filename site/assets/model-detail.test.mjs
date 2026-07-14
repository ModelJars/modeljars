import assert from "node:assert/strict";
import test from "node:test";

import { gradleSnippet, mavenSnippet, modelIdFromPath } from "./model-detail.js";

const coordinate = "org.modeljars.huggingface:qwen.qwen3.q4_k_m:3.0.0-q4_k_m.1";

test("extracts generated model route identifiers", () => {
  assert.equal(modelIdFromPath("/models/qwen3_8b_q4_k_m/"), "qwen3_8b_q4_k_m");
  assert.equal(modelIdFromPath("/models/qwen3_8b_q4_k_m/index.html"), "qwen3_8b_q4_k_m");
  assert.equal(modelIdFromPath("/plugins"), null);
});

test("renders build-tool snippets from marker coordinates", () => {
  assert.equal(
    gradleSnippet(coordinate),
    'runtimeOnly("org.modeljars.huggingface:qwen.qwen3.q4_k_m:3.0.0-q4_k_m.1")',
  );
  assert.match(mavenSnippet(coordinate), /<groupId>org\.modeljars\.huggingface<\/groupId>/);
  assert.match(mavenSnippet(coordinate), /<artifactId>qwen\.qwen3\.q4_k_m<\/artifactId>/);
  assert.match(mavenSnippet(coordinate), /<version>3\.0\.0-q4_k_m\.1<\/version>/);
});
