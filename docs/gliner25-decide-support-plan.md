# GLiNER2.5-Decide: implementation and catalog admission plan

Date: 2026-11-01. Status: researched plan; no runtime implementation, inference result,
qualification, or publication is claimed by this document.

**Recommendation:** support the pinned F32 checkpoint as a schema-driven classification
model. Reuse the existing DeBERTa encoder mathematics, add GLiNER preprocessing and its
small classification head, then qualify accuracy and performance against the publisher's
Python implementation. Start by assessing reuse of Java and existing Vectors operations,
and introduce Rust shims wherever required for correct integration or justified by performance.
A complete pure-Java implementation is not a prerequisite for a supported Models backend.
Qualification uses a dedicated GLiNER harness; llama.cpp and Ollama are not prerequisites.

This is substantial integration work, but not a new transformer architecture from scratch.
The main risks are preprocessing parity, refactoring the reranker-specific encoder safely,
and admitting multi-label decisions without pretending they are generated text or calibrated
probabilities. Catalog inclusion is contingent on measured results.

## Scope and immutable inputs

| Item | Inspected value |
| --- | --- |
| Candidate | `fastino_gliner2_5_decide_f32` in `catalog/models.json` |
| HF revision | `9d1bfb848cd16d93ee56ccbc59cd92bf0f284d90` |
| Weight file | `model.safetensors`, 1,945,828,140 bytes |
| Weight SHA-256 | `40a5a23ff860dc3dff426cecd1048cacdd29c648c96db209dad818e9686dc997` |
| Storage | 419 F32 tensors; 486,444,053 stored parameters, including embeddings and unused extraction heads |
| Encoder | DeBERTa-v3-large / `deberta-v2`; 24 layers, width 1024, FFN 4096, 16 heads |
| Relative attention | shared attention keys, p2c/c2p, 256 buckets, relative LayerNorm, epsilon `1e-7` |
| Tokenizer | Unigram plus GLiNER added markers; first-subword pooling |
| License | Apache-2.0 according to the checkpoint metadata; retain attribution in distribution |
| Publisher source inspected | `fastino-ai/GLiNER2` commit `ca4f68235b50fe4c3ecf3876851d748fe4b15c6c`; package declares `2.1.0` |
| ModelJars main / branch base | `a2886c364dd7c80110f94fc8c9a1316bed1a90d5` |
| Models main inspected | `5d4de4262045afe825f295e022c9a64ce1e1e6e7` |
| Vectors main inspected | `3c185a11e000b39bbdedb978545c4bd5389cac74` |

The six already-pinned snapshot files include both configs and tokenizer metadata. Preserve
their individual digests; the weight digest alone does not identify the preprocessing graph.
The inspected publisher commit is a proposed oracle pin, **not yet a tested dependency lock**.
PR 1 must demonstrate that it loads this snapshot offline and freeze its Python dependencies.

Initial scope: English single-label, multi-label, label descriptions, and several declared
tasks in one encoder pass. Scores and selected labels are structured outputs. No generation,
tool calling, explanation trace, entity extraction, relation extraction, or long-document
chunking is implied. Reject unsupported schema features explicitly.

The article's duplicate-detection LoRA is a separate potential artifact. Its released adapter,
license, base revision and held-out data have not been established. Do not attribute its
accuracy or speed claims to this base checkpoint. Defer LoRA, quantization, GPU support,
cross-task constraint solvers and extraction heads to separately scoped work.

## Verified implementation boundaries

### Models: reuse the encoder, separate the task head

Existing implementation under `backend-java/src/main/java/com/integrallis/models/backend/purejava/`:

