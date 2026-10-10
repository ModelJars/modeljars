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

## 2. A Safetensors entry was passed to the single-file GGUF worker

`qwen_qwen2_5_0_5b_instruct_bf16` failed with
`MalformedGgufException: Invalid GGUF magic: 0x00007E18 (expected 0x46554747)`.

**Diagnosis corrected during the 2026-10-10 audit:** the catalog already declares this entry as
`format: safetensors`, with `model.safetensors` and three configuration/tokenizer files. This was
also true in the published `v0.1.54` catalog. The worker saves every single download as `$id.gguf`
and omits the companion files. The failure does not establish a defective download URI or an
LFS/HTML response; it demonstrates that this worker did not load the declared artifact format.

**Fix prepared for Models 0.3.57:** `scripts/fleet/smoke-worker.sh` fetches all four pinned files,
verifies every SHA-256 and size, and passes the snapshot directory to pure-java. Its synthetic
shell-loop regression checks the path received by Java and prevents Java from starting on a corrupt
tokenizer. `scripts/fleet/smoke-qwen-bf16.json` records the next run's files and settings.

**Measured on 2026-10-10:** after the user requested local installation, both real Qwen fixture
tests passed (tokenizer and recorded reference logits), and the Models 0.3.57 candidate passed
the nine-case default smoke with no failures and correct abstention. The model contributed on 5/9
cases, all correct; 3/9 used extractive fallback. Evidence is in Models
`benchmark-results/2026-10-10-qwen-bf16/`, with pinned input and runtime JAR hashes.

The candidate used staged Vectors 0.1.29 artifacts. Repeat the smoke against final released JARs
before binding released evidence; this is not a new comparative qualification. Its historical
0.3.54 model-correctness status remains **unknown**.

A refreshed `defaultConfigurationSmoke` may declare its own `modelsRevision` and `backendVersion`.
Both are required together: the revision must be an immutable 40-character commit, and the runtime
label must exactly match the hash-verified report. This keeps the older qualification's source
revision and measured runtime intact while a new default-smoke report lives at a later commit.

## 3. MobileMoE still needs a successful default smoke

`facebook_mobilemoe_s_qat_int4_g32` is `format: safetensors`, a Hugging Face *directory*.
`smoke-worker-0354b.sh` handles single-file artifacts only; `qual-worker-two-arm.sh` already handles
the `files` array form.

The new `smoke-worker.sh` supports this entry's four-file layout as well as Qwen's. That removes
the single-file worker limitation; it does not establish that MobileMoE passed inference. A successful
authenticated artifact fetch and default smoke are still required. Its 0.3.54 correctness status
remains **unknown**.

## What must not be concluded from this page

- that any of the eight is a regression — the six are measured identical on the previous release, and
  the other two were never measured
- that the two unmeasured entries pass — absent is not passing
- that the six should be dropped — a metric change that would drop an entry is a bug in the change
