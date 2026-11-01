/**
 * Checks every non-text modality claim in the catalog against the artifact that is supposed to
 * back it.
 *
 * A model card claiming image understanding is not evidence. Neither is our own `capabilities`
 * list -- that list is the thing under test. The only evidence this gate accepts comes out of
 * the artifact's own GGUF header.
 *
 * Two kinds of evidence, because non-text modalities arrive two different ways:
 *
 *   1. A TOWER. In the ggml convention an encoder bolted onto a text decoder -- a CLIP vision
 *      tower, a whisper audio encoder -- ships as tensors under its own namespace, or as a
 *      separate projector GGUF. `detectTowers` finds them. This is how image-understanding,
 *      video-understanding, audio-understanding, speech-to-text and ocr are backed.
 *
 *   2. A FIRST-PARTY TASK DECLARATION. A generative audio model has no "tower" -- the generator
 *      IS the model, so there is no separate namespace to find. Those artifacts instead declare
 *      their task in the header, e.g. soprano ships
 *      `audiocpp.model_spec.json = {"tasks":["tts"], ...}`. That is the converter stating the
 *      model's purpose, which is stronger evidence than any tensor-name heuristic.
 *
 *   3. A DECLARED INTERFACE, for ONNX. An ONNX graph has neither ggml tensor namespaces nor a task
 *      string, but it does declare its typed inputs and outputs, and the pinned config.json beside
 *      it declares the sample rate. A graph that takes integer token ids and returns a float
 *      waveform at a declared sample rate is the artifact describing itself as a speech
 *      generator (`onnxDeclaredTasks`). This is a structural declaration, weaker than a task
 *      string and stronger than a tensor-name guess; the record keeps the names it decided on.
 *
 * Requiring a tower alone would be wrong and was checked before this gate was written: soprano,
 * the one genuinely qualified TTS model in the catalog, carries NO tower by any name pattern.
 * A tower-only rule would have failed the honest entry and is exactly the false positive this
 * gate must not produce.
 *
 * Offline by default. The network read is done once by `--write`, which commits an evidence
 * record per entry; the gate then compares claims against the record and refuses a record whose
 * `sha256` no longer matches the catalog, so a swapped artifact cannot inherit old evidence.
 *
 *   node tools/modality-claim-gate.mjs                      # check (offline, CI)
 *   node tools/modality-claim-gate.mjs --write              # fetch artifacts, record evidence
 *   node tools/modality-claim-gate.mjs --write --only=<id>  # record one entry only
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from "node:fs";
import path from "node:path";

/**
 * The claims this gate governs, and which towers can back each.
 *
 * Keys are catalog capability strings; values are the towers that would substantiate the claim.
 * A claim backed by a declared task needs no tower, so an empty list is not "impossible to
 * satisfy", it means "tower evidence does not apply to this claim".
 */
export const MODALITY_CLAIMS = new Map([
  ["image-understanding", { towers: ["vision", "projector"], tasks: ["image", "vision", "vqa"] }],
  ["video-understanding", { towers: ["vision", "projector"], tasks: ["video"] }],
  ["audio-understanding", { towers: ["audio", "projector"], tasks: ["audio", "asr"] }],
  ["speech-to-text", { towers: ["audio"], tasks: ["asr", "stt", "transcribe"] }],
  ["ocr", { towers: ["vision", "projector"], tasks: ["ocr"] }],
  ["text-to-speech", { towers: ["audio"], tasks: ["tts", "text-to-speech"] }],
  ["audio-generation", { towers: ["audio"], tasks: ["tts", "audio", "audio-generation"] }],
  ["voice-cloning", { towers: ["audio"], tasks: ["tts", "voice-cloning", "voice_cloning"] }],
]);

/**
 * Task strings an artifact declares about itself.
 *
 * Read from the header only. `audiocpp.model_spec.json` is a JSON blob whose `tasks` array is the
 * declaration; the `stt.*` namespace is itself a declaration that the file does speech-to-text
 * (whisper artifacts carry `stt.frontend.type` and friends). Anything that cannot be parsed is
 * skipped rather than guessed at -- a malformed blob yields no tasks, which fails the claim
 * closed.
 */
export function declaredTasks(metadata = {}) {
  const tasks = new Set();
  for (const [key, value] of Object.entries(metadata)) {
    if (key.endsWith("model_spec.json") && typeof value === "string") {
      try {
        const spec = JSON.parse(value);
        for (const t of spec?.tasks ?? []) {
          if (typeof t === "string") tasks.add(t.toLowerCase());
        }
      } catch {
        // Unparseable: contributes nothing. The claim then fails for want of evidence.
      }
    }
    if (key.startsWith("stt.")) tasks.add("stt");
    if (key.startsWith("tts.")) tasks.add("tts");
  }
  return [...tasks].sort();
}