| Existing code | Useful implementation | Required change |
| --- | --- | --- |
| `deberta/DebertaV2Config.java` | dimensions and relative-attention validation | Separate encoder validation from its requirement for `DebertaV2ForSequenceClassification`; GLiNER nests the encoder config and its standalone encoder config has no architecture declaration. Keep graph validation strict. |
| `deberta/DebertaV2Weights.java` | embedding and attention/FFN weight mapping | Currently requires F16, `deberta.*` names, pooler and scalar reranker head. Share an encoder weight loader supporting F16/F32 and explicit namespaces; GLiNER uses `encoder.*`. Keep reranker head ownership outside it. |
| `deberta/DebertaV2ForwardPass.java` | bidirectional attention, relative projections, GELU, LayerNorm | Expose final token states / requested marker rows through a shared encoder boundary; retain one encoder implementation. Do not duplicate the transformer or call the reranker's scalar score method. |
| `tokenizer/DebertaV2Tokenizer.java`, `UnigramTokenizer.java` | tokenizer JSON validation, Unigram segmentation | Current public API is sentence-pair encoding. Add reusable raw tokenization and added-marker support with GLiNER's word splitting, normalization and structural position mapping. A `[CLS] query [SEP] document [SEP]` pair is not the GLiNER input. |
| `safetensors/*`, `tensor/SafetensorsTensorSource.java` | immutable tensor parsing and shape/storage checks | Reuse these; validate all consumed names/dtypes/shapes and explicitly account for unused extraction tensors. Avoid a second weight format or a forced GGUF conversion. |

GLiNER's classification path gathers contextual `[L]` marker states, then applies
`classifier.0` (1024 → 2048), ReLU, and `classifier.2` (2048 → 1), including biases.
It does not need the checkpoint's span/count heads. The header contains 390 encoder tensors,
4 classifier tensors, and 25 span/count tensors. Verify the unused-head accounting against
the pinned oracle before avoiding their materialization; keep the original artifact intact.

Preprocessing is part of the model: schema parentheses, `[P]`, `[L]`, `[SEP_STRUCT]`,
`[SEP_TEXT]`, descriptions, text word boundaries and first-subword indices must match.
The publisher routes structural marker positions, rather than gathering every token whose
ID happens to be a marker. Test marker-looking strings inside labels and text.

Do not infer a usable context limit from the tokenizer's enormous sentinel value. The encoder
declares 512 positions while GLiNER `max_len` is null. Establish supported total schema-plus-text
length experimentally. Initial proposal: at most 512 encoded tokens, explicit overlength errors,
no silent truncation. If that excludes qualification cases, report coverage and address context
support before running the locked cohort; never drop cases after seeing their results.

### Public contract: schema classification, not decoder logits

Add a task interface in `models-api` (proposed name `SchemaClassificationModel`) and immutable
request/result records. Requests carry text, ordered tasks, ordered labels/descriptions,
single/multi-label mode, threshold and any supported activation/temperature options. Results
carry task identity, ordered scores, selected labels, and whether confidence quality is qualified.
Validate empty/duplicate labels, invalid thresholds/temperature, duplicate task names, limits
and unsupported features. Implement lifecycle and concurrent-call ownership explicitly.

Add a lifecycle-owning ModelJars classification runtime / factory using the existing snapshot
resolver. Prefer a dedicated `openClassificationRuntime` contract: the existing
`ModelJarDecisionRuntime` is tied to `InferenceBackend`, last-token letter logits and one
normalized answer space. `ModelJars.requireGenerativeModel` deliberately rejects typed-only
models. Preserve existing decision/chat callers; do not route GLiNER through those assumptions.

Reference decoding matters:

- Default single-label: softmax followed by argmax.
- Default multi-label: sigmoid, select scores **greater than or equal to** threshold (default 0.5).
- In the inspected reference, if no multi-label score passes, it returns the argmax label anyway.
  Preserve this behavior in the initial compatible contract, document it, and test the boundary.
  Do not advertise native abstention or an empty selection.
- Explicit activation overrides, positive temperature, optional top-k and tie ordering need
  either parity tests or explicit unsupported-option errors. Never silently substitute behavior.
- Multi-task inference jointly encodes the schema. It need not equal separate one-task calls;
  compare each mode to the matching oracle input, not to an assumed equivalence.

Scores are model scores, not an assertion of calibrated confidence. Do not force independent
multi-label sigmoid scores into a distribution summing to one. Existing `Choice`/`Verdict`
types may be adapted for compatible single-label consumers, but cannot define the full API.

### Vectors and Rust: adaptation and measured optimization

