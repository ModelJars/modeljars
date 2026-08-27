import { formatDuration } from "./benchmark-data.js";
import { gradleSnippet, mavenSnippet } from "./dependency-snippets.js";
import { primaryQualification, qualificationLabel } from "./qualification-data.js";
import { estimateMemory, formatBytes, formatParameters } from "./resource-profile.js";
import { relatedModels, sizeTier, verificationProfile } from "./taxonomy.js";
import { initializeTheme } from "./theme.js";

export function modelIdFromPath(pathname) {
  const match = String(pathname).match(/^\/models\/([^/]+)(?:\/index\.html)?\/?$/);
  return match ? decodeURIComponent(match[1]) : null;
}

export { gradleSnippet, mavenSnippet };

function referenceClassName(modelId) {
  return String(modelId)
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("_");
}

export function javaSnippet(modelId) {
  if (!/^[a-z][a-z0-9_]*$/.test(String(modelId))) {
    throw new Error(`Invalid ModelJars catalog ID: ${modelId}`);
  }
  const reference = referenceClassName(modelId);
  return `import static org.modeljars.catalog.${reference}.MODEL;

import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.util.List;
import org.modeljars.ModelJars;

var options = SamplingOptions.builder()
    .temperature(0).maxTokens(128).build();

try (var runtime = ModelJars.openRuntime(MODEL)) {
  InferencePipeline pipeline = runtime.pipeline();
  ModelPrompt prompt = runtime.chatTemplate().render(
      List.of(ChatMessage.user("Name one JVM language.")));
  String answer = pipeline.generate(prompt, options);
}`;
}

export function embeddingJavaSnippet(modelId) {
  if (!/^[a-z][a-z0-9_]*$/.test(String(modelId))) {
    throw new Error(`Invalid ModelJars catalog ID: ${modelId}`);
  }
  const reference = referenceClassName(modelId);
  return `import static org.modeljars.catalog.${reference}.MODEL;

import org.modeljars.ModelJars;

try (var embeddings = ModelJars.openEmbedding(MODEL)) {
  float[] vector = embeddings.embed("Where is the maintenance schedule?");
}`;
}

function formatPercent(value) {
  return `${(Number(value) * 100).toFixed(1)}%`;
}

/** True for embedding evidence, which records agreement rather than latency and answer rates. */
export function isEmbeddingEvidence(qualification) {
  return Boolean(qualification) && qualification.probes !== undefined;
}

/** True for tool-calling conformance evidence rather than RAG or embedding evidence. */
export function isToolEvidence(qualification) {
  return Boolean(qualification) && qualification.structuredOutputRate !== undefined;
}

