import { validateBenchmarkCatalog } from "./benchmark-data.js";
import {
  buildQualificationRows,
  validateQualificationCatalog,
} from "./qualification-data.js";
import {
  buildBenchmarkSummary,
  buildInferenceRows,
  buildRagRows,
} from "./benchmark-view.js";
import { initializeTheme } from "./theme.js";

const elements = {
  publishedAt: document.querySelector("#published-at"),
  environment: document.querySelector("#environment-summary"),
  summary: document.querySelector("#benchmark-summary"),
  inferenceBody: document.querySelector("#inference-table-body"),
  qualificationBody: document.querySelector("#qualification-table-body"),
  qualificationSummary: document.querySelector("#qualification-summary"),
  ragBody: document.querySelector("#rag-table-body"),
  ragWorkload: document.querySelector("#rag-workload"),
  status: document.querySelector("#benchmark-status"),
};

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function evidenceCell(evidence) {
  return `
    <a
      class="evidence-link"
      href="${escapeHtml(evidence.url)}"
      title="SHA-256 ${escapeHtml(evidence.sha256)}">
      Raw JSON
    </a>
    <code class="evidence-hash">${escapeHtml(evidence.sha256.slice(0, 12))}...</code>`;
}

function renderSummary(summary) {
  const values = [
    [summary.modelCount, "measured models"],
    [summary.prefillVsLlamaCpp, "Java prompt vs llama.cpp"],
    [summary.prefillVsOllama, "Java prompt vs Ollama"],
    [summary.decodeVsLlamaCpp, "Java decode vs llama.cpp"],
    [summary.decodeVsOllama, "Java decode vs Ollama"],
  ];
  elements.summary.innerHTML = values
    .map(
      ([value, label]) => `
        <div>
          <strong>${escapeHtml(value)}</strong>
          <span>${escapeHtml(label)}</span>
        </div>`,
    )
    .join("");
}

function renderInferenceRows(rows) {
  elements.inferenceBody.innerHTML = rows
    .map((row) => {
      const java = row.engines["pure-java"];
      const llama = row.engines["llama.cpp"];
      const ollama = row.engines.ollama;
      return `
        <tr id="${escapeHtml(row.modelId)}">
          <th class="model-result" scope="row">
            <a href="${escapeHtml(row.modelUrl)}">${escapeHtml(row.modelName)}</a>
            <small>Java tier: ${escapeHtml(java.performanceTier.toLowerCase())}</small>
          </th>
          <td class="text-cell">
            <span class="result-status ${escapeHtml(row.statusId)}">${escapeHtml(row.status)}</span>
          </td>
          <td>${escapeHtml(java.ttft)}</td>
          <td>${escapeHtml(llama.ttft)}</td>
          <td>${escapeHtml(ollama.ttft)}</td>
          <td>${escapeHtml(java.prefill)}</td>
          <td>${escapeHtml(llama.prefill)}</td>
          <td>${escapeHtml(ollama.prefill)}</td>
          <td>${escapeHtml(java.decode)}</td>
          <td>${escapeHtml(llama.decode)}</td>
          <td>${escapeHtml(ollama.decode)}</td>
          <td>${escapeHtml(row.decodeVsLlamaCpp)}</td>
          <td>${escapeHtml(row.decodeVsOllama)}</td>
          <td class="evidence-cell">${evidenceCell(row.evidence)}</td>
        </tr>`;
    })
    .join("");
}

function renderRagRows(rows) {
  elements.ragBody.innerHTML = rows
    .map(
      (row) => `
        <tr>
          <th class="model-result" scope="row">
            <strong>${escapeHtml(row.engine)}</strong>
            <small>${escapeHtml(row.model)}</small>
          </th>
          <td class="text-cell">${escapeHtml(row.execution)}</td>
          <td>${escapeHtml(row.retrieval)}</td>
          <td>${escapeHtml(row.ttft)}</td>
          <td>${escapeHtml(row.tpot)}</td>
          <td>${escapeHtml(row.endToEnd)}</td>
          <td>${escapeHtml(row.decode)}</td>
          <td>${escapeHtml(row.strictQuality)}</td>
          <td>${escapeHtml(row.auditedSemanticQuality)}</td>
          <td class="cost-cell">
            ${escapeHtml(row.cost)}
            ${row.costDetail ? `<small>${escapeHtml(row.costDetail)}</small>` : ""}
          </td>
          <td>
            <span class="egress-status ${row.dataEgress === "Yes" ? "external" : "local"}">
              ${escapeHtml(row.dataEgress)}
            </span>
          </td>
          <td class="evidence-cell">${evidenceCell(row.evidence)}</td>
        </tr>`,
    )
    .join("");
}