Rust shims are an explicitly supported implementation option, not restricted to accelerating
matrix multiplication. During PR 1, assess whether tokenizer semantics, tensor layout, an
upstream native component or another missing operation is better adapted through a narrow
Rust C ABI exposed to Java FFM. Put that integration in Models `backend-native`, behind the
same classification contract. Keep schema and task orchestration in Models; reuse an upstream
component where appropriate rather than porting it merely to satisfy a Java-only requirement.
Record the selected implementation, dependencies, licenses and supported platforms in an ADR.

For any shim, define buffer layout/dtype/length, ownership and lifetime, thread safety, error
translation, version negotiation and cleanup. Prevent Rust panics from unwinding across the
ABI. Test malformed inputs, repeated open/close and concurrent use; verify packaged native
libraries on every advertised platform. Report the actual executing backend in evidence and
runtime diagnostics. Do not label a native-backed implementation as pure Java.

The existing encoder already uses `VectorUtil.batchDotProductExact`, weighted-row operations,
and Models `TensorOps` normalization/softmax. Vectors also provides `F32ExecutionMatrix` with
batch multiplication. Reuse these first. Profile projections, relative-attention gathers,
normalization, preprocessing, allocations and weight loading separately.

If matrix operations dominate, compare the existing projection loop with a shared F32 batch
kernel at actual shapes (1024×1024, 4096×1024, 1024×4096) and actual sequence lengths. A Rust
FFM performance kernel belongs in Models `backend-native` when end-to-end results justify it;
adaptation shims can be necessary independently of a speedup. Generic Java vector primitives
belong in Vectors; model-specific semantics remain owned by Models even when implemented in Rust.
Check native-library packaging, CPU dispatch, thread ownership and oversubscription if added.

Do not retain an alternate worse path: experiment in isolated branches, retain measurements,
promote the winning implementation and remove its losing experimental switches. Preserve the
existing supported Java portability path where one exists; do not build or retain an inferior
GLiNER implementation solely to offer a second route. Unsupported platforms should fail clearly.
Every production optimization must pass
parity, held-out quality and existing DeBERTa reranker regression checks.

## Qualification: three independent obligations

### Dedicated comparison harness

Build a model-specific harness in Models (proposed location
`experiments/gliner25-decide/`) with independent Python-reference and Models JVM workers.
Use the publisher's supported local runtime as the primary external comparator. Do not force
this architecture through llama.cpp, Ollama, a GGUF conversion or a generative benchmark just
to reuse an existing harness. Their absence does not block qualification.

The harness needs:

- A versioned JSONL request format carrying case ID, text, ordered tasks/labels/descriptions,
  classification mode, threshold and supported options. Each worker adapts this common task
  into its native API. Responses contain selected labels, available scores, errors and timings.
- A launcher that starts workers separately, verifies readiness and snapshot/runtime hashes,
  controls thread budgets and case order, applies timeouts, and retains stderr and raw records.
  Workers stay loaded for warm measurements; cold startup/load is measured separately.
- A comparison/report generator that joins by case ID, detects missing/duplicate responses,
  includes failures in coverage, evaluates held-out labels and produces machine-readable
  quality/performance evidence. Failed cases must not disappear from latency reports.
- Separate worker-side inference timing and controller-observed request latency. Include
  preprocessing and decoding in end-to-end inference; report transport/serialization overhead
  separately and use symmetric measurement boundaries. Record batching and concurrency.
- A small schema/protocol test suite with synthetic workers, followed by the remote real-model
  runs. No Python reference dependency is required in a user's ModelJars installation.

Keep the external worker a thin wrapper around the pinned publisher inference API. Compare its
outputs with direct publisher calls on fixtures before accepting it as the reference. Do not
silently replace its decoder, normalization or classification rules with our implementation.
Its readiness response must attest the loaded snapshot/config/tokenizer hashes, runtime and
remote-code revisions, dtype, effective device placement and thread settings. Cross-check that
attestation with the launch manifest; a client-supplied artifact path alone is not proof of what
the server loaded. Fail on unexpected CPU/GPU fallback or mixed placement. Isolate and lock each
worker's dependencies, including native components, so another experiment cannot change them.

