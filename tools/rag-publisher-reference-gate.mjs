import { createHash } from "node:crypto";
import { isDeepStrictEqual } from "node:util";

export const PUBLISHER_RAG_POLICY = "production-rag-publisher-reference-v1";
const BONSAI = "prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0";
const ARTIFACT = "53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3";
const REFERENCE = "PrismML-Eng/llama.cpp@e8fd209c975c80aa7fb36a8423571ff3b1b7e955";

function require(value, message) {
  if (!value) throw new Error(`Publisher-reference qualification: ${message}`);
}

function binding(value) {
  require(value && /^[a-f0-9]{64}$/.test(value.reportSha256 ?? value.sha256 ?? ""), "missing report digest");
  const path = value.report ?? value.path;
  require(typeof path === "string" && /^[A-Za-z0-9_./-]+\.json$/.test(path) &&
    !path.split("/").some(part => !part || part === "." || part === ".."), "invalid report path");
  return { report: path, sha256: value.reportSha256 ?? value.sha256 };
}

/** Bind the exceptional policy to its exact artifact, pinned reference and hash-verified verdict. */
export async function validatePublisherReference(entry, revision, loadReport) {
  if (entry.policyVersion !== PUBLISHER_RAG_POLICY) return;
  require(entry.modelId === BONSAI && entry.artifactSha256 === ARTIFACT &&
    entry.artifactSizeBytes === 5946648928, "unreviewed artifact");
  const receipt = binding(entry.qualificationEvidence);
  if (!loadReport) return;
  const read = async (reference) => {
    const ref = binding(reference);
    const bytes = await loadReport({ entry, revision, report: ref.report });
    require(createHash("sha256").update(bytes).digest("hex") === ref.sha256, `digest mismatch for ${ref.report}`);
    return JSON.parse(Buffer.from(bytes).toString("utf8"));
  };
  const evidence = await read(receipt);
  require(evidence.schemaVersion === 1 && evidence.policyId === PUBLISHER_RAG_POLICY,
    "wrong verdict policy");
  require(evidence.qualification?.qualified === true && evidence.qualification?.verdict === "QUALIFIED",
    "verdict did not qualify");
  require(evidence.candidate?.path === entry.report && evidence.candidate?.sha256 === entry.reportSha256,
    "candidate binding differs from catalog");
  require(evidence.comparators?.length === 1, "exactly one publisher comparator is required");
  const candidate = await read(evidence.candidate);
  const comparator = await read(evidence.comparators[0]);
  for (const report of [candidate, comparator]) {
    require(report.modelId === BONSAI && report.artifactSha256 === ARTIFACT &&
      report.artifactSizeBytes === 5946648928, "report artifact differs");
    require(Array.isArray(report.failures) && report.failures.length === 0, "runtime failures");
    require(report.summary?.totalAttempts >= 27 && report.summary.successfulAttempts === report.summary.totalAttempts &&
      report.runs?.length === report.summary.totalAttempts, "incomplete request evidence");
    require(Number.isFinite(report.summary.p50DecodeTokensPerSecond) && report.summary.p50DecodeTokensPerSecond > 0 &&
      Number.isFinite(report.summary.endToEndMillis?.p95) && report.summary.endToEndMillis.p95 > 0, "relative performance timing is invalid");
    require(report.summary.correctAnswerRate >= .9 && report.summary.abstentionAccuracy === 1, "quality floor failed");
  }
  require(Number.isSafeInteger(entry.contextLength) && entry.contextLength > 0 &&
    entry.contextLength === candidate.settings?.contextLength, "tested context differs from catalog");
  require(candidate.backend === entry.backend && candidate.backendVersion === entry.backendVersion, "candidate runtime differs");
  require(comparator.backend === "llama.cpp" && comparator.backendVersion === REFERENCE, "unpinned publisher comparator");
  for (const key of ["hostname", "osName", "architecture", "cpuModel", "availableProcessors", "totalMemoryBytes"])
    require(candidate.environment?.[key] !== undefined &&
      isDeepStrictEqual(candidate.environment[key], comparator.environment?.[key]), `host differs: ${key}`);
  for (const key of ["workload", "corpusSha256", "caseIds", "promptTemplate", "retrievalTopK", "maxOutputTokens", "contextLength", "threads", "groundingPolicy", "minimumRetrievalScore"])
    require(candidate.settings?.[key] !== undefined &&
      isDeepStrictEqual(candidate.settings[key], comparator.settings?.[key]), `workload differs: ${key}`);
  for (const key of ["temperature", "topK", "topP", "seed", "repetitionPenalty", "stopSequences"])
    require((key === "stopSequences" || candidate.settings.generationControls?.[key] !== undefined) &&
      isDeepStrictEqual(candidate.settings.generationControls[key], comparator.settings.generationControls?.[key]), `generation differs: ${key}`);
  const coverage = report => report.runs.map(run => `${run.caseId}/${run.promptSha256}`).sort();
  require(candidate.runs.every(run => /^[a-f0-9]{64}$/.test(run.promptSha256 ?? "")) &&
    isDeepStrictEqual(coverage(candidate), coverage(comparator)), "prompt coverage differs");
  const answers = candidate.runs.filter(run => ["MODEL_ANSWER", "MODEL_ANSWER_WITH_DERIVED_CITATIONS"].includes(run.grounding?.decision));
  require(answers.length / candidate.summary.totalAttempts >= 1/3 &&
    answers.filter(run => run.evaluation?.correct === true).length / answers.length >= .9, "model contribution floor failed");
  const decode = candidate.summary.p50DecodeTokensPerSecond / comparator.summary.p50DecodeTokensPerSecond;
  const latency = candidate.summary.endToEndMillis?.p95 / comparator.summary.endToEndMillis?.p95;
  require(Number.isFinite(decode) && decode >= .8 && Number.isFinite(latency) && latency >= 0 && latency <= 1.5, "relative performance floor failed");
  require(evidence.qualification.comparisons?.some(comparison => comparison.comparatorBackend === "llama.cpp" &&
    comparison.qualified === true && comparison.minimumDecodeThroughputRatio === .8 &&
    comparison.maximumEndToEndLatencyRatio === 1.5 && comparison.decodeThroughputRatio === decode &&
    comparison.endToEndLatencyRatio === latency), "verdict ratios differ from raw reports");
}
