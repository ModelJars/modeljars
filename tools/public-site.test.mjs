import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), "utf8");
}

test("publishes only artifacts that passed production qualification", async () => {
  const [catalog, qualifications, build] = await Promise.all([
    read("catalog/models.json").then(JSON.parse),
    read("catalog/qualifications.json").then(JSON.parse),
    read("build.gradle.kts"),
  ]);

  assert.ok(catalog.models.length > qualifications.entries.length);
  assert.equal(
    qualifications.entries.filter((entry) => entry.qualified).length,
    qualifications.qualifiedModels,
  );
  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  for (const entry of qualifications.entries.filter((candidate) => candidate.qualified)) {
    const model = modelsById.get(entry.modelId);
    assert.ok(model, `qualification references unknown model ${entry.modelId}`);
    assert.equal(entry.artifactSha256, model.sha256);
    assert.equal(entry.artifactSizeBytes, model.sizeBytes);
  }
  assert.match(build, /publicCatalogEntries/);
  assert.match(build, /publicCatalogEntries\.forEach/);
  assert.match(build, /Public site catalog must contain only qualified artifacts/);
});

test("explains the product, evidence, and complete Java onboarding", async () => {
  const [index, model, benchmarks, apple, contribution, detailScript, readme, operations] =
    await Promise.all([
    read("site/index.html"),
    read("site/model.html"),
    read("site/benchmarks/index.html"),
    read("site/apple/index.html"),
    read("site/contribute/index.html"),
    read("site/assets/model-detail.js"),
    read("README.md"),
    read("docs/modeljars-operations-and-model-candidates.md"),
  ]);
  const pages = [index, model, benchmarks, apple, contribution];

  for (const page of pages) {
    assert.match(page, /ModelJARs\.org/);
    assert.match(page, /href="\/apple\/"/);
    assert.match(page, /Built with .* by/);
    assert.match(
      page,
      /<a href="https:\/\/integrallis\.com"[^>]*>\s*<img[^>]+assets\/integrallis-logo\.png[^>]+alt="Integrallis"/,
    );
    assert.match(
      page,
      /https:\/\/integrallis\.github\.io\/models\/docs\/models\/current\/getting-started\.html/,
    );
  }

  assert.match(
    index,
    /ModelJARs are versioned JAR files that make AI models available to the/,
  );
  assert.match(index, /Find qualified models/);
  assert.doesNotMatch(index, /Explore qualified models/);
  assert.match(
    index,
    /Discover the power of small and medium-sized models for local, in-JVM inference/,
  );
  assert.match(index, />Models JVM inference library<\/a>/);
  assert.match(index, /Think of WebJars, but for AI models/);
  assert.doesNotMatch(index, /apple-runtime-notice|apple-runtime-band/);
  assert.match(index, /id="catalog-search"/);
  assert.match(index, /id="catalog-results"/);
  assert.ok(
    index.indexOf('class="discovery"') < index.indexOf('id="catalog-results"'),
    "catalog search must lead directly into the dynamic result set",
  );

  assert.match(apple, /class="apple-brand-mark"/);
  assert.match(apple, /Apple Foundation Models from Java/);
  assert.match(apple, /not a downloadable ModelJAR/);
  assert.match(apple, /LangChain4J and Spring AI/);
  assert.match(apple, /com\.integrallis:backend-apple:0\.2\.0/);
  assert.match(apple, /AppleFoundationModels\.create/);
  assert.match(apple, /client\.availability/);
  assert.match(
    apple,
    /integrallis\.github\.io\/models\/docs\/models\/current\/apple-foundation-models\.html/,
  );
  assert.match(index, /org\.modeljars:modeljars:0\.1\.0/);
  assert.match(index, /Add the JVM runtime and the model/);
  assert.doesNotMatch(index, /\bfacade\b/i);
  assert.match(index, /Qwen3_0_6b_Q4_0\.MODEL/);
  assert.match(index, /ModelJars\.open/);
  assert.doesNotMatch(index, /ModelJarInstaller|PureJavaBackend\.load|RustFfmBackend\.load/);
  assert.doesNotMatch(index, /Not evaluated|Evaluated, not qualified/);

  assert.match(benchmarks, /qualified artifacts with controlled cross-engine studies/i);
  assert.match(benchmarks, /generated directly from\s+the qualification ledger/i);
  assert.match(benchmarks, /Guarded RAG uses/);

  assert.match(contribution, /Artifact verification/);
  assert.match(contribution, /Production qualification/);
  assert.match(contribution, /run-controlled-rag-qualification\.sh/);
  assert.match(contribution, /not yet qualified/i);
  assert.match(contribution, /pull request/i);
  assert.match(contribution, /Retrieval recall/);
  assert.match(contribution, /Mean reciprocal rank/);
  assert.match(contribution, /Citation recall and precision/);
  assert.match(contribution, /Relative to llama\.cpp/);

  assert.match(detailScript, /model-mark-logo/);
  assert.doesNotMatch(detailScript, /\bfacade\b/i);
  assert.doesNotMatch(detailScript, /model\.name\.charAt/);
  assert.match(detailScript, /Also compatible with/);
  assert.doesNotMatch(detailScript, /<h2>Available in<\/h2>/);

  assert.match(readme, /qualified subset/i);
  assert.match(readme, /JVM runtime/);
  assert.doesNotMatch(readme, /\bfacade\b/i);
  assert.doesNotMatch(readme, /complete generated catalog is searchable/i);
  assert.match(operations, /internal candidate queue/i);
  assert.doesNotMatch(operations, /external-runner/);
  assert.doesNotMatch(
    operations,
    /publish marker JARs before pure-Java execution is complete/,
  );
});

test("CI builds only the current public Pages task", async () => {
  const [workflow, publicationWorkflow] = await Promise.all([
    read(".github/workflows/validate.yml"),
    read(".github/workflows/model-artifacts.yml"),
  ]);

  assert.match(workflow, /generateSite/);
  assert.doesNotMatch(workflow, /generatePublicSite/);
  assert.match(
    publicationWorkflow,
    /--qualifications catalog\/qualifications\.json/g,
  );
});