export function qualificationSummary(qualification) {
  if (!qualification) return null;
  if (isEmbeddingEvidence(qualification)) {
    return {
      label: qualificationLabel(qualification),
      backend: `${qualification.backend} ${qualification.backendVersion}`,
      workload: qualification.workload,
      probes: qualification.probes,
      embeddingDimension: qualification.embeddingDimension,
      pooling: qualification.pooling,
      normalized: qualification.normalized,
      oracle: `${qualification.oracleBackend} ${qualification.oracleVersion}`,
      minimumOracleCosine: qualification.minimumOracleCosine,
      meanOracleCosine: qualification.meanOracleCosine,
      maxNormDeviation: qualification.maxNormDeviation,
      evidenceUri: qualification.reportUri,
      evidenceSha256: qualification.reportSha256,
      qualified: qualification.qualified,
      promptTemplate: null,
      groundingPolicy: null,
      attempts: null,
      ttft: null,
      tpot: null,
      endToEnd: null,
      decode: null,
      peakRss: null,
      rawQuality: null,
      finalQuality: null,
      fallbackRate: null,
    };
  }
  if (isToolEvidence(qualification)) {
    return {
      label: qualificationLabel(qualification),
      backend: `${qualification.backend} ${qualification.backendVersion}`,
      workload: qualification.workload,
      promptTemplate: qualification.promptTemplate,
      attempts: qualification.attempts,
      passed: qualification.passed,
      structured: formatPercent(qualification.structuredOutputRate),
      selection: formatPercent(qualification.toolSelectionExactRate),
      schema: formatPercent(qualification.schemaValidityRate),
      declaredOnly: formatPercent(qualification.declaredArgumentsOnlyRate),
      arguments: formatPercent(qualification.expectedArgumentAccuracy),
      refusal: formatPercent(qualification.refusalAccuracy),
      endToEnd: formatDuration(qualification.p95EndToEndMillis),
      evidenceUri: qualification.reportUri,
      evidenceSha256: qualification.reportSha256,
      qualified: qualification.qualified,
      groundingPolicy: null,
      ttft: null,
      tpot: null,
      decode: null,
      peakRss: null,
      rawQuality: null,
      finalQuality: null,
      fallbackRate: null,
    };
  }
  return {
    label: qualificationLabel(qualification),
    backend: `${qualification.backend} ${qualification.backendVersion}`,
    workload: qualification.workload,
    promptTemplate: qualification.promptTemplate,
    groundingPolicy: qualification.groundingPolicy,
    attempts: qualification.attempts,
    ttft: formatDuration(qualification.p95TtftMillis),
    tpot: formatDuration(qualification.p95TpotMillis),
    endToEnd: formatDuration(qualification.p95EndToEndMillis),
    decode: `${qualification.p50DecodeTokensPerSecond.toFixed(1)} tok/s`,
    peakRss: formatBytes(qualification.peakRssBytes),
    rawQuality: formatPercent(qualification.rawCorrectAnswerRate),
    finalQuality: formatPercent(qualification.correctAnswerRate),
    fallbackRate: formatPercent(qualification.extractiveFallbackRate),
    evidenceUri: qualification.reportUri,
    evidenceSha256: qualification.reportSha256,
    qualified: qualification.qualified,
  };
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function safeExternalUrl(value) {
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? escapeHtml(url.href) : "#";
  } catch {
    return "#";
  }
}

