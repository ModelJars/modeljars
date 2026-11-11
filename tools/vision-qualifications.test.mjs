import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {mkdtemp, writeFile, rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import test from "node:test";
import {publishable, registryProperties, validateEntry, validateManifest} from "./vision-qualifications.mjs";
import {filterQualifiedPublications, selectCatalogPublications} from "./model-publications.mjs";

const model = JSON.parse(readFileSync(new URL("../catalog/models.json", import.meta.url))).models
  .find(entry => entry.id === "second_state_llava_v1_5_7b_gguf_q4_k_s");
const catalog = {schemaVersion: 2, models: [model]};
function fixture() {
  return {modelId: model.id, backendVersion: "models@test+" + "a".repeat(40),
    report: "https://example.invalid/vision-report", reportSha256: "a".repeat(64), corpusSha256: "b".repeat(64),
    files: structuredClone(model.files), tokenAgreement: 1, integrationPassed: true, qualified: true,
    releasedRuntimeVerified: false, modeljarsRuntimeVerified: false,
    datasets: Object.fromEntries(["vqav2", "textvqa"].map(name => [name,
      {cases: 32, candidateScore: .75, referenceScore: .75, blankCases: 8, imageContribution: .2}])),
    performance: ["vqav2", "textvqa"].flatMap(dataset => [0, 1].map(i =>
      ({dataset, caseId: `${dataset}-${i}`, warmups: 1, repetitions: 3, prefillRatio: 1, decodeRatio: 1, totalRatio: 1}))),
  };
}
const manifest = entry => ({schemaVersion: 1, policy: "llava-vqa-v1", entries: [entry]});

test("vision-only CLI evidence filters manual selection and detects a new qualified delta", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vision-publication-"));
  try {
    const current = join(directory, "catalog.json"), evidence = join(directory, "vision.json");
    const previous = join(directory, "previous-vision.json");
    await writeFile(current, JSON.stringify(catalog));
    await writeFile(previous, JSON.stringify({...manifest(fixture()), entries: []}));
    const entry = fixture();
    await writeFile(evidence, JSON.stringify(manifest(entry)));
    const run = extra => {
      const result = spawnSync(process.execPath,
        [fileURLToPath(new URL("./plan-model-publications.mjs", import.meta.url)),
          "--current", current, "--vision-qualifications", evidence, ...extra], {encoding: "utf8"});
      assert.equal(result.status, 0, result.stderr);
      return JSON.parse(result.stdout);
    };
    assert.deepEqual(run(["--ids", model.id]).publications, []);
    const delta = ["--previous", current, "--previous-vision-qualifications", previous];
    assert.deepEqual(run(delta).publications, []);
    entry.backendVersion = "0.0.1";
    entry.releasedRuntimeVerified = entry.modeljarsRuntimeVerified = true;
    entry.releaseVerification = {modelsVersion: "0.0.1", reportSha256: "c".repeat(64),
      report: `https://raw.githubusercontent.com/example/repository/${"a".repeat(40)}/release.json`};
    entry.modeljarsVerification = {reportSha256: "d".repeat(64),
      report: `https://raw.githubusercontent.com/example/repository/${"a".repeat(40)}/consumer.json`};
    await writeFile(evidence, JSON.stringify(manifest(entry)));
    assert.deepEqual(run(delta).publications.map(p => p.id), [model.id]);
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test("passing branch evidence loads but cannot publish before release and ModelJars verification", () => {
  const entry = fixture();
  validateManifest(manifest(entry), catalog);
  assert.equal(publishable(entry), false);
  assert.match(registryProperties(manifest(entry)), /vision\.0\.qualified=true/);
  const planned = selectCatalogPublications(catalog, [model.id]);
  const filter = e => filterQualifiedPublications(planned, catalog,
    ...Array(7).fill({schemaVersion: 1, entries: []}), {schemaVersion: 2, entries: []}, manifest(e));
  assert.equal(filter(entry).publications.length, 0);
  entry.backendVersion = "0.0.1";
  entry.releaseVerification = {modelsVersion: "0.0.1",
    report: `https://raw.githubusercontent.com/example/repository/${"a".repeat(40)}/released.json`, reportSha256: "c".repeat(64)};
  entry.releasedRuntimeVerified = true;
  assert.equal(filter(entry).publications.length, 0);
  entry.modeljarsRuntimeVerified = true;
  assert.throws(() => filter(entry), /ModelJars runtime/);
  entry.modeljarsVerification = {report: `https://raw.githubusercontent.com/example/repository/${"b".repeat(40)}/runtime.json`, reportSha256: "d".repeat(64)};
  assert.equal(filter(entry).publications.length, 1);
});

test("changed projector, failed metrics and missing cohorts cannot become qualified", () => {
  const changed = fixture(); changed.files[1].sha256 = "f".repeat(64);
  assert.throws(() => validateManifest(manifest(changed), catalog), /snapshot/);
  for (const mutate of [
    e => e.performance[0].totalRatio = 1.11,
    e => e.datasets.vqav2.candidateScore = .49,
    e => e.datasets.textvqa.candidateScore = .19,
    e => e.datasets.vqav2.referenceScore = .76,
    e => e.tokenAgreement = .98,
    e => e.integrationPassed = false,
    e => e.datasets.vqav2.imageContribution = .04,
    e => e.datasets.vqav2.cases = 31,
    e => e.performance[0].prefillRatio = NaN,
    e => e.files[0].path = "../weights.gguf",
    e => e.releasedRuntimeVerified = true,
    e => e.modeljarsRuntimeVerified = true,
  ]) {
    const entry = fixture(); mutate(entry);
    assert.throws(() => validateEntry(entry));
  }
  const rejected = fixture(); rejected.qualified = false; rejected.performance[0].totalRatio = 1.11;
  validateEntry(rejected);
  assert.equal(publishable(rejected), false);
});