Reuse proven process control, evidence hashing and replication utilities from other modality
harnesses where suitable; keep GLiNER request semantics, quality metrics and policy independent.
Identical raw documents and ordered schemas are required for matched comparisons; record each
adapter's processed inputs as well. Matched generated-token counts are a generative-only rule:
GLiNER instead matches total encoded input length, labels, tasks, batch size and concurrency.

There are two comparison levels. **Implementation parity** uses the same checkpoint and
matched semantics to diagnose the Models port with exact preprocessing and numerical tolerances.
**Operational comparison** asks how comparable tasks perform through the two real runtimes:
same documents, label choices, quality targets and hardware budget, but potentially different
internal kernels, batching, API shapes or supported preprocessing paths. It need not be an
exact internal execution match or produce byte-identical floating-point results.

Disclose every operational difference and report each arm's absolute quality, latency and
memory use. If a different quantization/checkpoint or materially different task mapping is
needed, identify it as a separate comparator, not an oracle. The parity tolerances and proposed
oracle-relative gates below apply to the matched-reference experiment; set a separate policy
before running a non-equivalent comparison. A favorable approximate comparison cannot replace
correctness tests or establish that the Models implementation faithfully loads the checkpoint.

### 1. Publisher parity

Before performance tuning, commit small oracle fixtures with input schemas, token IDs,
attention masks, marker positions, selected intermediate states, logits, scores and decisions.
Include Unicode/whitespace/punctuation, descriptions, several tasks, label permutations,
reserved-marker text, ties, threshold boundaries, no-label-above-threshold, and overlength inputs.

Proposed initial numerical contract: exact preprocessing and selected-label agreement on
deterministic fixtures; `abs(models - oracle) <= 1e-4 + 1e-4 * abs(oracle)` for final logits and
scores, finite values throughout. Register this before Models measurements. A failure triggers
layer-by-layer investigation, not an automatic tolerance increase. Large-corpus differences
near ties/thresholds must remain visible in reports even when aggregate accuracy is unchanged.
Changing tolerances requires an explained policy revision and fresh evidence.

### 2. Accuracy, generalization and confidence

Existing `DecisionCalibrationQualification` uses `decision-calibration-v2`: overall accuracy
≥0.70, choice ≥0.60, noul ≥0.70, and ECE ≤0.10 for `CALIBRATED_DECISION`; otherwise a passing
accuracy result can be `RANKED_DECISION`. It is coupled to ModernBert question types and a
Laya-specific rule for uncalibrated choice buckets with 11+ options. The ModelJars generator
also fixes policy v2 and backend `pure-java`; its manifest lacks multi-label/performance metrics.
**Do not fabricate Laya buckets or weaken/rewrite existing qualifications to admit GLiNER.**

PR 1 should register a versioned schema-classification policy and compatible evidence extension:

- Reuse JevBench v1.1's pinned 120-case cohort for a comparable single-label decision view,
  retaining the existing accuracy floors and reporting every supported question type. Freeze
  the mapping from evidence/question/options into GLiNER tasks before scoring.
- Reproduce the available publisher `fast-decisions` snapshot, recording actual split names,
  row counts and per-domain exact match. The model card describes 300 cases/domain while the
  viewed dataset exposes a 100-row `train` subset; resolve the pinned inventory instead of
  assuming the claimed benchmark is fully downloadable. Treat this as publisher evidence,
  not proof of independence from training.
- Add independently maintained single-label and multi-label test sets. Proposed sources:
  CLINC150 and GoEmotions. Pin licenses, revisions, splits, class names, label descriptions,
  hashes and deterministic selection before evaluation. Large label sets may exceed the initial
  context budget; define and disclose a fixed domain-scoped CLINC task rather than secretly
  reducing labels per example or selecting labels using the answer. No claim of a full CLINC150
  benchmark from such a subset. Public test sets do not prove absence of pretraining exposure.
- Report exact match, macro/micro F1, per-domain/class precision/recall, label-order sensitivity,
  OOS behavior and coverage. Test label permutations against the oracle; do not assume the model
  is invariant. OOS diagnostics do not establish an abstention capability.
