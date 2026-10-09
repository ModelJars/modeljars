# Entries set aside by the 0.3.54 catalogue smoke

**Nothing here is removed from the catalogue.** The catalogue cannot shrink, and none of these is a
re-grading of a model: every one is still published, still qualified, and still carries the evidence
it was qualified on. This is a work list, kept so the eight entries the
2026-10-09 smoke did not clear are tracked somewhere other than a shard log.

Smoke: 65 of 65 entries reported, **57 PASS**. Protocol and results in the models repository at
`benchmark-results/2026-10-09-catalogue-smoke-0354/NOTES.md`.

## 1. Six entries miss one case of nine, and 0.3.54 did not cause it

| entry | published correctAnswerRate | smoke | failing case |
|---|---|---|---|
| `smollm2_360m_instruct_q8_0` | 1.0 | 0.889 | `telemedicine-benefit` — model abstained |
| `eurollm_1_7b_instruct_q4_k_m` | 1.0 | 0.889 | `berlin-sicherung` — factCoverage 0.5 |
| `huggingfacetb_smollm2_1_7b_instruct_gguf_q4_k_m` | 1.0 | 0.889 | one case, factCoverage 0.5 |
| `qwen2_5_coder_1_5b_instruct_q4_0` | 1.0 | 0.889 | one case, factCoverage 0.5 |
| `qwen2_5_coder_1_5b_instruct_q8_0` | 1.0 | 0.889 | `http-retry-rules` — factCoverage 0.5 |
| `umarfarookm_umartransit_1b_q4_k_m` | 1.0 | 0.889 | one case, factCoverage 0.5 |

**Measured, not assumed:** the same six were re-run under the identical worker, shard and gate
against Models 0.3.53, with the library as the only variable. `correctAnswerRate` is
`0.8888888888888888` on **both** releases for all six. The release is exonerated; the entries are not
yet explained.

The published 1.0 was measured at `warmups=1, iterations=3`; the smoke runs `0/1` by the runbook's own
gate. So the open question is **not** "did 0.3.54 break these" but "does a single cold iteration
expose a case these models only pass on a later attempt". Retrieval was perfect in every failing case
(`retrievalRecall 1.0`, `reciprocalRank 1.0`), so it is the answer and not the lookup.

**Testing to do:** run each at `iterations=3` under the smoke protocol and see whether the case passes
on attempts 2 or 3. If it does, the smoke gate needs to state its iteration count as part of the
claim. If it does not, these six have a real gap at their published template and workload, and
`modelAnswerRate` (0.333-0.778 for this group) is where to look.

## 2. One entry's download URI does not return a GGUF

`qwen_qwen2_5_0_5b_instruct_bf16` failed with
`MalformedGgufException: Invalid GGUF magic: 0x00007E18 (expected 0x46554747)`.

This is a **catalogue defect, not a model defect**: the smoke fetched exactly what
`catalog/models.json` says to fetch and got something that is not a GGUF. Most likely a Hugging Face
LFS pointer or an HTML error page served by the pinned `downloadUri`.

**Testing to do:** fetch that URI and inspect the first bytes. Fix the `downloadUri`, then re-smoke
this one entry. Until then its 0.3.54 status is **unknown** — it is neither passing nor failing.

## 3. One entry is safetensors and the smoke worker cannot fetch it

`facebook_mobilemoe_s_qat_int4_g32` is `format: safetensors`, a Hugging Face *directory*.
`smoke-worker-0354b.sh` handles single-file artifacts only; `qual-worker-two-arm.sh` already handles
the `files` array form.

**Testing to do:** port the `files` handling into the smoke worker, then smoke this entry. Its 0.3.54
status is likewise **unknown**, which is a worker limitation and nothing about the model.

## What must not be concluded from this page

- that any of the eight is a regression — the six are measured identical on the previous release, and
  the other two were never measured
- that the two unmeasured entries pass — absent is not passing
- that the six should be dropped — a metric change that would drop an entry is a bug in the change
