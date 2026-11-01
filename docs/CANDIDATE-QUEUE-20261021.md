# Candidate queue, 2026-10-21 — from the playlist intake, triaged

Source: `tools/playlist-intake/candidates.py` over 203 transcripts (105 newly fetched the same
day). Every name below came out of a video; **nothing here is a measurement**, and the only facts
that are ours are the GGUF headers triaged for this document.

Ordered by the rules that already exist: `docs/MODALITY-ROADMAP.md` (modality gaps and their
gates) and `models/docs/CAMPAIGN-RUNBOOK.md` (smallest-first, triage before code, a new modality
needs a new gate, two arms for any verdict).

## Added 2026-10-31: GLiNER2.5-Decide

Requested from [Mohit Mayank's duplicate-detection article](https://pub.towardsai.net/gliner2-5-decide-fine-tuned-local-system-one-model-jev-level-accuracy-7-faster-367f82c01bf7).
The linked public checkpoint is [fastino/GLiNER2.5-Decide](https://huggingface.co/fastino/GLiNER2.5-Decide),
now candidate `fastino_gliner2_5_decide_f32`. No qualification or publication is claimed.
The article's fine-tuned duplicate-detection adapter has not been identified; its reported
Jev comparison does not establish the base checkpoint's quality or our runtime's speed.

- Revision: `9d1bfb848cd16d93ee56ccbc59cd92bf0f284d90`.
- Weights: `model.safetensors`, 1,945,828,140 bytes, SHA-256
  `40a5a23ff860dc3dff426cecd1048cacdd29c648c96db209dad818e9686dc997` from publisher LFS metadata.
- Inspected by HTTP range: 419 F32 tensors, **486,444,053 total parameters**.
  The card's 340M label is not the complete stored parameter count.
- Pinned config: `gliner2`, `Gliner2ForSchemaExtraction`, DeBERTa-v3-large encoder
  (`deberta-v2` configuration), 24 layers, width 1024, relative attention; Apache-2.0.
- Six snapshot files are pinned. Java/Rust support is unavailable; `gliner2` identifies
  the published Python reference implementation, not an in-JVM execution route.

Support requires DeBERTa attention/tokenization, schema-marker preprocessing and GLiNER2
classification scoring, followed by a Models typed-decision adapter and ModelJars snapshot loading.
The [2026-11-01 implementation and qualification plan](gliner25-decide-support-plan.md)
identifies the reusable DeBERTa runtime, concrete F32/tokenizer/API gaps, proposed gates,
and the ordered PRs through released-runtime validation and publication.
Qualify with publisher-output parity, label-order and multi-label tests, multiple independent
classification datasets, and same-host latency measurements. A task-specific fine-tune must beat
its untuned base on held-out data. The generative RAG gate is not an applicable substitute.

## Triaged here — real headers, read with `npm run catalog:triage`

| artifact | arch | runtime | towers | params | bytes | license | source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Laya-Q8_0.gguf` | **modern-bert** | **not served** | text only | 369,452,033 | 449,397,600 | apache-2.0 | `ggml-org/Laya-GGUF` |
| `MiniCPM5-2B-Q4_K_M.gguf` | llama | **served** | text only | 2,249,369,600 | 1,561,318,368 | apache-2.0 | `openbmb/MiniCPM5-2B-GGUF` |
| `VibeThinker-1.5B.Q4_K_M.gguf` | qwen2 | **served** | text only | 1,543,714,304 | 1,117,321,472 | mit | `mradermacher/VibeThinker-1.5B-GGUF` |
| `whisper-tiny-Q5_K_M.gguf` | whisper | **not served** | enc/dec/frontend | — | 44,211,616 | apache-2.0 | `handy-computer/whisper-tiny-gguf` |
| `MiniCPM5-1B-Q4_K_M.gguf` | llama | served | text only | 880,092,672 | — | apache-2.0 | **already in the catalog and qualified on `coding`** |

Pinned revisions and digests, so a later run cannot drift onto a different artifact:

| artifact | revision | sha256 | bytes |
| --- | --- | --- | --- |
| Laya Q8_0 | `845f314dd047fc2444a5289a4d50c1e1d59e27e1` | `c06528c5746d3bb8baa72a27938be95abbfd0b226f8471e8a9e365ed0bb066d2` | 449,397,600 |
| Laya BF16 | `845f314dd047fc2444a5289a4d50c1e1d59e27e1` | `edeea94939cd1575c1f984c28982170c0e47caab66e4b80993fed992519573b4` | 844,026,720 |
| MiniCPM5-2B Q4_K_M | `2079a22f3beaa4e306449978533478fe0522f4b3` | `ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd` | 1,561,318,368 |
| MiniCPM5-2B Q8_0 | `2079a22f3beaa4e306449978533478fe0522f4b3` | `c5415f8989bf88a8288f1b55a3cc371af53c07b0faa220a63bd7a990cfaba078` | 2,679,710,688 |
| VibeThinker-1.5B Q4_K_M | `d0d66139a78030a92a582f966b0f7cbbb3b19406` | `59fa32aaaf5c4512452a9bff8bb6d08f1e39a75acee226b5e01372f7509e3458` | 1,117,321,472 |
| VibeThinker-1.5B Q8_0 | `d0d66139a78030a92a582f966b0f7cbbb3b19406` | `4243d9c5de7ba3d682403c8f25708de5efb4eb6f6878898f4c771afe098a3bba` | 1,894,532,864 |
| whisper-tiny Q5_K_M | `1bd61cda36bbd22c88fc5a33842498a6e1d30f45` | `72cfa8ee436a635a5b6fb373cc056a828b9efe96d32d6eb8769ed3cc5b429719` | 44,211,616 |

## Tier 1 — the runtime and the gate both already exist

These need no adaptation. `llama` and `qwen2` are served, the artifacts are text-only, and
`production-rag-model-contribution-v6` is the gate they are measured against. The work is a catalog
entry and a two-arm shard.

### `minicpm5_2b_q4_k_m`

`llama`, 42 blocks, 2048 embedding, head_count 16 / head_count_kv 2, context 131072,
rope.freq_base 5,000,000, vocab 130,560, 381 tensors, no towers. Apache-2.0, **first-party GGUF**
from OpenBMB rather than a community requantization.

Its 1B sibling `minicpm5_1b_q4_k_m` is already qualified on workload `coding`, which gives this
model something most new candidates do not have: a recorded precedent for `wl`, `tpl`, `mt` and
`dt` on the same family and the same architecture. Use it, and say so in the shard rather than
deriving the fields — `scripts/fleet/build-shards.py` refuses to derive them for exactly this
reason.

### `vibethinker_1_5b_q4_k_m`

`qwen2`, 28 blocks, 1536 embedding, head_count 12 / head_count_kv 2, context 131072, 339 tensors,
no towers. MIT. Base model is `Qwen/Qwen2.5-Math-1.5B`, and the catalog already carries
`qwen2_5_math_1_5b_instruct_q4_k_m` **qualified on workload `math`** — the same base, the same
architecture, a recorded precedent, and the runbook's own worked example of why `general` is the
wrong corpus for a math specialist.

Two things to carry into the shard rather than discover on a box:

- `rope.freq_base = 640,000`, which is neither Qwen2.5's 10,000 nor the 1,000,000 of its
  long-context variants. It is the fine-tune's own value and is read from the header, not assumed.
- The GGUF is a community requantization (`mradermacher`). WeiboAI publishes no GGUF for the 1.5B.
  That is normal for this catalog but it is a provenance fact, and the entry pins the digest above.

A 3B VibeThinker GGUF also exists (`mradermacher/VibeThinker-3B-i1-GGUF`, MIT). Smallest-first says
the 1.5B goes first and the 3B only follows if the 1.5B qualifies.

## Tier 2 — Laya, which is the find, and also the most work

`ggml-org/Laya-GGUF`, apache-2.0, 421M label / 369M parameters, 449 MB at Q8_0, published by
ggml-org. `pipeline_tag = text-classification`. Two reasons it outranks everything else in value and
two reasons it cannot be qualified this week.

**What the header says it is.** `general.architecture = modern-bert`: 30 blocks, 1024 embedding,
16 heads, `attention.causal = false`, `sliding_window 128` with `sliding_window_pattern 3`
(alternating local and global attention), **two rope bases** — `rope.freq_base 160,000` global and
`rope.freq_base_swa 10,000` for the windowed layers — GeLU, vocab 50,368, context 8,192. Tensor
roots `blk` (191), `cls` (6), `token_embd`, `token_embd_norm`, `token_types`, `output_norm`.

**And it is a typed, calibrated decision model, which is our own problem domain.** The header
carries `decision.type = laya`, `decision.block_count = 2`, `decision.max_head_tokens = 192`, and a
family of calibration temperatures declared *per decision arity*:

| key | value |
| --- | --- |
| `decision.temperature.choice` | 1.6369030475616455 |
| `decision.temperature.choice.2` | 1.9063563346862793 |
| `decision.temperature.choice.3_5` | 1.7601518630981445 |
| `decision.temperature.choice.6_10` | 1.0000158548355103 |
| `decision.temperature.choice.11` | 0.10058280825614929 |
| `decision.temperature.noul` | 1.983399510383606 |
| `decision.temperature.noul.2` | 1.983399510383606 |
| `decision.temperature.score` | 1.2514300346374512 |
| `decision.temperature.score.3_5` | 1.2514300346374512 |

A binary choice is scaled by 1.906 and an 11-way choice by 0.101 — a fifteen-fold difference in how
the logits are tempered depending on how many options the question has. That is a published
position on something we measured ourselves on 2026-10-21: our own decision arm's confidence is
uncalibrated by 14 to 30 points, and its gate reads a single threshold with no arity term at all
(`articles/models-deep-dive/experiments/decision-threshold`). Whether per-arity temperature is the
reason, and whether it transfers, is a measurement nobody here has made.

**Why it is not a Tier 1 job.** Two gates are missing, not one:

1. **`modern-bert` is a new architecture.** The catalog serves `bert`, `nomic-bert`,
   `jina-bert-v2`, `deberta-v2` — not this. The genuinely new concepts are the alternating
   sliding-window/global pattern and the **two rope bases**, which is the failure mode the runbook
   warns about: a wrong rotary layout scrambles position without failing a shape assertion, and a
   fixture whose window is uniform cannot fail on the axis it holds constant. Any test here must
   exercise a windowed layer and a global layer with different bases and prove the assertion is
   sensitive by flipping them.
2. **No policy describes what it does.** `correctAnswerRate` is a grounded-RAG pipeline metric and
   this model emits a typed decision with a calibrated probability. It wants the shape of gate the
   roadmap already specifies for safety classifiers — precision and recall against a committed
   labelled set, two arms on one host — plus a calibration term, because a decision model whose
   probability is wrong is not fit for the one job it exists to do. The method is already written
   down and reproduced: reliability bins, ECE, Brier, and Platt scaling fitted out-of-fold.

Proposed phase number: **0.6**, after safety classification (0.5) and before ASR (1), because it
shares 0.5's gate shape and adds one encoder rather than a modality.

## Tier 3 — already on the roadmap, confirmed available

| candidate | phase | status this document adds |
| --- | --- | --- |
| `whisper-tiny` Q5_K_M, 44 MB, apache-2.0 | 1 (ASR) | the roadmap's named target; artifact and digest confirmed present, 6 quants published |
| MiniCPM-V 4.7 family | 2 (vision) | the small member is a community fine-tune; OpenBMB's own small V-series GGUF was not found. Prefer the roadmap's `Qwen3-VL-2B` target |
| `Breeze-TTS-2` (`cstr/breeze-tts-2-GGUF`) | 4 (TTS) | license is **`other` and tagged non-commercial**; a new TTS architecture on top. Lowest priority of the four |

## Deferred — the size tail, by the catalog's own definition

Small and medium models are the catalog, not a priority ordering within it. These were named in the
corpus and are recorded so the decision is visible rather than silent:

| named | smallest published form | why deferred |
| --- | --- | --- |
| Qwen3.8-27B | `unsloth/Qwen3.8-27B-GGUF` | 27B; the tail |
| GLM-5.2 / 5.3 | `unsloth/GLM-5.2-GGUF` | frontier-scale MoE; the tail |
| Tiel-Coder-35B-A3B | `peculiar-ragdoll/...-GGUF` | 35B |
| Kimi-Linear-48B-A3B | `bartowski/...-GGUF` | 48B |
| kona2-12B | `mradermacher/kona2-12B-i1-GGUF` | 12B, above the median by an order of magnitude |
| Nemotron-3-Nano-4B | none found | no GGUF published; custom `nemotron_h` modeling code; license `other` |
| ModernBERT base/large | none found | no GGUF in the top results. The `modern-bert` runtime arrives with Laya; revisit then |
| Aleph, Kona, DINO, Mamba, JEPA, NVFP4, Energy-Based Model, Barlow Twins, VICReg | — | not models we can pin: two are research groups, the rest are architectures, formats or training methods, which the intake tool counts as names because that is all a transcript contains |

## The order of work, with the gate that ends each step

1. Catalog entries for `minicpm5_2b_q4_k_m` and `vibethinker_1_5b_q4_k_m` — `npm run catalog:triage`
   prints no `!!`, `npm test`, `npm run catalog:profiles:check`.
2. One two-arm shard per workload: `coding` for MiniCPM5-2B, `math` for VibeThinker — settings
   copied from the recorded precedent, both arms given the same `--threads`, verdict files present
   for both arms before anything is called a result.
3. Default-configuration smoke for whichever qualifies, under library defaults with no
   `-Dmodels.*`, before any publish.
4. Laya: phase 0.6 written into `MODALITY-ROADMAP.md` with its two gates, then the `modern-bert`
   encoder behind a positional ablation, then the decision policy.
5. Whisper phase 1 is unchanged by this document except that its artifact is now pinned above.

Nothing in steps 2 onward has been run. This file is prepared input.