function publisher(model) {
  return String(model.sourceId || "")
    .replace(/^hf:\/\//, "")
    .split("/")[0];
}

export function artifactManifest(model) {
  if (Array.isArray(model.files) && model.files.length > 0) {
    return model.files.map((file) => ({ ...file }));
  }
  let path = "model artifact";
  try {
    const candidate = new URL(model.downloadUri).pathname.split("/").pop();
    if (candidate) path = decodeURIComponent(candidate);
  } catch {
    // The integrity record remains useful even when an older descriptor lacks a parseable URL.
  }
  return [{ path, role: "model-weights", sizeBytes: model.sizeBytes, sha256: model.sha256 }];
}

export function artifactDownloadBytes(model) {
  return artifactManifest(model).reduce((total, file) => total + Number(file.sizeBytes || 0), 0);
}

function isGenerationModel(model) {
  return (model.capabilities || []).some((capability) =>
    ["chat", "generation", "text-generation"].includes(capability),
  );
}

export function resourceMemoryNote(model) {
  return isGenerationModel(model)
    ? "Memory baseline includes mapped weights and a full-precision KV cache. Backend workspace, repacking, JVM, allocator, and operating-system overhead are additional."
    : "Memory baseline covers the complete artifact bytes. Embedding working buffers, backend workspace, repacking, JVM, allocator, and operating-system overhead are additional.";
}

function dimensionRows(model, memory, downloadBytes, generationModel) {
  const dimensions = model.dimensions || {};
  const rows = [
    ["Parameters", formatParameters(dimensions.parameterCount)],
    ["Download", formatBytes(downloadBytes)],
    ["Context", dimensions.contextLength?.toLocaleString("en-US") + " tokens"],
    ["Embedding width", dimensions.embeddingLength?.toLocaleString("en-US")],
    ["Layers", dimensions.blockCount?.toLocaleString("en-US")],
    ["Attention heads", dimensions.attentionHeadCount?.toLocaleString("en-US")],
    ["KV heads", dimensions.keyValueHeadCount?.toLocaleString("en-US")],
    [
      "Memory baseline",
      memory
        ? `>= ${formatBytes(memory.minimumBytes)}${generationModel ? " at 4,096 tokens" : ""}`
        : null,
    ],
  ].filter(([, value]) => value && !String(value).startsWith("undefined"));

  return rows
    .map(
      ([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`,
    )
    .join("");
}

function renderArtifactManifest(model) {
  return artifactManifest(model)
    .map(
      (file) => `
        <tr>
          <th scope="row"><code>${escapeHtml(file.path)}</code><small>${escapeHtml(file.role)}</small></th>
          <td>${escapeHtml(formatBytes(file.sizeBytes))}</td>
          <td><code>${escapeHtml(file.sha256)}</code></td>
        </tr>`,
    )
    .join("");
}

function checkRows(profile) {
  const labels = new Map([
    ["Pinned artifact", "Revision and checksum identify immutable upstream bytes."],
    ["Complete metadata", "Runtime, architecture, dimensions, license, and location are declared."],
    ["Pure Java executed", "The catalog records successful execution through the pure-Java backend."],
  ]);
  return profile.checks
    .map(
      (check) => `
        <li>
          <span class="check-mark" aria-hidden="true">&#10003;</span>
          <span><strong>${escapeHtml(check)}</strong><small>${escapeHtml(
            labels.get(check) ||
              (check.endsWith("-request RAG qualification")
                ? "The exact artifact completed the controlled end-to-end RAG workload."
                : check.endsWith("-case tool-calling qualification")
                  ? "The exact artifact completed the controlled tool-calling conformance suite."
                : "Verified catalog evidence."),
          )}</small></span>
        </li>`,
    )
    .join("");
}

function copyBlock(label, value, language = "") {
  return `
    <div class="code-block">
      <div><span>${escapeHtml(label)}</span><button type="button" data-copy="${escapeHtml(value)}">Copy</button></div>
      <pre><code class="${escapeHtml(language)}">${escapeHtml(value)}</code></pre>
    </div>`;
}

function renderEmbeddingQualification(summary) {
  return `
    <section class="detail-section qualification-panel ${summary.qualified ? "qualified" : "rejected"}" aria-labelledby="embedding-evidence-title">
      <div class="verification-heading">
        <div>
          <p class="eyebrow">Production evidence</p>
          <h2 id="embedding-evidence-title">${escapeHtml(summary.label)}</h2>
        </div>
        <span>${escapeHtml(String(summary.probes))} probes</span>
      </div>
      <p>
        We test that Models produces the same vectors as ${escapeHtml(summary.oracle)}, for a
        pinned probe set over the same model bytes, on ${escapeHtml(summary.backend)} using
        ${escapeHtml(summary.pooling)} pooling.
      </p>
      <dl class="dimension-grid qualification-metrics">
        <div><dt>Minimum agreement</dt><dd>${escapeHtml(summary.minimumOracleCosine.toFixed(7))}</dd></div>
        <div><dt>Mean agreement</dt><dd>${escapeHtml(summary.meanOracleCosine.toFixed(7))}</dd></div>
        <div><dt>Vector width</dt><dd>${escapeHtml(String(summary.embeddingDimension))}</dd></div>
        <div><dt>Pooling</dt><dd>${escapeHtml(summary.pooling)}</dd></div>
        <div><dt>Unit length</dt><dd>${summary.normalized ? "normalized" : "raw"}</dd></div>
        <div><dt>Reference</dt><dd>${escapeHtml(summary.oracle)}</dd></div>
      </dl>
      <div class="qualification-evidence">
        <a href="${safeExternalUrl(summary.evidenceUri)}">Raw equivalence JSON &#8599;</a>
        <code>SHA-256 ${escapeHtml(summary.evidenceSha256)}</code>
      </div>
    </section>`;
}

function renderToolQualification(summary) {
  return `
    <section class="detail-section qualification-panel ${summary.qualified ? "qualified" : "rejected"}" aria-labelledby="tool-evidence-title">
      <div class="verification-heading">
        <div>
          <p class="eyebrow">Production evidence</p>
          <h2 id="tool-evidence-title">${escapeHtml(summary.label)}</h2>
        </div>
        <span>${escapeHtml(String(summary.passed))}/${escapeHtml(String(summary.attempts))} cases</span>
      </div>
      <p>
        The exact artifact ran the ${escapeHtml(summary.workload)} conformance suite in process on
        ${escapeHtml(summary.backend)} using the ${escapeHtml(summary.promptTemplate)} tool syntax.
      </p>
      <dl class="dimension-grid qualification-metrics">
        <div><dt>Structured output</dt><dd>${escapeHtml(summary.structured)}</dd></div>
        <div><dt>Tool selection</dt><dd>${escapeHtml(summary.selection)}</dd></div>
        <div><dt>Schema validity</dt><dd>${escapeHtml(summary.schema)}</dd></div>
        <div><dt>Declared arguments</dt><dd>${escapeHtml(summary.declaredOnly)}</dd></div>
        <div><dt>Argument accuracy</dt><dd>${escapeHtml(summary.arguments)}</dd></div>
        <div><dt>Refusal accuracy</dt><dd>${escapeHtml(summary.refusal)}</dd></div>
        <div><dt>End to end p95</dt><dd>${escapeHtml(summary.endToEnd)}</dd></div>
      </dl>
      <div class="qualification-evidence">
        <a href="${safeExternalUrl(summary.evidenceUri)}">Raw conformance JSON &#8599;</a>
        <code>SHA-256 ${escapeHtml(summary.evidenceSha256)}</code>
      </div>
    </section>`;
}

function renderQualification(qualification) {
  const summary = qualificationSummary(qualification);
  if (!summary) return "";
  if (isEmbeddingEvidence(qualification)) return renderEmbeddingQualification(summary);
  if (isToolEvidence(qualification)) return renderToolQualification(summary);
  return `
    <section class="detail-section qualification-panel ${summary.qualified ? "qualified" : "rejected"}" aria-labelledby="rag-evidence-title">
      <div class="verification-heading">
        <div>
          <p class="eyebrow">Production evidence</p>
          <h2 id="rag-evidence-title">${escapeHtml(summary.label)}</h2>
        </div>
        <span>${escapeHtml(summary.attempts)} requests</span>
      </div>
      <p>
        Measured through the published Models Java client on
        ${escapeHtml(summary.backend)} for the ${escapeHtml(summary.workload)} workload using
        ${escapeHtml(summary.promptTemplate)}. Final quality includes
        ${escapeHtml(summary.groundingPolicy)}; raw model quality and fallback use remain visible
        below.
      </p>
      ${
        summary.label === "Guarded RAG"
          ? `<p>
              Guarded RAG uses generated text only when citation and grounding checks pass.
              Otherwise Models returns a deterministic extract from the retrieved sources or
              abstains when the sources do not support an answer.
            </p>`
          : ""
      }
      <dl class="dimension-grid qualification-metrics">
        <div><dt>TTFT p95</dt><dd>${escapeHtml(summary.ttft)}</dd></div>
        <div><dt>TPOT p95</dt><dd>${escapeHtml(summary.tpot)}</dd></div>
        <div><dt>End to end p95</dt><dd>${escapeHtml(summary.endToEnd)}</dd></div>
        <div><dt>Decode p50</dt><dd>${escapeHtml(summary.decode)}</dd></div>
        <div><dt>Peak RSS</dt><dd>${escapeHtml(summary.peakRss)}</dd></div>
        <div><dt>Raw model quality</dt><dd>${escapeHtml(summary.rawQuality)}</dd></div>
        <div><dt>Final grounded quality</dt><dd>${escapeHtml(summary.finalQuality)}</dd></div>
        <div><dt>Extractive fallback</dt><dd>${escapeHtml(summary.fallbackRate)}</dd></div>
      </dl>
      <div class="qualification-evidence">
        <a href="${safeExternalUrl(summary.evidenceUri)}">Raw benchmark JSON &#8599;</a>
        <code>SHA-256 ${escapeHtml(summary.evidenceSha256)}</code>
      </div>
    </section>`;
}

function renderRelated(model, catalog) {
  const related = relatedModels(model, catalog, 4);
  if (!related.length) return "";
  return `
    <section class="detail-section related-section" aria-labelledby="related-title">
      <h2 id="related-title">Related models</h2>
      <div class="related-list">
        ${related
          .map(
            (candidate) => `
              <a href="/models/${encodeURIComponent(candidate.id)}/">
                <span><strong>${escapeHtml(candidate.name)}</strong><small>${escapeHtml(candidate.description)}</small></span>
                <span>${escapeHtml(candidate.quantization)} &#8594;</span>
              </a>`,
          )
          .join("")}
      </div>
    </section>`;
}

function renderModel(model, catalog) {
  const target = document.querySelector("#model-detail");
  const profile = verificationProfile(model);
  const planningContext = Math.min(4_096, model.dimensions?.contextLength || 4_096);
  const generationModel = isGenerationModel(model);
  const downloadBytes = artifactDownloadBytes(model);
  const memory = generationModel
    ? estimateMemory(model, planningContext, 2)
    : { minimumBytes: downloadBytes };
  const tags = [
    ...(model.domains || []),
    ...(model.capabilities || []),
    ...(model.tags || []),
  ];
  const qualification = primaryQualification(model);
  const alsoCompatibleWith = model.backends?.["llama.cpp"] ? ["llama.cpp"] : [];

  document.title = `${model.name} | ModelJARs.org`;
  document.querySelector('meta[name="description"]').content = model.description;
  target.innerHTML = `
    <nav class="breadcrumb" aria-label="Breadcrumb">
      <a href="/">Models</a><span>/</span><span>${escapeHtml(model.name)}</span>
    </nav>

    <div class="detail-grid">
      <div class="detail-main">
        <header class="model-identity">
          <div class="model-mark">
            <img class="model-mark-logo" src="/android-chrome-192x192.png" alt="ModelJars artifact">
          </div>
          <div>
            <div class="identity-meta">
              <span>${escapeHtml(model.format.toUpperCase())}</span>
              <span>${escapeHtml(model.quantization)}</span>
              <span>${escapeHtml(model.license)}</span>
            </div>
            <h1>${escapeHtml(model.name)}</h1>
            <p class="byline">Published from <a href="${safeExternalUrl(model.sourceUri)}">${escapeHtml(publisher(model))}</a></p>
          </div>
        </header>
        <p class="model-summary">${escapeHtml(model.description)}</p>
        <div class="detail-tags">
          ${tags.map((tag) => `<a href="/?q=${encodeURIComponent(tag)}">${escapeHtml(tag)}</a>`).join("")}
        </div>

        <section class="verification-panel ${escapeHtml(profile.level)}" aria-labelledby="verification-title">
          <div class="verification-heading">
            <div>
              <p class="eyebrow">Catalog evidence</p>
              <h2 id="verification-title">${escapeHtml(profile.label)}</h2>
            </div>
            <span>${profile.checks.length}/3 checks</span>
          </div>
          <ul>${checkRows(profile)}</ul>
        </section>

        ${renderQualification(qualification)}

        <section class="detail-section" aria-labelledby="install-title">
          <p class="eyebrow">JVM dependency</p>
          <h2 id="install-title">Install this model</h2>
          <p>
            Add the ModelJars JVM Runtime and this model to the application. The runtime brings
            the <a href="https://integrallis.github.io/models/">Integrallis Models JVM inference library</a>
            and its execution backends. The model JAR provides the generated Java reference, pinned
            model location, checksum, and qualification metadata; weights are downloaded to the
            verified local cache when first opened.
          </p>
          ${copyBlock("Gradle", gradleSnippet(model.markerCoordinate), "language-kotlin")}
          ${copyBlock("Maven", mavenSnippet(model.markerCoordinate), "language-xml")}
        </section>

        <section class="detail-section" aria-labelledby="run-title">
          <p class="eyebrow">In-process inference</p>
          <h2 id="run-title">Open and run the model</h2>
          <p>
            The generated catalog reference pins this exact artifact. ModelJars selects its
            qualified backend, installs and verifies the weights in the content-addressed cache,
            and applies a performance profile when the current JVM and hardware match one.
          </p>
          ${copyBlock(
            "Java",
            isEmbeddingEvidence(qualification)
              ? embeddingJavaSnippet(model.id)
              : javaSnippet(model.id),
            "language-java",
          )}
        </section>

        <section class="detail-section" aria-labelledby="contents-title">
          <p class="eyebrow">Descriptor</p>
          <h2 id="contents-title">What is inside</h2>
          <dl class="dimension-grid">${dimensionRows(model, memory, downloadBytes, generationModel)}</dl>
          <p class="resource-note">
            ${escapeHtml(resourceMemoryNote(model))}
          </p>
        </section>

        <section class="detail-section" aria-labelledby="integrity-title">
          <p class="eyebrow">Reproducibility</p>
          <h2 id="integrity-title">Artifact integrity</h2>
          <dl class="integrity-list">
            <div><dt>Source</dt><dd><a href="${safeExternalUrl(model.sourceUri)}">${escapeHtml(model.sourceId)}</a></dd></div>
            <div><dt>Revision</dt><dd><code>${escapeHtml(model.revision)}</code></dd></div>
          </dl>
          <div class="table-scroll" tabindex="0" aria-label="Complete artifact manifest">
            <table class="benchmark-table artifact-manifest">
              <thead><tr><th scope="col">File</th><th scope="col">Size</th><th scope="col">SHA-256</th></tr></thead>
              <tbody>${renderArtifactManifest(model)}</tbody>
            </table>
          </div>
        </section>

        ${renderRelated(model, catalog)}
      </div>

      <aside class="detail-sidebar" aria-label="Model summary">
        <div class="sidebar-panel">
          <a class="primary-button" href="${safeExternalUrl(model.sourceUri)}">View source &#8599;</a>
          <button class="secondary-button full-width" type="button" data-copy="${escapeHtml(model.markerCoordinate)}">Copy coordinate</button>
        </div>
        <div class="sidebar-panel">
          <h2>Model facts</h2>
          <dl class="sidebar-facts">
            <div><dt>Parameters</dt><dd>${escapeHtml(formatParameters(model.dimensions?.parameterCount))}</dd></div>
            <div><dt>Download</dt><dd>${escapeHtml(formatBytes(downloadBytes))}</dd></div>
            <div><dt>Size class</dt><dd>${escapeHtml(sizeTier(model).replace("-", " "))}</dd></div>
            <div><dt>Architecture</dt><dd>${escapeHtml(model.architecture)}</dd></div>
            <div><dt>Version</dt><dd>${escapeHtml(model.modelVersion)}</dd></div>
          </dl>
        </div>
        <div class="sidebar-panel">
          <h2>Qualified Models runtime</h2>
          <ul class="backend-list">
            <li><span class="status-dot"></span>Models ${escapeHtml(qualification.backend)}</li>
          </ul>
        </div>
        ${
          alsoCompatibleWith.length
            ? `<div class="sidebar-panel">
                <h2>Also compatible with</h2>
                <ul class="backend-list">
                  ${alsoCompatibleWith
                    .map(
                      (backend) =>
                        `<li><span class="status-dot secondary"></span>${escapeHtml(backend)} <small>third-party runtime</small></li>`,
                    )
                    .join("")}
                </ul>
              </div>`
            : ""
        }
        <div class="sidebar-panel">
          <h2>Coordinate</h2>
          <code class="coordinate">${escapeHtml(model.markerCoordinate)}</code>
        </div>
      </aside>
    </div>`;
}

async function loadDetail() {
  const target = document.querySelector("#model-detail");
  try {
    const response = await fetch("/catalog.json");
    if (!response.ok) throw new Error(`Catalog request failed: ${response.status}`);
    const payload = await response.json();
    const catalog = Array.isArray(payload) ? payload : payload.models || [];
    const id = modelIdFromPath(window.location.pathname) || new URLSearchParams(location.search).get("id");
    const model = catalog.find((candidate) => candidate.id === id);
    if (!model) {
      target.innerHTML = `<div class="not-found"><p class="eyebrow">404</p><h1>Model not found</h1><p>This model is not present in the current catalog.</p><a class="primary-button" href="/">Browse models</a></div>`;
      return;
    }
    renderModel(model, catalog);
  } catch (error) {
    target.innerHTML = `<div class="not-found"><h1>Catalog unavailable</h1><p>${escapeHtml(error.message)}</p><a href="/">Return to the catalog</a></div>`;
  }
}

if (typeof document !== "undefined") {
  initializeTheme(document.querySelector("#theme-toggle"));
  document.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-copy]");
    if (!button) return;
    await navigator.clipboard.writeText(button.dataset.copy);
    const original = button.textContent;
    button.textContent = "Copied";
    setTimeout(() => {
      button.textContent = original;
    }, 1_200);
  });
  loadDetail();
}