- Proposed additional floors: macro-F1 ≥0.70 on the locked independent single-label suite;
  multi-label micro-F1 ≥0.70 and macro-F1 ≥0.60. Require Models versus oracle accuracy/F1 loss
  ≤0.5 percentage points on each cohort. These are **proposed new admission thresholds**, not
  current catalog policy or measurements; register the final policy before either arm runs.
- Keep a separate calibration/development split for any temperature or threshold fitting;
  never tune on qualification labels. Measure single-label ECE and multi-label calibration
  separately. Export confidence-quality claims only for the modes that pass their registered
  calibration gate; a single-label ECE must not qualify multi-label scores.

If the base model fails quality, preserve the failing report and investigate mapping/reference
correctness. A separately pinned fine-tune may be evaluated on untouched data against its base;
do not lower a threshold after results or publish an unrelated article claim as qualification.

### 3. Performance and released-runtime repeat

Use disposable remote CPU workers, never a local weight download or local full-model run.
Start on one Linux x86_64 AVX2 host with sufficient RAM; record exact CPU, physical/logical cores,
OS, JDK, vector width, Rust/native versions if used, Python/Torch dependencies and all thread counts.
Use serial, counterbalanced Models/reference arms on the same host and immutable snapshot.
The Models arm includes its actual Java/FFM/Rust execution path and all required shim overhead.
Verify both arms execute on the same compute class and resource allocation, not merely the same
host. For the initial CPU qualification, both must report CPU execution with matched CPU/thread
budgets. A later CPU-versus-GPU deployment comparison can be informative but cannot satisfy this
implementation-performance gate. An unsupported comparator is recorded as unavailable, not
silently replaced with a different device or an assumed publisher benchmark number.
Pin the oracle's actual attention implementation; compare against a supported efficient CPU
reference configuration, not an accidentally slow implementation.

Measure cold load, peak RSS, warm end-to-end p50/p95, preprocessing/encoder/head times,
decisions/sec and document throughput. Include schema cost in encoded lengths. Register a fixed
matrix covering 32/128/256/512 total tokens, 2/8/32 labels where they fit, one/several tasks,
and batch 1 plus a representative throughput batch if supported. Unsupported combinations
are stated up front. TTFT and generated tokens/sec are inapplicable.

Proposed performance admission: Models p95 end-to-end ≤1.50× reference and throughput ≥0.80×
reference for each advertised workload bucket, with a warm-up policy and at least 30 timed
samples per bucket. Require at least three paired runs of the final frozen implementation,
configuration and policy, with counterbalanced arm order. A screening run counts only if all
those inputs were already frozen; tuning resets the final replication sequence. Every pair
must pass each advertised bucket. Publish per-pair ratios plus mean, range and standard deviation;
do not infer a universal noise percentage from another model's measurements.
These are new proposed classification gates, not automatically inherited RAG policy. Record
absolute latency and RSS alongside ratios; do not advertise suitability for a deployment SLO
that was not tested. Any tighter memory/latency requirement must be registered before evaluation.

The 1.95 GB checkpoint is only a storage lower bound; mapping, heap copies, relative projections,
activation buffers and batch sizes determine actual RSS. Do not infer laptop speed or a RAM
requirement from parameter count. A later Intel i9-14900HX repeat can establish that hardware's
profile; do not transfer an AMD result to a hybrid Intel CPU by assumption.

## Ordered PRs and exit criteria

