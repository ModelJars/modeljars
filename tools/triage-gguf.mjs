#!/usr/bin/env node
/**
 * Triage a GGUF by catalog id or by URL, before writing any code against it.
 *
 * A URL is accepted on purpose: the question "should this become a candidate" has to be answerable
 * for a model that is not in the catalog yet, which is exactly when it matters most.
 *
 * Usage:
 *   npm run catalog:triage -- <catalog-id>
 *   npm run catalog:triage -- https://huggingface.co/<repo>/resolve/<rev>/<file>.gguf
 *   npm run catalog:triage -- <catalog-id-or-url> --json
 */
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

import { triageUri } from "./gguf-triage.mjs";

const USER_AGENT = "ModelJars-Catalog-Triage/0.1";

async function resolveUri(target) {
  if (/^https?:\/\//.test(target)) {
    return { uri: target, source: "url" };
  }
  const catalog = JSON.parse(
    await readFile(new URL("../catalog/models.json", import.meta.url), "utf8"),
  );
  const model = catalog.models.find((candidate) => candidate.id === target);
  if (!model) {
    throw new Error(
      `Unknown catalog ID and not a URL: ${target}\n` +
        "Pass a catalog id, or a direct https URL to a .gguf to triage something not yet listed.",
    );
  }
  return { uri: model.downloadUri, source: "catalog", model };
}

function formatShare(share) {
  return `${(share * 100).toFixed(1)}%`;
}

function render(target, resolved, report) {
  const lines = [];
  lines.push(`# ${target}`);
  lines.push(`  uri          ${resolved.uri}`);
  lines.push(`  architecture ${report.architecture}`);
  if (report.name) lines.push(`  name         ${report.name}`);
  if (report.license) lines.push(`  license      ${report.license}`);
  lines.push(`  tensors      ${report.tensorCount}`);

  const towers = Object.entries(report.towers);
  lines.push(
    `  towers       ${towers.length === 0 ? "none found (text only)" : towers.map(([t, n]) => `${t} (${typeof n === "number" ? `${n} tensors` : n})`).join(", ")}`,
  );

  if (resolved.model) {
    const claimed = resolved.model.capabilities ?? [];
    const visual = claimed.filter((c) => /image|vision|video|ocr|document/i.test(c));
    const audio = claimed.filter((c) => /audio|speech|voice/i.test(c));
    if (visual.length > 0 && report.towers.vision === undefined) {
      lines.push(`  !! claims ${visual.join(", ")} but the file carries NO vision tensors`);
    }
    if (audio.length > 0 && report.towers.audio === undefined && !/tts|audiocpp|soprano/i.test(report.architecture)) {
      lines.push(`  !! claims ${audio.join(", ")} but the file carries NO audio tensors`);
    }
  }

  lines.push("");
  lines.push("  tensor roots");
  for (const [root, count] of Object.entries(report.tensorRoots).slice(0, 12)) {
    lines.push(`    ${root.padEnd(24)} ${String(count).padStart(5)}`);
  }

  lines.push("");
  lines.push("  weights by quantization (token embedding excluded: read by lookup, not multiplied)");
  for (const row of report.weights.rows) {
    lines.push(
      `    ${row.type.padEnd(8)} ${String(row.tensors).padStart(4)} tensors  ${String(row.elements).padStart(14)} elements  ${formatShare(row.share).padStart(7)}`,
    );
  }

  lines.push("");
  lines.push("  header");
  for (const [key, value] of Object.entries(report.metadata)) {
    if (key.startsWith("general.")) continue;
    lines.push(`    ${key} = ${Array.isArray(value) ? JSON.stringify(value) : value}`);
  }
  return lines.join("\n");
}

async function main() {
  const args = process.argv.slice(2).filter((a) => a !== "--json");
  const asJson = process.argv.includes("--json");
  const target = args[0];
  if (!target) {
    throw new Error("Usage: npm run catalog:triage -- <catalog-id|https-url> [--json]");
  }
  const resolved = await resolveUri(target);
  const report = await triageUri(resolved.uri, {
    additionalFetchHeaders: { "User-Agent": USER_AGENT },
  });
  process.stdout.write(
    asJson
      ? `${JSON.stringify({ target, uri: resolved.uri, ...report }, null, 2)}\n`
      : `${render(target, resolved, report)}\n`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
