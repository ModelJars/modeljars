import { matches, normalize } from "./search.js";
import { estimateMemory, formatBytes, formatParameters } from "./resource-profile.js";

const catalog = [];

const searchInput = document.querySelector("#catalog-search");
const backendFilter = document.querySelector("#backend-filter");
const resultCount = document.querySelector("#result-count");
const results = document.querySelector("#catalog-results");
const emptyState = document.querySelector("#empty-state");
const numberFormat = new Intl.NumberFormat("en-US");

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderCard(model) {
  const dimensions = model.dimensions || {};
  const planningContext = Math.min(4_096, dimensions.contextLength || 4_096);
  const memory = estimateMemory(model, planningContext, 2);
  const dimensionParts = [
    dimensions.embeddingLength && `${numberFormat.format(dimensions.embeddingLength)} width`,
    dimensions.blockCount && `${numberFormat.format(dimensions.blockCount)} blocks`,
    dimensions.attentionBlockCount &&
      dimensions.attentionBlockCount !== dimensions.blockCount &&
      `${numberFormat.format(dimensions.attentionBlockCount)} attention blocks`,
    dimensions.attentionHeadCount &&
      `${numberFormat.format(dimensions.attentionHeadCount)} heads`,
    dimensions.keyValueHeadCount &&
      `${numberFormat.format(dimensions.keyValueHeadCount)} KV heads`,
  ].filter(Boolean);
  const capabilities = model.capabilities
    .map((capability) => `<span class="pill">${escapeHtml(capability)}</span>`)
    .join("");
  const backends = Object.entries(model.backends)
    .filter(([, supported]) => supported)
    .map(([backend]) => `<span class="pill accent">${escapeHtml(backend)}</span>`)
    .join("");
  const locationLabel = model.classpathResource ? "Classpath Resource" : "Local Path";
  const location = model.classpathResource || model.localPath;
  const parameterCount = dimensions.parameterCount;

  return `
    <article class="model-card">
      <div>
        <p class="eyebrow">${escapeHtml(model.format.toUpperCase())} · ${escapeHtml(model.quantization)}</p>
        <h3>${escapeHtml(model.name)}</h3>
        <p>${escapeHtml(model.description)}</p>
      </div>
      <dl class="metadata">
        <div><dt>Source</dt><dd><a href="${escapeHtml(model.sourceUri)}">${escapeHtml(model.sourceId)}</a></dd></div>
        <div><dt>Coordinate</dt><dd><code>${escapeHtml(model.markerCoordinate)}</code></dd></div>
        <div><dt>Architecture</dt><dd>${escapeHtml(model.architecture)}</dd></div>
        ${parameterCount ? `<div><dt>Parameters</dt><dd title="${numberFormat.format(parameterCount)} parameters">${formatParameters(parameterCount)}</dd></div>` : ""}
        ${dimensions.contextLength ? `<div><dt>Context</dt><dd>${numberFormat.format(dimensions.contextLength)} tokens</dd></div>` : ""}
        ${dimensionParts.length ? `<div><dt>Dimensions</dt><dd>${escapeHtml(dimensionParts.join(" / "))}</dd></div>` : ""}
        <div><dt>Disk</dt><dd>${formatBytes(model.sizeBytes)}</dd></div>
        ${memory ? `<div><dt>Memory baseline</dt><dd title="Weights plus FP16 KV cache. Backend workspace, repacking, allocator, JVM, and OS overhead are excluded.">&ge; ${formatBytes(memory.minimumBytes)} at ${numberFormat.format(planningContext)} tokens</dd></div>` : ""}
        <div><dt>${locationLabel}</dt><dd><code>${escapeHtml(location)}</code></dd></div>
      </dl>
      <div class="pills">${capabilities}${backends}</div>
      <button class="copy-button" type="button" data-copy="${escapeHtml(model.markerCoordinate)}">
        Copy coordinate
      </button>
    </article>
  `;
}

function render() {
  const query = normalize(searchInput.value.trim());
  const backend = backendFilter.value;
  const filtered = catalog.filter((model) => matches(model, query, backend));

  results.innerHTML = filtered.map(renderCard).join("");
  resultCount.textContent = `${filtered.length} model${filtered.length === 1 ? "" : "s"}`;
  emptyState.hidden = filtered.length > 0;
}

async function loadCatalog() {
  const response = await fetch("/catalog.json");
  catalog.push(...(await response.json()));
  render();
}

document.addEventListener("click", async (event) => {
  const target = event.target;
  if (!(target instanceof HTMLButtonElement) || !target.dataset.copy) {
    return;
  }
  await navigator.clipboard.writeText(target.dataset.copy);
  const original = target.textContent;
  target.textContent = "Copied";
  setTimeout(() => {
    target.textContent = original;
  }, 1200);
});

searchInput.addEventListener("input", render);
backendFilter.addEventListener("change", render);

loadCatalog();
