import { formatDuration } from "./benchmark-data.js";
import { primaryQualification, qualificationLabel } from "./qualification-data.js";
import { estimateMemory, formatBytes, formatParameters } from "./resource-profile.js";
import { relatedModels, sizeTier, verificationProfile } from "./taxonomy.js";
import { initializeTheme } from "./theme.js";

export function modelIdFromPath(pathname) {
  const match = String(pathname).match(/^\/models\/([^/]+)(?:\/index\.html)?\/?$/);
  return match ? decodeURIComponent(match[1]) : null;
}

function coordinateParts(coordinate) {
  const parts = String(coordinate || "").split(":");
  if (parts.length !== 3 || parts.some((part) => !part)) {
    throw new Error(`Invalid Maven coordinate: ${coordinate}`);
  }
  return parts;
}

export function gradleSnippet(coordinate) {
  coordinateParts(coordinate);
  return `runtimeOnly("${coordinate}")`;
}

export function mavenSnippet(coordinate) {
  const [groupId, artifactId, version] = coordinateParts(coordinate);
  return `<dependency>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
  <scope>runtime</scope>
</dependency>`;
}

function formatPercent(value) {
  return `${(Number(value) * 100).toFixed(1)}%`;
}

export function qualificationSummary(qualification) {
  if (!qualification) return null;
  return {
    label: qualificationLabel(qualification),
    backend: `${qualification.backend} ${qualification.backendVersion}`,
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

function dimensionRows(model, memory) {
  const dimensions = model.dimensions || {};
  const rows = [
    ["Parameters", formatParameters(dimensions.parameterCount)],
    ["Download", formatBytes(model.sizeBytes)],
    ["Context", dimensions.contextLength?.toLocaleString("en-US") + " tokens"],
    ["Embedding width", dimensions.embeddingLength?.toLocaleString("en-US")],
    ["Layers", dimensions.blockCount?.toLocaleString("en-US")],
    ["Attention heads", dimensions.attentionHeadCount?.toLocaleString("en-US")],
    ["KV heads", dimensions.keyValueHeadCount?.toLocaleString("en-US")],
    ["Memory baseline", memory ? `>= ${formatBytes(memory.minimumBytes)} at 4,096 tokens` : null],
  ].filter(([, value]) => value && !String(value).startsWith("undefined"));

  return rows
    .map(
      ([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`,
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

function renderQualification(qualification) {
  const summary = qualificationSummary(qualification);
  if (!summary) return "";
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
        ${escapeHtml(summary.backend)}. Final quality includes the declared grounding policy;
        raw model quality and fallback use remain visible below.
      </p>
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
  const memory = estimateMemory(model, planningContext, 2);
  const tags = [
    ...(model.domains || []),
    ...(model.capabilities || []),
    ...(model.tags || []),
  ];
  const supportedBackends = Object.entries(model.backends || {}).filter(([, supported]) => supported);
  const qualification = primaryQualification(model);

  document.title = `${model.name} | ModelJars`;
  document.querySelector('meta[name="description"]').content = model.description;
  target.innerHTML = `
    <nav class="breadcrumb" aria-label="Breadcrumb">
      <a href="/">Models</a><span>/</span><span>${escapeHtml(model.name)}</span>
    </nav>

    <div class="detail-grid">
      <div class="detail-main">
        <header class="model-identity">
          <div class="model-mark" aria-hidden="true">${escapeHtml(model.name.charAt(0))}</div>
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
          <h2 id="install-title">Install this marker</h2>
          <p>
            Add the ModelJars facade and this marker to the application runtime. The marker records
            the pinned model location and checksum; weights are resolved separately.
          </p>
          ${copyBlock("Gradle", gradleSnippet(model.markerCoordinate), "language-kotlin")}
          ${copyBlock("Maven", mavenSnippet(model.markerCoordinate), "language-xml")}
        </section>

        <section class="detail-section" aria-labelledby="contents-title">
          <p class="eyebrow">Descriptor</p>
          <h2 id="contents-title">What is inside</h2>
          <dl class="dimension-grid">${dimensionRows(model, memory)}</dl>
          <p class="resource-note">
            Memory baseline includes mapped weights and a full-precision KV cache. Backend
            workspace, repacking, JVM, allocator, and operating-system overhead are additional.
          </p>
        </section>

        <section class="detail-section" aria-labelledby="integrity-title">
          <p class="eyebrow">Reproducibility</p>
          <h2 id="integrity-title">Artifact integrity</h2>
          <dl class="integrity-list">
            <div><dt>Source</dt><dd><a href="${safeExternalUrl(model.sourceUri)}">${escapeHtml(model.sourceId)}</a></dd></div>
            <div><dt>Revision</dt><dd><code>${escapeHtml(model.revision)}</code></dd></div>
            <div><dt>SHA-256</dt><dd><code>${escapeHtml(model.sha256)}</code></dd></div>
            <div><dt>Local path</dt><dd><code>${escapeHtml(model.localPath || model.classpathResource)}</code></dd></div>
          </dl>
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
            <div><dt>Download</dt><dd>${escapeHtml(formatBytes(model.sizeBytes))}</dd></div>
            <div><dt>Size class</dt><dd>${escapeHtml(sizeTier(model).replace("-", " "))}</dd></div>
            <div><dt>Architecture</dt><dd>${escapeHtml(model.architecture)}</dd></div>
            <div><dt>Version</dt><dd>${escapeHtml(model.modelVersion)}</dd></div>
          </dl>
        </div>
        <div class="sidebar-panel">
          <h2>Available in</h2>
          <ul class="backend-list">
            ${supportedBackends
              .map(
                ([backend]) => `<li><span class="status-dot"></span>${escapeHtml(backend)}</li>`,
              )
              .join("")}
          </ul>
        </div>
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
