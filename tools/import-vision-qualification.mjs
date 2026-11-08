/** Imports measured branch evidence. Publication stays closed pending released-runtime verification. */
import {createHash} from "node:crypto";
import {readFile, writeFile} from "node:fs/promises";
import {pathToFileURL} from "node:url";
import {validateManifest} from "./vision-qualifications.mjs";

const require = (condition, message) => { if (!condition) throw new Error(message); };
const gates = ["accuracyLoss", "vqaFloor", "textVqaFloor", "imageContribution", "tokenAgreement",
  "performance", "strongestReferencePerformance", "integrationConfirmation"];

export function importAttempt(bytes, reportUrl, catalog) {
  const attempt = JSON.parse(bytes.toString());
  require(attempt.schema === "llava-catalog-attempt-v1", "Unknown attempt schema");
  require(/^https:\/\/raw\.githubusercontent\.com\/integrallis\/models\/[a-f0-9]{40}\//.test(reportUrl),
    "Report URL must pin a Models source commit");
  const receipt = attempt.receipt;
  require(/^[a-f0-9]{40}$/.test(receipt.revision), "Measured runtime commit required");
  require(receipt.threads === 8 && receipt.context === 1024 && receipt.maximumOutputTokens === 32
    && receipt.kvType === "f32", "Measured execution controls differ");
  require(attempt.candidates?.length === 2
    && new Set(attempt.candidates.map(c => c.variant)).size === 2, "Both variant verdicts required");
  const entries = attempt.candidates.map(candidate => {
    require(["Q4_K_S", "Q4_K_M"].includes(candidate.variant), "Unknown LLaVA variant");
    require(Object.keys(candidate.gates).sort().join() === [...gates].sort().join()
      && gates.every(g => typeof candidate.gates[g] === "boolean"), "Incomplete gate coverage");
    require(candidate.backendQualified === gates.every(g => candidate.gates[g]), "Verdict contradicts gates");
    require(candidate.gates.integrationConfirmation === attempt.confirmationIntegration.passed,
      "Integration verdict mismatch");
    const modelId = `second_state_llava_v1_5_7b_gguf_${candidate.variant.toLowerCase()}`;
    const model = catalog.models.find(m => m.id === modelId);
    require(model && model.files.every(f => receipt.artifacts[f.path] === f.sha256), "Measured snapshot differs");
    require(candidate.performance.length === 4, "Incomplete performance cohort");
    if (candidate.backendQualified) require(candidate.performance.every(p => p.passed && p.generationLengthsMatch),
      "Performance requires matched generation lengths");
    return {
      modelId, backendVersion: `models-source:${receipt.revision}`,
      report: reportUrl, reportSha256: createHash("sha256").update(bytes).digest("hex"),
      corpusSha256: receipt.corpusSha256, files: model.files, datasets: candidate.datasets,
      tokenAgreement: candidate.tokenAgreement,
      performance: candidate.performance.map(p => ({dataset: p.dataset, caseId: p.id,
        warmups: p.warmups, repetitions: p.repetitions,
        prefillRatio: p.worstRatios.prefillMs, decodeRatio: p.worstRatios.decodeMs, totalRatio: p.worstRatios.totalMs})),
      integrationPassed: attempt.confirmationIntegration.passed,
      qualified: candidate.backendQualified, releasedRuntimeVerified: false, modeljarsRuntimeVerified: false,
    };
  });
  return validateManifest({schemaVersion: 1, policy: "llava-vqa-v1", entries}, catalog);
}

async function main() {
  const [attempt, reportUrl, output, catalogPath = "catalog/models.json"] = process.argv.slice(2);
  require(attempt && reportUrl && output,
    "usage: import-vision-qualification.mjs ATTEMPT_JSON IMMUTABLE_REPORT_URL OUTPUT_JSON [CATALOG_JSON]");
  const imported = importAttempt(await readFile(attempt), reportUrl, JSON.parse(await readFile(catalogPath)));
  // Refuse to replace any prior qualification through this initial-admission importer.
  let previous;
  try { previous = JSON.parse(await readFile(output)); }
  catch (error) { if (error.code !== "ENOENT") throw error; }
  require(!previous || previous.entries.length === 0, "Output already contains evidence; choose a new output file");
  await writeFile(output, JSON.stringify(imported, null, 2) + "\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