/**
 * Task an ONNX artifact declares through its interface, read from the inspector's summary and
 * the pinned config.json. One rule so far: integer token ids in, a float waveform out, and a
 * declared positive sample rate is "tts". The names that satisfied the rule are returned so the
 * evidence record can show its work; an interface that fits no rule declares nothing.
 */
export function onnxDeclaredTasks({ inputs = [], outputs = [] } = {}, config = {}) {
  const tokenInput = inputs.find(
    (i) => /^(U?INT(8|16|32|64))$/.test(i.elemType ?? "") && /input_ids|tokens?|phonem/i.test(i.name));
  const waveformOutput = outputs.find(
    (o) => o.elemType === "FLOAT" && /audio|wav|waveform|pcm|speech/i.test(o.name));
  const sampleRate = Number(config?.sample_rate);
  const tasks = [];
  const evidence = {};
  if (tokenInput && waveformOutput && Number.isFinite(sampleRate) && sampleRate > 0) {
    tasks.push("tts");
    evidence.tts = { input: tokenInput.name, output: waveformOutput.name, sampleRate };
  }
  return { tasks: tasks.sort(), evidence };
}

/** Decides one claim against one evidence record. Pure; the tests drive this directly. */
export function backing(claim, evidence) {
  const rule = MODALITY_CLAIMS.get(claim);
  if (!rule) return { ok: true, why: "not a modality claim this gate governs" };
  const towers = Object.keys(evidence.towers ?? {});
  const tower = rule.towers.find((t) => towers.includes(t));
  if (tower) return { ok: true, why: `artifact carries a ${tower} tower` };
  const tasks = evidence.declaredTasks ?? [];
  const task = rule.tasks.find((t) => tasks.includes(t));
  if (task) return { ok: true, why: `artifact declares task "${task}" (${evidence.format === "onnx" ? "typed interface and pinned config" : "header"})` };
  return {
    ok: false,
    why: `no ${rule.towers.join("/")} tower and no declared task in `
       + `${rule.tasks.map((t) => `"${t}"`).join("/")}; header carries `
       + `${towers.length ? `towers ${towers.join(", ")}` : "no towers"} and `
       + `${tasks.length ? `tasks ${tasks.join(", ")}` : "no declared tasks"}`,
  };
}

export function claimsOf(model) {
  return (model.capabilities ?? []).filter((c) => MODALITY_CLAIMS.has(c)).sort();
}

export function evidenceDir(root) {
  return path.join(root, "catalog", "modality-evidence");
}

/**
 * Runs the gate over a catalog using already-loaded evidence.
 *
 * `loadEvidence(id)` returns the record or null. Kept injectable so the tests can run the whole
 * gate without a filesystem or a network.
 */
export function runGate(models, loadEvidence) {
  const findings = [];
  for (const model of models) {
    const claims = claimsOf(model);
    if (claims.length === 0) continue;
    if (model.format !== "gguf" && model.format !== "onnx") {
      findings.push({ id: model.id, status: "UNVERIFIABLE", claims,
        detail: `format is ${model.format}; this gate reads GGUF headers and ONNX graphs` });
      continue;
    }
    const ev = loadEvidence(model.id);
    if (!ev) {
      findings.push({ id: model.id, status: "NO_EVIDENCE", claims,
        detail: "no record; run with --write" });
      continue;
    }
    if (model.sha256 && ev.sha256 && model.sha256 !== ev.sha256) {
      findings.push({ id: model.id, status: "STALE_EVIDENCE", claims,
        detail: `record is for sha256 ${ev.sha256.slice(0, 12)} but the catalog now pins `
              + `${model.sha256.slice(0, 12)}` });
      continue;
    }
    const unbacked = [];
    const backed = [];
    for (const claim of claims) {
      const r = backing(claim, ev);
      (r.ok ? backed : unbacked).push({ claim, why: r.why });
    }
    findings.push({
      id: model.id,
      status: unbacked.length ? "UNBACKED_CLAIM" : "BACKED",
      claims, backed, unbacked,
    });
  }
  return findings;
}

async function ggufRecord(model, triageUri) {
  const t = await triageUri(model.downloadUri);
  return {
    id: model.id,
    downloadUri: model.downloadUri,
    sha256: model.sha256,
    architecture: t.architecture,
    tensorCount: t.tensorCount,
    towers: t.towers,
    declaredTasks: declaredTasks(t.metadata ?? {}),
    recordedAt: new Date().toISOString(),
    recordedBy: "tools/modality-claim-gate.mjs --write",
  };
}

/**
 * The ONNX record: the graph's own declared interface plus the pinned config.json, both fetched
 * from the catalog's URIs and the config checked against the digest the catalog lists for it, so
 * the record cannot describe a config the entry does not pin.
 */
