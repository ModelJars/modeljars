# Validation against the separate model qualification plan

Reviewed 2026-11-01 against GLiNER plan commit `f4cda76`. Verdict: the plans agree on the
overall method, but the other campaign has unresolved admission and measurement issues.
It is useful design input, not a qualification-ready replacement for GLiNER's plan.

## Review scope and provenance

- Workspace summary: `~/Code/java-ai/MODEL-QUALIFICATION-PLAN.md`, SHA-256
  `9a8f4aaf450c37964aba2fafd47a9998b499f92b8ba39808da3407b426cddd3a`.
- Supporting code/design found in `projects/models-q8f32-kernel`, branch
  `feat/kernel-dispatch-and-measurement-protocol`, commit
  `706500ec829f31f2e251fb3b42663978f91c6a44`.
- `docs/QUALIFYING-MODELS-OUR-HARNESS-CANNOT-MEASURE.md`, SHA-256
  `520c52b0622cd421513e74e58531b07b2021f7b5eda27d5c4f4393435b7f2e0c`.
- `scripts/fleet/transformers-completion-server.py`, SHA-256
  `38a75be4df03762425343f8e35d8323827dd5ad3f7bf2c3e1cf393c037f2061a`.

The summary's `projects/models/docs/...` link does not resolve in that checkout; its supporting
document is on the separate worktree above. Use commit-specific links when circulating it.
This review reads the plans, adapter and qualification code. It does not independently reproduce
the stated Mellum/LFM2MoE measurements, verify publisher benchmark numbers, inspect live pods,
or run a model. Those claims remain attributed to the other plan.

## Agreements and GLiNER-specific differences

| Topic | Agreement / resolution |
| --- | --- |
| Reference engine | A supported publisher runtime is appropriate; neither Ollama nor llama.cpp is mandatory. GLiNER uses its pinned Python inference API. |
| Integration | Native shims are legitimate integration work. GLiNER assesses Rust adaptation before insisting on a complete Java port. |
| Modality | Reuse orchestration and evidence machinery, but give classification, ASR, TTS and generation their own metrics and policies. |
| Provenance | Pin both artifacts when they differ, plus conversion provenance. Different weights are an operational comparison, not numerical parity evidence. |
| Repetition | Three paired final runs, counterbalanced order, all raw results and nulls retained. Freeze the implementation before counting qualification replications. |
| Matching workload | Identical raw inputs and explicit transformations. GLiNER matches documents, schemas, encoded lengths, label/task counts and batches; generated-token counts do not apply. |
| Quality | Accuracy and calibration are separate. GLiNER retains independently labelled quality tests in addition to publisher parity. |
| Publication | New candidates need qualifying released-runtime evidence; existing catalog qualifications remain untouched. Losing implementation experiments are removed. |

## Findings requiring correction in the other campaign

### 1. Maple's scheduled comparison violates its own device rule — admission blocker

Summary lines 52–55 pair FA3 on H100 with a CPU-side candidate on that host. Lines 83–84 and
the supporting design explicitly exclude GPU-versus-CPU comparisons. Same host does not make
compute resources equivalent. Run both arms on matched compute, or retain this only as an
explicit deployment comparison that cannot pass the implementation-relative gate.

This must be enforced in code: `RagProductionQualificationPolicy.sameHardware()` checks host,
OS, architecture, CPU, processors and RAM, but not the device actually executing the model.
Require loaded-device attestation rather than trusting the pod type or CLI request.

### 2. GGUF versus safetensors cannot use the unchanged standard gate — admission blocker

Summary lines 39–50 prescribe GGUF for Models and publisher safetensors for the comparator,
while describing Mellum/LFM2MoE as standard-gate runs. The current
`RagProductionQualificationPolicy.exclusion()` rejects differing artifact SHA-256 **and size**.
The supporting design acknowledges that for Maple, but the same restriction applies to the
other two whenever their arms use different artifacts.