function renderQualificationRows(qualifications, models) {
  const rows = buildQualificationRows(qualifications, models);
  elements.qualificationSummary.innerHTML = `
    <div><strong>${escapeHtml(qualifications.qualifiedModels)}</strong><span>qualified models</span></div>
    <div><strong>${escapeHtml(
      rows.filter((row) => row.useCaseTier === "GENERATIVE_RAG" && row.qualified).length,
    )}</strong><span>generative RAG</span></div>
    <div><strong>${escapeHtml(
      rows.filter((row) => row.useCaseTier === "GUARDED_RAG" && row.qualified).length,
    )}</strong><span>guarded RAG</span></div>
    <div><strong>${escapeHtml(qualifications.rejectedModels)}</strong><span>evaluated, not qualified</span></div>`;
  elements.qualificationBody.innerHTML = rows.length
    ? rows
        .map(
          (row) => `
            <tr>
              <th class="model-result" scope="row">
                <a href="${escapeHtml(row.modelUrl)}">${escapeHtml(row.modelName)}</a>
                <small>${escapeHtml(row.backend)} ${escapeHtml(row.backendVersion)}</small>
              </th>
              <td class="text-cell">
                <span class="result-status ${row.qualified ? "profiled" : "candidate"}">
                  ${escapeHtml(row.useCase)}
                </span>
                <small>${escapeHtml(row.performanceTier.toLowerCase().replaceAll("_", " "))}</small>
              </td>
              <td>${escapeHtml(row.ttft)}</td>
              <td>${escapeHtml(row.tpot)}</td>
              <td>${escapeHtml(row.endToEnd)}</td>
              <td>${escapeHtml(row.decode)}</td>
              <td>${escapeHtml(row.rawQuality)}</td>
              <td>${escapeHtml(row.finalQuality)}</td>
              <td>${escapeHtml(row.fallbackRate)}</td>
              <td>${escapeHtml(row.peakRss)}</td>
              <td>${escapeHtml(row.attempts)}</td>
              <td class="evidence-cell">${evidenceCell(row.evidence)}</td>
            </tr>`,
        )
        .join("")
    : '<tr><td colspan="12" class="table-loading">Qualification campaign pending.</td></tr>';
}

function renderEnvironment(benchmarks) {
  const environment = benchmarks.environment;
  elements.publishedAt.textContent = new Intl.DateTimeFormat("en-US", {
    dateStyle: "long",
    timeZone: "UTC",
  }).format(new Date(`${benchmarks.publishedAt}T00:00:00Z`));
  elements.environment.textContent =
    `${environment.cpu}, ${environment.logicalProcessors} logical CPUs, ` +
    `${environment.localExecution}, ${environment.localJdk}`;

  const workload = benchmarks.ragComparison.workload;
  elements.ragWorkload.textContent =
    `${workload.documents} documents, ${workload.cases} cases, ${workload.warmups} warmup, ` +
    `${workload.iterations} measured iterations, ${workload.measuredRequests} measured requests, ` +
    `${workload.maxOutputTokens} output tokens, concurrency ${workload.concurrency}.`;
}

async function loadBenchmarks() {
  try {
    const [benchmarkResponse, catalogResponse, qualificationResponse] = await Promise.all([
      fetch("/benchmarks.json"),
      fetch("/catalog.json"),
      fetch("/qualifications.json"),
    ]);
    if (!benchmarkResponse.ok) {
      throw new Error(`Benchmark request failed: ${benchmarkResponse.status}`);
    }
    if (!catalogResponse.ok) throw new Error(`Catalog request failed: ${catalogResponse.status}`);
    if (!qualificationResponse.ok) {
      throw new Error(`Qualification request failed: ${qualificationResponse.status}`);
    }

    const [benchmarkPayload, catalogPayload, qualificationPayload] = await Promise.all([
      benchmarkResponse.json(),
      catalogResponse.json(),
      qualificationResponse.json(),
    ]);
    const models = Array.isArray(catalogPayload) ? catalogPayload : catalogPayload.models || [];
    const benchmarks = validateBenchmarkCatalog(benchmarkPayload, models);
    const qualifications = validateQualificationCatalog(qualificationPayload, models);

    renderEnvironment(benchmarks);
    renderSummary(buildBenchmarkSummary(benchmarks));
    renderInferenceRows(buildInferenceRows(benchmarks, models));
    renderQualificationRows(qualifications, models);
    renderRagRows(buildRagRows(benchmarks));
    elements.status.textContent =
      "Every row links to immutable raw evidence and publishes its SHA-256 digest.";
  } catch (error) {
    elements.inferenceBody.innerHTML =
      `<tr><td colspan="14" class="table-error">${escapeHtml(error.message)}</td></tr>`;
    elements.ragBody.innerHTML =
      `<tr><td colspan="12" class="table-error">${escapeHtml(error.message)}</td></tr>`;
    elements.qualificationBody.innerHTML =
      `<tr><td colspan="12" class="table-error">${escapeHtml(error.message)}</td></tr>`;
    elements.status.textContent = "Benchmark evidence is unavailable.";
  }
}

initializeTheme(document.querySelector("#theme-toggle"));
loadBenchmarks();
