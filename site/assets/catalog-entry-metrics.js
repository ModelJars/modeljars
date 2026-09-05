import { formatDuration } from "./benchmark-data.js";

function finite(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function percent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

export function qualificationMetrics(qualification) {
  if (!qualification?.qualified) return [];

  const metrics = [];
  const add = (label, value) => {
    if (value !== null) metrics.push({ label, value });
  };

  switch (qualification.useCaseTier) {
    case "GENERATIVE_RAG":
    case "GUARDED_RAG":
      add(
        "TTFT p95",
        finite(qualification.p95TtftMillis)
          ? formatDuration(qualification.p95TtftMillis)
          : null,
      );
      add(
        "decode",
        finite(qualification.p50DecodeTokensPerSecond)
          ? `${qualification.p50DecodeTokensPerSecond.toFixed(1)} tok/s`
          : null,
      );
      break;
    case "SEMANTIC_SEARCH":
      add(
        "agreement",
        finite(qualification.minimumOracleCosine)
          ? qualification.minimumOracleCosine.toFixed(5)
          : null,
      );
      add(
        "dimensions",
        Number.isSafeInteger(qualification.embeddingDimension)
          ? String(qualification.embeddingDimension)
          : null,
      );
      break;
    case "TOOL_CALLING":
      add(
        "tool selection",
        finite(qualification.toolSelectionExactRate)
          ? percent(qualification.toolSelectionExactRate)
          : null,
      );
      add(
        "arguments",
        finite(qualification.expectedArgumentAccuracy)
          ? percent(qualification.expectedArgumentAccuracy)
          : null,
      );
      break;
    case "TEXT_TO_SPEECH":
      add(
        "TTFA p95",
        finite(qualification.p95TimeToFirstAudioMillis)
          ? formatDuration(qualification.p95TimeToFirstAudioMillis)
          : null,
      );
      add(
        "RTF p95",
        finite(qualification.p95RealTimeFactor)
          ? qualification.p95RealTimeFactor.toFixed(2)
          : null,
      );
      break;
    default:
      break;
  }

  return metrics;
}

