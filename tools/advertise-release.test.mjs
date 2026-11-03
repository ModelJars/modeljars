import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { advertiseRelease, CARRIERS } from "./advertise-release.mjs";

async function siteFixture(version) {
  const root = await mkdtemp(path.join(tmpdir(), "advertise-"));
  await mkdir(path.join(root, "assets"), { recursive: true });
  await writeFile(
    path.join(root, "index.html"),
    `<pre><code>implementation("org.modeljars:modeljars:${version}")\n` +
      `implementation("org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1")</code></pre>\n`,
  );
  await writeFile(
    path.join(root, "assets", "dependency-snippets.js"),
    'export function gradleSnippet() {\n' +
      `  return \`implementation("org.modeljars:modeljars:${version}")\`;\n` +
      "}\n" +
      "export function mavenSnippet() {\n" +
      "  return `<dependency>\n  <groupId>org.modeljars</groupId>\n" +
      `  <artifactId>modeljars</artifactId>\n  <version>${version}</version>\n</dependency>\`;\n` +
      "}\n",
  );
  return root;
}

test("rewrites every carrier, including the generator's Maven block", async () => {
  const root = await siteFixture("0.1.73");
  const report = await advertiseRelease(root, "0.1.76");

  assert.deepEqual(
    report.map((entry) => [entry.file, entry.changed]),
    [["index.html", 1], ["assets/dependency-snippets.js", 2]],
  );
  for (const carrier of CARRIERS) {
    const text = await readFile(path.join(root, carrier), "utf8");
    assert.doesNotMatch(text, /0\.1\.73/, `${carrier} still advertises the old version`);
  }
  // The page alone is not enough: fixing index.html while the generator kept emitting the old
  // coordinate is the exact drift this repository has hit twice.
  const generator = await readFile(path.join(root, "assets/dependency-snippets.js"), "utf8");
  assert.match(generator, /org\.modeljars:modeljars:0\.1\.76/);
  assert.match(generator, /<version>0\.1\.76<\/version>/);
});

test("leaves other coordinates alone", async () => {
  const root = await siteFixture("0.1.73");
  await advertiseRelease(root, "0.1.76");
  const page = await readFile(path.join(root, "index.html"), "utf8");
  assert.match(page, /ggml-org\.qwen3-0\.6b-gguf\.q4_0:3\.0\.0-q4_0\.1/);
});

test("is idempotent, so a repeated finalize opens no empty change", async () => {
  const root = await siteFixture("0.1.76");
  const report = await advertiseRelease(root, "0.1.76");
  assert.equal(report.reduce((sum, entry) => sum + entry.changed, 0), 0);
});

test("refuses anything that is not a release version", async () => {
  const root = await siteFixture("0.1.73");
  for (const bad of ["0.1.76-SNAPSHOT", "v0.1.76", "0.1", "", "latest"]) {
    await assert.rejects(() => advertiseRelease(root, bad), /Not a release version/);
  }
});

test("fails closed when the carriers move", async () => {
  const root = await siteFixture("0.1.73");
  await writeFile(path.join(root, "index.html"), "<p>no coordinate here</p>\n");
  await assert.rejects(() => advertiseRelease(root, "0.1.76"), /the carriers moved/);
});
