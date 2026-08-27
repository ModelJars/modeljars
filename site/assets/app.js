import { formatDuration } from "./benchmark-data.js";
import { renderDependencyCopyActions } from "./catalog-entry-actions.js";
import { copyDependencySnippet } from "./dependency-snippets.js";
import { formatBytes, formatParameters } from "./resource-profile.js";
import { primaryQualification } from "./qualification-data.js";
import { filterCatalog } from "./search.js";
import { buildFacets, sizeTier, verificationProfile } from "./taxonomy.js";
import { initializeTheme } from "./theme.js";

const catalog = [];
const state = {
  query: "",
  domain: "",
  backend: "",
  architecture: "",
  size: "",
  qualification: "",
  sort: "name",
};

const elements = {
  search: document.querySelector("#catalog-search"),
  domains: document.querySelector("#domain-filters"),
  backend: document.querySelector("#backend-filter"),
  architecture: document.querySelector("#architecture-filter"),
  size: document.querySelector("#size-filter"),
  qualification: document.querySelector("#qualification-filter"),
  sort: document.querySelector("#sort-filter"),
  results: document.querySelector("#catalog-results"),
  resultCount: document.querySelector("#result-count"),
  emptyState: document.querySelector("#empty-state"),
  advanced: document.querySelector("#advanced-filters"),
  filterToggle: document.querySelector("#filter-toggle"),
  activeFilterCount: document.querySelector("#active-filter-count"),
};

const numberFormat = new Intl.NumberFormat("en-US");

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function publisher(model) {
  return String(model.sourceId || "")
    .replace(/^hf:\/\//, "")
    .split("/")[0];
}

function detailPath(model) {
  return `/models/${encodeURIComponent(model.id)}/`;
}

function metric(label, value) {
  if (!value) return "";
  return `<span><strong>${escapeHtml(value)}</strong> ${escapeHtml(label)}</span>`;
}

function renderEntry(model) {
  const dimensions = model.dimensions || {};
  const context = dimensions.contextLength
    ? `${numberFormat.format(dimensions.contextLength)} ctx`
    : null;
  const profile = verificationProfile(model);
  const tags = [
    ...(model.domains || []),
    ...(model.capabilities || []).slice(0, 2),
  ].slice(0, 4);
  const qualification = primaryQualification(model);
  const modelsBackend = qualification?.backend;
  // Latency and decode rate exist only on RAG evidence; agreement and width only on embedding
  // evidence. Reading one from the other throws inside the formatters and blanks the catalog.
  const ragEvidence =
    qualification?.qualified && qualification.p95TtftMillis !== undefined ? qualification : null;
  const embeddingEvidence =
    qualification?.qualified && qualification.probes !== undefined ? qualification : null;
  const toolEvidence =
    qualification?.qualified && qualification.structuredOutputRate !== undefined
      ? qualification
      : null;

  return `
    <article class="catalog-entry">
      ${renderDependencyCopyActions(model)}
      <div class="entry-main">
        <div class="entry-title-row">
          <div>
            <a class="entry-title" href="${detailPath(model)}">${escapeHtml(model.name)}</a>
            <span class="publisher">by ${escapeHtml(publisher(model))}</span>
          </div>
          <span class="verification-badge ${escapeHtml(profile.level)}">${escapeHtml(profile.label)}</span>
        </div>
        <p class="entry-description">${escapeHtml(model.description)}</p>
        <div class="entry-tags">
          ${tags.map((tag) => `<button type="button" data-search="${escapeHtml(tag)}">${escapeHtml(tag)}</button>`).join("")}
        </div>
      </div>
      <div class="entry-facts" aria-label="Model properties">
        ${ragEvidence ? metric("TTFT p95", formatDuration(ragEvidence.p95TtftMillis)) : ""}
        ${ragEvidence ? metric("decode", `${ragEvidence.p50DecodeTokensPerSecond.toFixed(1)} tok/s`) : ""}
        ${embeddingEvidence ? metric("agreement", embeddingEvidence.minimumOracleCosine.toFixed(5)) : ""}
        ${embeddingEvidence ? metric("dimensions", String(embeddingEvidence.embeddingDimension)) : ""}
        ${toolEvidence ? metric("tool selection", `${(toolEvidence.toolSelectionExactRate * 100).toFixed(1)}%`) : ""}
        ${toolEvidence ? metric("arguments", `${(toolEvidence.expectedArgumentAccuracy * 100).toFixed(1)}%`) : ""}
        ${metric("parameters", formatParameters(dimensions.parameterCount))}
        ${metric("download", formatBytes(model.sizeBytes))}
        ${metric("", context)}
        ${metric("", model.quantization)}
      </div>
      <div class="entry-runtime">
        <span>${escapeHtml(model.architecture)}</span>
        ${modelsBackend ? `<span class="runtime-label">Models ${escapeHtml(modelsBackend)}</span>` : ""}
        <a class="entry-arrow" href="${detailPath(model)}" aria-label="View ${escapeHtml(model.name)}">&#8594;</a>
      </div>
    </article>`;
}

function activeFilterCount() {
  return [
    state.domain,
    state.backend,
    state.architecture,
    state.size,
    state.qualification,
  ].filter(Boolean).length;
}

function renderDomainFilters(facets) {
  const visibleDomains = facets.domains.slice(0, 9);
  elements.domains.innerHTML = [
    { value: "", count: catalog.length, label: "All" },
    ...visibleDomains.map((facet) => ({ ...facet, label: facet.value })),
  ]
    .map(
      ({ value, count, label }) => `
        <button type="button" data-domain="${escapeHtml(value)}" aria-pressed="${state.domain === value}">
          ${escapeHtml(label)} <span>${numberFormat.format(count)}</span>
        </button>`,
    )
    .join("");
}

function populateSelect(select, facets, format = (value) => value) {
  const first = select.options[0];
  select.replaceChildren(first);
  for (const { value, count } of facets) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = `${format(value)} (${count})`;
    select.append(option);
  }
}

