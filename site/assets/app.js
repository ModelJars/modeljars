import { qualificationMetrics } from "./catalog-entry-metrics.js";
import { renderDependencyCopyActions } from "./catalog-entry-actions.js";
import { copyDependencySnippet } from "./dependency-snippets.js";
import { describeCoordinate } from "./coordinate.js";
import { formatBytes, formatParameters } from "./resource-profile.js";
import { primaryQualification } from "./qualification-data.js";
import { filterCatalog } from "./search.js";
import { buildFacets, kindProfile, sizeTier, verificationProfile } from "./taxonomy.js";
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
  if (model.kind === "hybrid") return "ModelJars";
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
  const kind = kindProfile(model);
  // The badge carries the group; the coordinate then shows only what differs row to row. The copy
  // buttons still emit the full coordinate, because a shortened one does not resolve in a build.
  const coordinate = describeCoordinate(model.markerCoordinate);
  const tags = [
    ...(model.domains || []),
    ...(model.capabilities || []).slice(0, 2),
  ].slice(0, 4);
  const qualification = primaryQualification(model);
  const modelsBackend = qualification?.backend;
  const evidenceMetrics = qualificationMetrics(qualification);
  const downloadBytes = model.kind === "hybrid" ? model.requiredWeightBytes : model.sizeBytes;

  return `
    <details class="catalog-entry">
      <summary class="entry-summary">
        <span class="entry-identity">
          <a class="entry-title" href="${detailPath(model)}">${escapeHtml(model.name)}</a>
          <span class="publisher">by ${escapeHtml(publisher(model))}</span>
        </span>
        <span class="entry-pills">
          ${kind ? `<span class="kind-badge ${escapeHtml(kind.kind)}">${escapeHtml(kind.label)}</span>` : ""}
          <span class="verification-badge ${escapeHtml(profile.level)}">${escapeHtml(profile.label)}</span>
        </span>
        <span class="entry-coordinate-row">
          ${coordinate.label ? `<span class="source-badge">${escapeHtml(coordinate.label)}</span>` : ""}
          <code class="entry-coordinate" title="${escapeHtml(coordinate.full)}">${escapeHtml(coordinate.short)}</code>
          ${renderDependencyCopyActions(model)}
        </span>
      </summary>
      <div class="entry-body">
        <div class="entry-main">
          <p class="entry-description">${escapeHtml(model.description)}</p>
          <div class="entry-tags">
            ${tags.map((tag) => `<button type="button" data-search="${escapeHtml(tag)}">${escapeHtml(tag)}</button>`).join("")}
          </div>
        </div>
        <div class="entry-facts" aria-label="Model properties">
          ${evidenceMetrics.map(({ label, value }) => metric(label, value)).join("")}
          ${metric("parameters", formatParameters(dimensions.parameterCount))}
          ${metric(model.kind === "hybrid" ? "member weights" : "download", formatBytes(downloadBytes))}
          ${metric("", context)}
          ${metric("", model.quantization)}
        </div>
        <div class="entry-runtime">
          <span>${escapeHtml(model.architecture)}</span>
          ${modelsBackend ? `<span class="runtime-label">Models ${escapeHtml(modelsBackend)}</span>` : ""}
          <a class="entry-arrow" href="${detailPath(model)}" aria-label="View ${escapeHtml(model.name)}">&#8594;</a>
        </div>
      </div>
    </details>`;
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

// Categories the catalogue leads with regardless of how many entries carry them. The domain facet
// is ordered by count and then cut to nine, which is right for discovering what the catalogue is
// mostly made of and wrong for a category that exists on purpose: System One has two entries and
// would sort below "translation". A deliberate category that nobody can see is not a category.
const FEATURED_DOMAINS = ["system-1"];

function renderDomainFilters(facets) {
  const byCount = facets.domains.slice(0, 9);
  const featured = FEATURED_DOMAINS.flatMap((value) => {
    const facet = facets.domains.find((candidate) => candidate.value === value);
    // Absent from the catalogue entirely means nothing to filter, so show nothing rather than a
    // button that returns an empty list.
    return facet && !byCount.some((shown) => shown.value === value) ? [facet] : [];
  });
  const visibleDomains = [...featured, ...byCount];
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
    `${numberFormat.format(filtered.length)} qualified entr${filtered.length === 1 ? "y" : "ies"}`;
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

  // A catalog row is a disclosure, and its summary carries a link to the detail page and the two
  // dependency copy buttons. Activating either must do its own job, not toggle the row open.
  document.addEventListener("click", (event) => {
    const summary = event.target.closest(".entry-summary");
    if (!summary) return;
    if (event.target.closest("a, button")) {
      event.preventDefault();
      const link = event.target.closest("a");
      if (link && !event.target.closest("button")) {
        window.location.href = link.href;
      }
    }
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
    catalog.push(...models);
    const facets = buildFacets(catalog);

    populateSelect(elements.backend, facets.backends);
    populateSelect(elements.architecture, facets.architectures);
    populateSelect(elements.size, facets.sizes, (value) => value.replace("-", " "));

    document.querySelector("#model-total").textContent = numberFormat.format(
      catalog.filter((model) => model.kind !== "hybrid").length,
    );
    document.querySelector("#hybrid-total").textContent = numberFormat.format(
      catalog.filter((model) => model.kind === "hybrid").length,
    );
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
