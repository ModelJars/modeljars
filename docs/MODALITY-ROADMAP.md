# Modality roadmap — the plan, with gates

**Status: committed plan, tracked here. Not a conversation.** This file exists because a list of
candidate families was given verbally, acted on partially, and then lost. Anything not written here
with a gate beside it will be lost again.

Last verified against the published catalog on **2026-10-09**.

## Where we are

**100 published models. One modality that is not text.**

| modality | published | runtime | gate that exists |
| --- | --- | --- | --- |
| text generation / chat | 66 | 20 architectures | `production-rag-model-contribution-v6` |
| text embedding | 30 | bert, nomic-bert, qwen3, gemma-embedding, lfm2 | `oracle-equivalence-v1` |
| reranking | 2 | deberta-v2 | reranking policy |
| text-to-speech | 2 (one model, two quants) | soprano only | `speech-oracle-streaming-latency-v1` |
| speech-to-text | **0** | **none** | **none** |
| image understanding | **0** | **none** | **none** |
| OCR | **0** | **none** | **none** |
| video understanding | **0** | **none** | **none** |

Size range **0.013–15.64 GB**, median 0.87 GB. Parameters **22.6M–25.23B**, median 1.29B, per
`docs/catalog-parameters.json`. 62 of 100 under 1 GB. The catalog is broad and **nearly
single-modality**, and that is the thing this roadmap exists to change.

## Phase 0 — fix what is already wrong (no new runtime)

| # | item | gate |
| --- | --- | --- |
| 0.1 | `gemma_3n_e2b_it_q8_0` declares `image-understanding`, `audio-understanding`, `video-understanding`. Its artifact is 727 text-only tensors; upstream publishes no projector. Drop the three claims. | `npm run catalog:triage -- gemma_3n_e2b_it_q8_0` prints no `!!` |
| 0.2 | Run triage across every published entry and fix every capability/tensor mismatch it reports. | zero `!!` across the catalog |
| 0.3 | `facebook_mobilemoe_s_qat_int4_g32` returns HTTP 401 and refuses byte ranges. Needs an explicit availability flag so a reader does not get a 401. | entry carries the flag; site does not offer an undownloadable model |

Phase 0 is hours, not weeks, and it is a correctness debt that is live in public.

## Phase 0.5 — safety / moderation (no runtime work, cheapest remaining gap)

Guardrails are an enterprise requirement and the catalog has **zero** coverage. Unlike ASR, vision
and OCR, this one needs no new runtime: all three leading guardrail families are text classifiers on
architectures already served, verified by triaging the real artifacts on 2026-10-09:

| candidate | architecture | license | tensors |
| --- | --- | --- | --- |
| `granite-guardian-3.0-2b` Q4_K_M | `granite` | **apache-2.0** | 362 |
| `Llama-Guard-3-1B` Q4_K_M | `llama` | llama3.2 | 148 |
| `shieldgemma-2b` Q4_K_M | `gemma2` | gemma | 288 |

All three report `towers: none found (text only)`, so they load on the existing decoders.

| # | work | gate |
| --- | --- | --- |
| 0.5.1 | Catalog entries, smallest-first, preferring the Apache-2.0 one | triage prints no `!!`; digests pinned |
| 0.5.2 | New policy `safety-classification-v1`. These emit a safety verdict, not an answer, so `correctAnswerRate` describes nothing here. | precision and recall against a committed labelled set, two arms on one host, with the false-negative rate reported separately because a missed unsafe prompt is the costly error |
| 0.5.3 | Qualify | each with a released `backendVersion` |

The same reasoning applies to **NER** (small BERT-family token classifiers would run on the existing
`bert` encoder) and **text-to-SQL** (`sqlcoder_7b_2_q5_k_m` is already a catalog candidate on the
supported `llama` architecture). Both are blocked on a metric rather than a runtime:
`text-to-sql` needs query correctness against a schema, which the campaign runbook already says
wants its own policy, and NER needs span-level F1. Neither is a decoder problem.

## Phase 1 — speech-to-text (first new modality)

**Target:** `handy-computer/whisper-tiny-gguf`, Apache-2.0, 38M, `general.architecture = whisper`.
Then `base`, then `small`. Smallest-first, as the catalog requires.

The header specifies the whole frontend, so there is nothing to guess:
`num_mels 80`, `n_fft 400`, `win_length 400`, `hop_length 160`, `window hann_periodic`,
`pad_mode reflect`, `center true`, `sample_rate 16000`, `chunk_length 30`, `n_samples 480000`,
`nb_max_frames 3000`, `mel_norm slaney`, `normalize whisper_logmel`, `f_min 0`, `f_max 8000`;
decoder `d_model 384`, `ffn_dim 1536`, `n_heads 6`, `max_target_positions 448`, `activation gelu`;
`stt.capability.{timestamps, translate, lang_detect}` all true. Tensor roots are `enc` (67),
`dec` (100), `frontend` (2).

| # | work | gate — each one blocks the next |
| --- | --- | --- |
| 1.1 | STFT + log-mel frontend in Java, no model weights involved | a fixed WAV produces a mel matrix matching a committed reference within tolerance; the tolerance is written down before the test runs |
| 1.2 | Conv1d encoder stem + encoder transformer | encoder output for that WAV matches a committed reference |
| 1.3 | **Cross-attention.** Every decoder here is self-attention only. New block shape, and an encoder KV with a different lifetime from the token KV — computed once per 30 s window, not per token. | greedy transcription of a pinned clip is byte-identical across two runs in one process and across a restart |
| 1.4 | New qualification policy `asr-wer-v1`. `correctAnswerRate` does not describe transcription. | WER against a committed reference transcript on pinned audio, two arms on one host, kill-criterion stated before the run |
| 1.5 | Qualify `tiny`, then `base`, then `small` | each lands with `backendVersion` naming a **released** library, per the rule that bit us on 0.3.54 |

