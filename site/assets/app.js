const catalog = [];

const searchInput = document.querySelector("#catalog-search");
const backendFilter = document.querySelector("#backend-filter");
const resultCount = document.querySelector("#result-count");
const results = document.querySelector("#catalog-results");
const emptyState = document.querySelector("#empty-state");

function normalize(value) {
  return String(value || "").toLowerCase();
}

function matches(model, query, backend) {
  const text = [
    model.name,
    model.sourceId,
    model.markerCoordinate,
    model.architecture,
    model.format,
    model.quantization,
    model.packaging,
    model.language,
    model.topology,
    model.capabilities.join(" "),
    (model.features || []).join(" "),
    Object.keys(model.backends).join(" ")
  ].join(" ");

  const queryMatches = !query || normalize(text).includes(query);
  const backendMatches = !backend || model.backends[backend] === true;
  return queryMatches && backendMatches;
}

function renderCard(model) {
  const capabilities = model.capabilities
    .map((capability) => `<span class="pill">${capability}</span>`)
    .join("");
  const backends = Object.entries(model.backends)
    .filter(([, supported]) => supported)
    .map(([backend]) => `<span class="pill accent">${backend}</span>`)
    .join("");
  const locationLabel = model.classpathResource ? "Classpath Resource" : "Local Path";
  const location = model.classpathResource || model.localPath;

  return `
    <article class="model-card">
      <div>
        <p class="eyebrow">${model.format.toUpperCase()} · ${model.quantization}</p>
        <h3>${model.name}</h3>
        <p>${model.description}</p>
      </div>
      <dl class="metadata">
        <div><dt>Source</dt><dd><a href="${model.sourceUri}">${model.sourceId}</a></dd></div>
        <div><dt>Coordinate</dt><dd><code>${model.markerCoordinate}</code></dd></div>
        <div><dt>${locationLabel}</dt><dd><code>${location}</code></dd></div>
      </dl>
      <div class="pills">${capabilities}${backends}</div>
      <button class="copy-button" type="button" data-copy="${model.markerCoordinate}">
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