async function onnxRecord(model, inspectOnnx) {
  const graph = await inspectOnnx(model.downloadUri);
  const configFile = (model.files ?? []).find((f) => f.path === "config.json");
  let config = {};
  let configSha256 = null;
  if (configFile) {
    const uri = `${model.sourceUri}/resolve/${model.revision}/${configFile.path}`;
    const response = await fetch(uri, { redirect: "follow" });
    if (!response.ok) throw new Error(`HTTP ${response.status} fetching ${uri}`);
    const bytes = new Uint8Array(await response.arrayBuffer());
    configSha256 = createHash("sha256").update(bytes).digest("hex");
    if (configSha256 !== configFile.sha256) {
      throw new Error(`config.json digest ${configSha256.slice(0, 12)} does not match the catalog's ${configFile.sha256.slice(0, 12)}`);
    }
    config = JSON.parse(new TextDecoder().decode(bytes));
  }
  const declared = onnxDeclaredTasks(graph, config);
  return {
    id: model.id,
    downloadUri: model.downloadUri,
    sha256: model.sha256,
    format: "onnx",
    architecture: model.architecture,
    irVersion: graph.irVersion,
    producer: graph.producer,
    opset: graph.opset,
    inputs: graph.inputs,
    outputs: graph.outputs,
    nodeCount: graph.nodeCount,
    operatorTypes: graph.operatorTypes,
    initializerCount: graph.initializerCount,
    config: {
      sha256: configSha256,
      model_type: config.model_type ?? null,
      sample_rate: config.sample_rate ?? null,
      voices: Array.isArray(config.voices) ? config.voices : null,
      n_token: config.n_token ?? null,
    },
    towers: {},
    declaredTasks: declared.tasks,
    declaredTaskEvidence: declared.evidence,
    recordedAt: new Date().toISOString(),
    recordedBy: "tools/modality-claim-gate.mjs --write",
  };
}

function loadCatalog(root) {
  const raw = JSON.parse(readFileSync(path.join(root, "catalog", "models.json"), "utf8"));
  return Array.isArray(raw) ? raw : raw.models;
}

async function main(argv) {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
  const write = argv.includes("--write");
  const only = argv.find((a) => a.startsWith("--only="))?.slice("--only=".length);
  const models = loadCatalog(root);
  const dir = evidenceDir(root);

  if (write) {
    const { triageUri } = await import("./gguf-triage.mjs");
    const { inspectOnnx } = await import("./onnx-inspector.mjs");
    mkdirSync(dir, { recursive: true });
    for (const model of models) {
      if (claimsOf(model).length === 0) continue;
      if (only && model.id !== only) continue;
      if (model.format !== "gguf" && model.format !== "onnx") continue;
      process.stdout.write(`  fetching ${model.format === "gguf" ? "header" : "graph"}: ${model.id} ... `);
      try {
        const record = model.format === "gguf"
          ? await ggufRecord(model, triageUri)
          : await onnxRecord(model, inspectOnnx);
        writeFileSync(path.join(dir, `${model.id}.json`),
                      `${JSON.stringify(record, null, 1)}\n`);
        console.log(`towers=${JSON.stringify(record.towers)} tasks=${JSON.stringify(record.declaredTasks)}`);
      } catch (e) {
        console.log(`FAILED: ${e.message.slice(0, 100)}`);
        process.exitCode = 1;
      }
    }
    console.log();
  }

  const loadEvidence = (id) => {
    const p = path.join(dir, `${id}.json`);
    return existsSync(p) ? JSON.parse(readFileSync(p, "utf8")) : null;
  };
  const findings = runGate(models, loadEvidence);

  const by = (s) => findings.filter((f) => f.status === s);
  console.log(`modality claims checked: ${findings.length} entries carry one`);
  for (const f of findings) {
    console.log(`  ${f.status.padEnd(15)} ${f.id}  ${f.claims.join(", ")}`);
    for (const b of f.backed ?? []) console.log(`      ok   ${b.claim}: ${b.why}`);
    for (const u of f.unbacked ?? []) console.log(`      BAD  ${u.claim}: ${u.why}`);
    if (f.detail) console.log(`      ${f.detail}`);
  }

  const unverifiable = by("UNVERIFIABLE");
  if (unverifiable.length) {
    console.log();
    console.log(`${unverifiable.length} entr${unverifiable.length === 1 ? "y" : "ies"} cannot be `
      + `verified by this gate (neither GGUF nor ONNX). That is "no data", not "claim is fine"; they need a `
      + `safetensors reader or a separate check.`);
  }

  const bad = [...by("UNBACKED_CLAIM"), ...by("NO_EVIDENCE"), ...by("STALE_EVIDENCE")];
  console.log();
  if (bad.length) {
    console.log(`FAIL: ${bad.length} entr${bad.length === 1 ? "y" : "ies"} with a modality claim `
      + `the artifact does not back.`);
    process.exitCode = 1;
  } else {
    console.log("PASS: every checkable modality claim is backed by the artifact's own header.");
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main(process.argv.slice(2));
}
