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
    // index.html, not getting-started.html: the Models documentation has no getting-started page,
    // so the old assertion pinned a link that returned 404 from every page of the site.
    assert.match(
      page,
      /https:\/\/integrallis\.github\.io\/models\/docs\/models\/current\/index\.html/,
    );
    assert.doesNotMatch(page, /docs\/models\/current\/getting-started\.html/);
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
  assert.match(index, />Models<\/a> JVM inference library/);
  assert.doesNotMatch(index, />Models JVM inference library<\/a>/);
  assert.match(index, /Think of WebJars, but for AI models/);
  assert.match(
    index,
    /<a class="primary-button" href="#modeljars-cli">Install the native CLI<\/a>/,
  );
  assert.match(index, /content="Discover qualified local AI models, pull verified weights/);
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
  assert.match(apple, /com\.integrallis:backend-apple:0\.3\.6/);
  assert.match(apple, /AppleFoundationModels\.create/);
  assert.match(apple, /client\.availability/);
  assert.match(
    apple,
    /integrallis\.github\.io\/models\/docs\/models\/current\/apple-foundation-models\.html/,
  );
  assert.match(index, /org\.modeljars:modeljars:0\.1\.13/);
  assert.match(index, /brew install integrallis\/tap\/modeljars/);
  assert.match(index, /modeljars pull/);
  assert.match(index, /revision-pinned upstream URL/);
  assert.match(index, /Add the JVM Runtime and the model/);
  assert.match(index, /Qwen3_0_6b_Q4_0\.MODEL/);
  assert.match(index, /ModelJars\.openRuntime/);
  assert.match(index, /InferencePipeline pipeline = runtime\.pipeline\(\)/);
  assert.match(index, /structured prefill, logits, reset, checkpoint, and rewind/);
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
  assert.doesNotMatch(detailScript, /model\.name\.charAt/);
  assert.match(detailScript, /Also compatible with/);
  assert.doesNotMatch(detailScript, /<h2>Available in<\/h2>/);

  assert.match(readme, /qualified subset/i);
  assert.match(readme, /JVM Runtime/);
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

test("renders the Java guide as readable, highlighted vertical steps", async () => {
  const [index, styles, highlighter] = await Promise.all([
    read("site/index.html"),
    read("site/assets/styles.css"),
    read("site/assets/highlight.js"),
  ]);
  const guide =
    index.match(/<section class="guide-band" id="using-modeljars"[\s\S]*?<\/section>/)?.[0];

  assert.ok(guide, "landing page must contain the Java guide");
  assert.equal((guide.match(/<article>/g) ?? []).length, 3);
  assert.equal(
    (guide.match(/<code class="language-(?:java|kotlin) hljs" data-lang="(?:java|kotlin)">/g) ?? [])
      .length,
    3,
  );
  assert.match(index, /<script src="\/assets\/highlight\.js"><\/script>/);
  assert.match(
    styles,
    /\.guide-steps\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/s,
  );
  assert.match(styles, /\.guide-body\s*\{[^}]*min-width:\s*0/s);
  assert.match(
    styles,
    /@media \(max-width: 1040px\)[\s\S]*?\.guide-inner\s*\{[^}]*display:\s*block/s,
  );
  assert.match(styles, /\.guide-steps pre\s*\{[^}]*width:\s*100%/s);
  assert.match(styles, /\.guide-steps pre\s*\{[^}]*min-width:\s*0/s);
  assert.match(styles, /\.guide-steps code\s*\{[^}]*font-size:\s*0\.9rem/s);
  assert.match(styles, /\.guide-steps code\s*\{[^}]*white-space:\s*pre/s);
  assert.match(highlighter, /registerLanguage\("java"/);
  assert.match(highlighter, /registerLanguage\("kotlin"/);
});

test("links every primary navigation to the CLI and Java guides", async () => {
  const [index, model, benchmarks, apple, contribution, javaIcon, styles] = await Promise.all([
    read("site/index.html"),
    read("site/model.html"),
    read("site/benchmarks/index.html"),
    read("site/apple/index.html"),
    read("site/contribute/index.html"),
    read("site/assets/fontawesome-mug-hot.svg"),
    read("site/assets/styles.css"),
  ]);

  for (const page of [index, model, benchmarks, apple, contribution]) {
    assert.match(page, /<a class="nav-cli" href="\/#modeljars-cli">CLI<\/a>/);
    assert.match(
      page,
      /<a class="nav-java" href="\/#using-modeljars"[^>]*>[\s\S]*?<span class="nav-java-icon" aria-hidden="true"><\/span>[\s\S]*?<span>Use from Java<\/span>[\s\S]*?<\/a>/,
    );
  }

  assert.match(javaIcon, /Font Awesome Free 7\.3\.1/);
  assert.match(javaIcon, /viewBox="0 0 576 512"/);
  assert.equal((javaIcon.match(/<path /g) ?? []).length, 1);
  assert.match(styles, /background:\s*currentColor/);
  assert.match(styles, /mask:\s*url\("\/assets\/fontawesome-mug-hot\.svg"\)/);
});
