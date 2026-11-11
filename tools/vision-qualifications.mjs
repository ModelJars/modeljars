/** Validates the bounded, paired LLaVA image-question admission policy. */
const datasets = ["vqav2", "textvqa"];
const digest = value => typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
const unit = value => Number.isFinite(value) && value >= 0 && value <= 1;
const require = (condition, message) => { if (!condition) throw new Error(message); };
const identity = files => JSON.stringify(files.map(({path, role, sha256, sizeBytes}) =>
  ({path, role, sha256, sizeBytes})).sort((a, b) => a.path.localeCompare(b.path)));

function verification(value, name) {
  require(value && digest(value.reportSha256) && typeof value.report === "string"
    && /^https:\/\/raw\.githubusercontent\.com\/[^/]+\/[^/]+\/[a-f0-9]{40}\//.test(value.report),
  `${name} requires a digest and commit-pinned verification report`);
}

export function validateEntry(entry) {
  require(entry && /^[a-z0-9_]+$/.test(entry.modelId ?? ""), "Vision model identity required");
  require(typeof entry.backendVersion === "string" && entry.backendVersion.trim(), "Vision backend version required");
  require(digest(entry.reportSha256) && digest(entry.corpusSha256), "Vision evidence digests required");
  require(new URL(entry.report).protocol === "https:", "Vision report requires immutable HTTPS location");
  for (const key of ["qualified", "integrationPassed", "releasedRuntimeVerified", "modeljarsRuntimeVerified"])
    require(typeof entry[key] === "boolean", `Vision ${key} boolean required`);
  if (entry.modeljarsRuntimeVerified) verification(entry.modeljarsVerification, "ModelJars runtime");
  if (entry.releasedRuntimeVerified) {
    verification(entry.releaseVerification, "Released runtime");
    require(/^\d+\.\d+\.\d+$/.test(entry.backendVersion)
      && entry.releaseVerification.modelsVersion === entry.backendVersion,
    "Released vision evidence must name its measured release version");
  }
  require(Array.isArray(entry.files) && entry.files.length === 2, "Vision requires two files");
  require(new Set(entry.files.map(f => f.path)).size === 2
    && ["vision-projector", "weights"].every(role => entry.files.filter(f => f.role === role).length === 1), "Vision file roles differ");
  for (const file of entry.files) {
    require(typeof file.path === "string" && file.path.split("/").every(part => part && part !== "." && part !== "..")
      && !file.path.includes("\\") && !file.path.includes(":"), "Unsafe vision file path");
    require(digest(file.sha256) && Number.isSafeInteger(file.sizeBytes) && file.sizeBytes > 0, "Invalid vision artifact identity");
    require(new URL(file.downloadUri).protocol === "https:", "Vision download requires HTTPS");
  }
  require(entry.datasets && Object.keys(entry.datasets).sort().join() === [...datasets].sort().join(), "Both vision datasets required");
  for (const name of datasets) {
    const d = entry.datasets[name];
    require(d.cases === 32 && d.blankCases === 8 && unit(d.candidateScore) && unit(d.referenceScore)
      && Number.isFinite(d.imageContribution) && Math.abs(d.imageContribution) <= 1, "Invalid vision dataset coverage or score");
  }
  require(unit(entry.tokenAgreement), "Invalid vision token agreement");
  require(Array.isArray(entry.performance) && entry.performance.length === 4
    && new Set(entry.performance.map(p => p.caseId)).size === 4, "Four distinct vision performance cases required");
  for (const name of datasets) require(entry.performance.filter(p => p.dataset === name).length === 2, "Vision performance cohort missing");
  for (const p of entry.performance) {
    require(typeof p.caseId === "string" && p.caseId.trim() && Number.isInteger(p.warmups) && p.warmups >= 1
      && Number.isInteger(p.repetitions) && p.repetitions >= 3, "Invalid vision performance repetitions");
    for (const key of ["prefillRatio", "decodeRatio", "totalRatio"])
      require(Number.isFinite(p[key]) && p[key] > 0, "Invalid vision latency ratio");
  }
  const passed = entry.integrationPassed && entry.tokenAgreement >= .99
    && entry.datasets.vqav2.candidateScore >= .5 && entry.datasets.textvqa.candidateScore >= .2
    && entry.datasets.vqav2.imageContribution >= .05
    && datasets.every(name => entry.datasets[name].referenceScore - entry.datasets[name].candidateScore <= .005 + 1e-12)
    && entry.performance.every(p => p.prefillRatio <= 1.1 && p.decodeRatio <= 1.1 && p.totalRatio <= 1.1);
  require(!entry.qualified || passed, "Qualified vision evidence fails a registered gate");
  return entry;
}

export function publishable(entry) {
  return entry.qualified && entry.releasedRuntimeVerified && entry.modeljarsRuntimeVerified;
}

export function validateManifest(manifest, catalog) {
  require(manifest?.schemaVersion === 1 && manifest.policy === "llava-vqa-v1" && Array.isArray(manifest.entries), "Unsupported vision policy");
  const seen = new Set();
  for (const entry of manifest.entries) {
    validateEntry(entry);
    require(!seen.has(entry.modelId), "Duplicate vision model identity"); seen.add(entry.modelId);
    const model = catalog.models.find(m => m.id === entry.modelId);
    require(model?.format === "gguf" && model.architecture === "llama"
      && model.features?.includes("llava-1.5") && model.capabilities?.includes("image-understanding")
      && model.backends?.["rust-ffm"] === true && Array.isArray(model.files)
      && identity(model.files) === identity(entry.files), `Vision snapshot does not match ${entry.modelId}`);
  }
  return manifest;
}

function escape(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/\n/g, "\\n").replace(/\r/g, "\\r").replace(/[\u0080-\uffff]/g,
    character => "\\u" + character.charCodeAt(0).toString(16).padStart(4, "0"));
}

export function registryProperties(manifest) {
  const lines = ["modeljars.vision.schemaVersion=1", `modeljars.vision.count=${manifest.entries.length}`];
  manifest.entries.forEach((entry, index) => {
    validateEntry(entry);
    const prefix = `vision.${index}.`;
    const put = (key, value) => lines.push(`${prefix}${key}=${escape(value)}`);
    for (const key of ["modelId", "backendVersion", "report", "reportSha256", "corpusSha256", "tokenAgreement", "integrationPassed", "qualified"]) put(key, entry[key]);
    entry.files.forEach((file, i) => {
      for (const key of ["path", "role", "downloadUri", "sha256", "sizeBytes"]) put(`files.${i}.${key}`, file[key]);
    });
    for (const name of datasets) for (const [key, value] of Object.entries(entry.datasets[name])) put(`${name}.${key}`, value);
    entry.performance.forEach((p, i) => {
      for (const [key, value] of Object.entries(p)) put(`performance.${i}.${key}`, value);
    });
  });
  return lines.join("\n") + "\n";
}