Implement the declared cross-artifact policy before admitting any such pair. Pin both genuine
loaded artifacts and conversion/quantization lineage; quantify output quality differences.
Do not pass the candidate GGUF to the reference client's `--artifact` just to make hashes agree:
`RagBenchmarkCli` hashes that local argument, while the server independently loads `--model`.
Their identities are not currently bound together by a server manifest.

### 3. The completion adapter is more than protocol translation — reference blocker

The adapter describes the generation loop as the publisher's own. In fact, lines 123–178 run
`self.model(...)` repeatedly and implement cache stepping, sampling, repetition penalties,
EOS and stop handling locally. There is no call to the model's `generate` API. This may be
usable, but equivalence to the publisher's inference path has not been established by the plan.

Prefer wrapping the publisher's supported generation entry point; otherwise establish
output/control and timing equivalence against that entry point before calling it authoritative.
Specific review gaps:

- No revision argument pins model, tokenizer or remote code. A pinned library version alone
  does not identify the loaded checkpoint or custom modelling code.
- `device_map="auto"` is allowed, while readiness returns only `ok`; the effective placement,
  dtype, dependencies and loaded artifact digests are not attested to the harness.
- Request `n_threads` is advertised but not read; threads are set only by the server CLI.
  Report and verify effective values rather than assuming request fields took effect.
- Timing begins after tokenization. `predicted_n` counts every generated token while the
  decode timer starts after the first forward pass; define first-token accounting consistently
  with the other arm. Accelerator timing needs explicit synchronized boundaries or device events.
- The stream flush and repeated decoding of the growing token list occur inside the timed loop.
  Separate inference and transport costs, and measure wrapper overhead against direct inference.

Static inspection confirms these gaps; no claim is made here that the adapter's outputs have
already been shown wrong. Until validated, it cannot substantiate the stronger reference claim.

### 4. Speech policies still need fully specified gates — policy blocker

The TTS design says `round-trip WER <= threshold` without a numeric threshold despite claiming
every threshold is fixed. The summary's `RTF ratio >= 0.80` also omits the numerator. Define
RTF as synthesis wall time / audio duration (lower is better), then gate
`RTF_reference / RTF_models >= 0.80`, equivalently `RTF_models <= RTF_reference / 0.80`.
RTFx has the opposite direction: `RTFx_models / RTFx_reference >= 0.80`.

The supporting TTS design turns a published RTF of 0.469 into an absolute Models ceiling of
0.586. That is not the same-host comparison promised elsewhere unless the original hardware,
voice, inputs and settings are reproduced. Use measured local reference RTF for admission;
keep external figures as context. Pin voices, judge, datasets and normalization, and register
numeric intelligibility and failure/coverage requirements before running.

ASR's published-WER ×1.10 rule needs the exact evaluation manifest, weighting and normalization
that produced the published aggregate; a different subset cannot inherit that bound. Removing
RAG's contribution metric is sensible, but completion is not automatically 100%: exceptions,
timeouts, missing audio outputs and omitted transcripts must remain in the denominator. A valid
empty transcript should be scored against its reference, not discarded as a missing result.

### 5. Replication and length matching need precise acceptance semantics

Three runs and mean/spread are good requirements, but neither plan originally made every detail
of final-run selection explicit. Specify whether every pair must pass or use a preregistered
statistical rule. The GLiNER plan now requires all three final pairs to pass every claimed bucket.
Do not transfer the summary's observed ±8% spread into a universal error bound for other models.

For generative speed comparisons, matched output length must not be obtained by discarding early
stops, truncating bad answers or altering the quality cohort after the fact. Separate a fixed-work
performance probe from natural-stop quality evaluation, reporting both. GLiNER has no decoding
loop; the analogous controls are input/schema size, task/label count, batch and concurrency.

## Changes incorporated into our plan

The GLiNER plan now explicitly requires effective-device and loaded-artifact attestation,
isolated dependency locks, a validated thin publisher wrapper, matched compute allocations,
three final paired runs with a defined acceptance rule, and model-specific noise reporting.
It also calls for reusing sound harness infrastructure without importing generative metrics.

The other agent's branch and workspace summary were left unchanged. No CI was cancelled,
no remote worker was touched, and no qualification was added or removed.