| PR | Repository / deliverable | Must be true before proceeding |
| --- | --- | --- |
| 1 | Models: dedicated two-worker harness, pinned Python reference, dependency lock, fixtures, task mappings, cohort manifests, registered policy and Rust-adaptation assessment | Offline reference loads exact snapshot; classifier-only execution verified; matched versus operational comparisons declared; limits/metrics fixed; small parity fixtures committed. No Models quality or speed claim yet. |
| 2 | Models: shared DeBERTa encoder and tokenizer boundaries; F32 loading, namespace support and required Rust shims | Synthetic tensor/config and any ABI/packaging tests pass; existing F16 DeBERTa reranker numerical tests pass; remote existing-model reranker repeat shows no regression. No duplicated encoder path. |
| 3 | Models: GLiNER schema compiler, classifier head, classification API and adapter | Exact preprocessing and numerical/decision parity pass, including multi-label fallback; lifecycle/limits/concurrency tests pass. |
| 4 | Models, and Vectors only if justified: remote quality/performance runs and targeted optimization | All registered gates pass, losing implementations removed, results reproducible; publish Vectors only if changed, then Models with the tested implementation. |
| 5 | ModelJars: snapshot-backed classification runtime, evidence schema/generator/registry, marker metadata, CLI/site classification demo | Download/cache/offline/invalid-digest tests pass; old decision and published-model routes unchanged; no generation/tool claims; ordinary build and catalog CI green. Can be developed alongside PRs 3–4. |
| 6 | Models evidence + ModelJars admission: released-artifact repeat, generated qualification/profile and marker publication | Clean consumer resolves released libraries; all final gates repeat; immutable evidence links/digests committed; marker POM/JAR available on Central; generated CLI demo runs from a clean cache on remote host; Pages deploy and public catalog checked. |

Qualification manifests must derive metrics from committed raw outcomes, preserve provenance
for every snapshot file and runtime dependency, and reject stale/mismatched evidence. Extend
Node/Java schema tests for both the new entry and existing Laya entries. Existing published
qualifications are append-only and remain valid; this work does not unqualify any model.

PR 6 is the admission point. Until then, retain candidate status and unavailable runtime flags.
Only advertise tested backends, modes and environments. Do not cancel running CI; investigate
failures and rerun the affected checks. Publication must wait for real Maven availability to
avoid repeating the recent Pages failure on an artifact still returning 404.

Planning estimate for one engineer, assuming checkpoint/reference compatibility: roughly
2–3 days for oracle/policy, 4–7 for encoder/tokenizer adaptation, 3–5 for classification/API,
3–6 for qualification/performance work and 2–4 for catalog/release integration: **14–25 working
days**, with overlap possible. This is an effort estimate, not a qualification promise. Major
tokenizer divergence, a native kernel requirement, or failed base-model accuracy changes the
estimate; checkpoint the evidence after PR 1 and after the first end-to-end parity result.

## Primary sources and code reviewed

- [Pinned checkpoint configuration](https://huggingface.co/fastino/GLiNER2.5-Decide/blob/9d1bfb848cd16d93ee56ccbc59cd92bf0f284d90/config.json) and [tokenizer settings](https://huggingface.co/fastino/GLiNER2.5-Decide/blob/9d1bfb848cd16d93ee56ccbc59cd92bf0f284d90/tokenizer_config.json).
- [Publisher schema preprocessing](https://github.com/fastino-ai/GLiNER2/blob/ca4f68235b50fe4c3ecf3876851d748fe4b15c6c/gliner2/processor.py), [classification head](https://github.com/fastino-ai/GLiNER2/blob/ca4f68235b50fe4c3ecf3876851d748fe4b15c6c/gliner2/models/span/model.py), and [decoding semantics](https://github.com/fastino-ai/GLiNER2/blob/ca4f68235b50fe4c3ecf3876851d748fe4b15c6c/gliner2/inference/runtime.py).
- [Model card](https://huggingface.co/fastino/GLiNER2.5-Decide), [publisher dataset](https://huggingface.co/datasets/fastino/fast-decisions), [CLINC150 source](https://github.com/clinc/oos-eval), and [proposed GoEmotions source](https://github.com/google-research/google-research/tree/master/goemotions). Dataset eligibility and exact inventories are PR 1 deliverables.
- Models paths above, plus `backend-java/.../bert/DecisionCalibrationQualification.java` and `models-decisions/.../AnswerSpace.java`, at the Models revision in the inventory.
- ModelJars `ModelJars.java`, `ModelJarDecisionRuntime.java`, `tools/generate-decision-qualifications.mjs`, and `catalog/decision-qualifications.json` at this branch's base.
- Vectors `vectors-core/.../F32ExecutionMatrix.java` and the operations used by `DebertaV2ForwardPass`, at the Vectors revision in the inventory.

Research for this plan fetched source/config/tokenizer metadata and a bounded safetensors header
range only. No model weights were fully downloaded and no model inference was run.