function render() {
  const filtered = filterCatalog(catalog, state);
  elements.results.innerHTML = filtered.map(renderEntry).join("");
  elements.results.setAttribute("aria-busy", "false");
  elements.resultCount.textContent =
    `${numberFormat.format(filtered.length)} qualified artifact${filtered.length === 1 ? "" : "s"}`;
  elements.emptyState.hidden = filtered.length > 0;

  const count = activeFilterCount();
  elements.activeFilterCount.hidden = count === 0;
  elements.activeFilterCount.textContent = count;
  renderDomainFilters(buildFacets(catalog));
}

function clearFilters() {
  Object.assign(state, {
    query: "",
    domain: "",
    backend: "",
    architecture: "",
    size: "",
    qualification: "",
    sort: "name",
  });
  elements.search.value = "";
  elements.backend.value = "";
  elements.architecture.value = "";
  elements.size.value = "";
  elements.qualification.value = "";
  elements.sort.value = "name";
  render();
}

function bindControls() {
  elements.search.addEventListener("input", () => {
    state.query = elements.search.value;
    render();
  });
  elements.backend.addEventListener("change", () => {
    state.backend = elements.backend.value;
    render();
  });
  elements.architecture.addEventListener("change", () => {
    state.architecture = elements.architecture.value;
    render();
  });
  elements.size.addEventListener("change", () => {
    state.size = elements.size.value;
    render();
  });
  elements.qualification.addEventListener("change", () => {
    state.qualification = elements.qualification.value;
    render();
  });
  elements.sort.addEventListener("change", () => {
    state.sort = elements.sort.value;
    render();
  });
  elements.filterToggle.addEventListener("click", () => {
    const open = elements.advanced.hidden;
    elements.advanced.hidden = !open;
    elements.filterToggle.setAttribute("aria-expanded", String(open));
  });

  document.addEventListener("click", async (event) => {
    const copyButton = event.target.closest("[data-build-tool][data-coordinate]");
    const domainButton = event.target.closest("[data-domain]");
    const searchButton = event.target.closest("[data-search]");
    if (copyButton) {
      const originalLabel = copyButton.getAttribute("aria-label");
      const originalTitle = copyButton.title;
      try {
        await copyDependencySnippet(
          copyButton.dataset.buildTool,
          copyButton.dataset.coordinate,
        );
        copyButton.classList.add("copied");
        copyButton.setAttribute("aria-label", `${originalTitle} copied`);
        copyButton.title = "Copied";
      } catch {
        copyButton.classList.add("copy-failed");
        copyButton.setAttribute("aria-label", `${originalTitle} failed`);
        copyButton.title = "Copy failed";
      }
      setTimeout(() => {
        copyButton.classList.remove("copied", "copy-failed");
        copyButton.setAttribute("aria-label", originalLabel);
        copyButton.title = originalTitle;
      }, 1_200);
    } else if (domainButton) {
      state.domain = domainButton.dataset.domain;
      render();
    } else if (searchButton) {
      state.query = searchButton.dataset.search;
      elements.search.value = state.query;
      elements.search.focus();
      render();
    } else if (event.target.closest("#clear-filters, [data-clear-filters]")) {
      clearFilters();
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "/" && !["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement.tagName)) {
      event.preventDefault();
      elements.search.focus();
    }
  });
}

async function loadCatalog() {
  try {
    const response = await fetch("/catalog.json");
    if (!response.ok) throw new Error(`Catalog request failed: ${response.status}`);
    const payload = await response.json();
    const models = Array.isArray(payload) ? payload : payload.models || [];
    if (models.some((model) => !primaryQualification(model)?.qualified)) {
      throw new Error("Public catalog contains an artifact without production qualification");
    }
    catalog.push(...models);
    const facets = buildFacets(catalog);

    populateSelect(elements.backend, facets.backends);
    populateSelect(elements.architecture, facets.architectures);
    populateSelect(elements.size, facets.sizes, (value) => value.replace("-", " "));

    document.querySelector("#model-total").textContent = numberFormat.format(catalog.length);
    document.querySelector("#identity-total").textContent = numberFormat.format(
      new Set(catalog.map((model) => model.sourceId)).size,
    );
    document.querySelector("#pure-java-total").textContent = numberFormat.format(
      catalog.filter((model) => primaryQualification(model)?.backend === "pure-java").length,
    );
    document.querySelector("#publisher-total").textContent = numberFormat.format(
      new Set(catalog.map(publisher)).size,
    );
    render();
  } catch (error) {
    elements.results.setAttribute("aria-busy", "false");
    elements.resultCount.textContent = "Catalog unavailable";
    elements.results.innerHTML = `<p class="error-state">${escapeHtml(error.message)}</p>`;
  }
}

initializeTheme(document.querySelector("#theme-toggle"));
bindControls();
loadCatalog();