**Why this one first:** one new modality, a self-contained frontend, 38M to iterate on, Apache-2.0,
and the only genuinely new runtime concept is cross-attention.

## Phase 2 — vision (two modalities at once)

**Target:** `unsloth/Qwen3-VL-2B-Instruct-GGUF`, Apache-2.0. **Two files, two architectures.**

Text tower, `general.architecture = qwen3vl` — **not `qwen3`**, so the existing decoder does not
load it: `block_count 28`, `embedding_length 2048`, `feed_forward_length 6144`,
`head_count 16`/`head_count_kv 8`, `key_length`/`value_length 128`, `n_deepstack_layers 3`,
`rope.dimension_sections` a 4-element array, `rope.freq_base 5000000`, `context_length 262144`.

Projector, `general.architecture = clip`, 316 tensors, roots `v` (312) and `mm` (4):
`projector_type qwen3vl_merger`, `vision.block_count 24`, `embedding_length 1024`,
`feed_forward_length 4096`, `attention.head_count 16`, `patch_size 16`, `image_size 768`,
`projection_dim 2048`, `spatial_merge_size 2`, `is_deepstack_layers` 24 entries, `use_gelu true`.

| # | work | gate |
| --- | --- | --- |
| 2.1 | Two-file loading: a model plus its projector as one logical entry, with both digests pinned | catalog entry resolves and verifies both files |
| 2.2 | ViT tower — patch embed 16px, 24 blocks, GELU MLP, layer norm | vision embeddings for a pinned PNG match a committed reference |
| 2.3 | `qwen3vl_merger` projector with `spatial_merge_size 2` | projected tokens match a committed reference |
| 2.4 | **mRoPE** — `rope.dimension_sections`, not the single-axis rope every current decoder uses | **a positional ablation**: the same image at two positions must produce the documented difference. Getting mRoPE wrong degrades silently with position, which is the worst failure mode, so a flat result here fails the gate rather than passing it |
| 2.5 | Deepstack — 3 text-side layers fed from 24 vision-side | an ablation switching deepstack off must change the output *observably*, or the wiring is not proven |
| 2.6 | Image preprocessing from the header's `image_mean`/`image_std` | preprocessed tensor matches a committed reference |
| 2.7 | New policy `vision-qa-v1` | two arms on one host; `correctAnswerRate` is not reused |

## Phase 3 — OCR (rides Phase 2)

Once a vision tower and projector work, OCR is **a catalog entry and a metric**, not another
runtime project. Verified available with projectors: `ggml-org/GLM-OCR-GGUF`
(`mmproj-GLM-OCR-Q8_0.gguf`, 348 tensors, MIT) and `datalab-to/surya-ocr-2-gguf`.
LightOnOCR-3 is also a candidate: its 0.8B/4B models use the Qwen3.5 VL architecture,
but GGUF and projector availability remain unverified in this inventory.

| # | work | gate |
| --- | --- | --- |
| 3.1 | New policy `ocr-page-accuracy-v1` | page accuracy against committed reference text; `correctAnswerRate` describes neither OCR nor layout |
| 3.2 | Image preprocessing for documents — the LightOnOCR post states 400 DPI with a 5 MP cap (~4.8k image tokens/page) | preprocessing is measured, and the token cost per page is reported, not estimated |
| 3.3 | Qualify smallest-first | each with a released `backendVersion` |

**Upstream olmOCR-Bench figures (86.3 / 85.5 / 84.5 for 4B / 0.8B / 1B, 4.78 and 3.36 pages/s,
2.7 s single-page) are vendor self-reported and have NOT been re-run here.** They are a reason to
look, never a number to publish.

## Phase 4 — more TTS, and audio input beyond ASR

Lower priority: TTS already exists as a modality, so these add breadth not reach.
`dots-studio/dots.tts-mf` (`dots_tts`, safetensors + separate speaker-encoder and vocoder files) and
`Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice` (`qwen3_tts`) both need new architectures **and** multi-file
loading, which Phase 2.1 delivers first.

## Rules that apply to every phase

1. **Triage before code.** `npm run catalog:triage -- <id-or-url>` on the real artifact. The header
   has repeatedly contradicted the model card — most recently gemma-3n claiming three modalities it
   cannot do.
2. **A new modality needs a new gate.** Four of the five phases above list a policy that does not
   exist yet. That is the half most likely to be underestimated: the runtime is visible, the gate is
   what makes an entry mean anything.
3. **Qualify against a released library.** An entry's `backendVersion` is a claim a user can check.
4. **Smallest-first, always.** It is more models, a fraction of the cost, and the product's real
   case.
5. **Publish the nulls.** If a tower measures no better than a baseline, that is the result.
6. **No number without provenance.** Nothing upstream-reported gets published as ours.

## What has not been done

**No code exists for any phase.** No accuracy, WER, page-accuracy or throughput number appears
above, because none has been measured here. Every architectural fact was range-fetched from the
artifact's own header with the triage tool; every upstream benchmark figure is labelled as theirs.
