# ModelJars Launch Catalog: 25 Distinct Models

Status: July 2026 planning baseline

## Counting rule

The launch target is 25 distinct upstream model identities. A second
quantization of the same weights is another artifact, not another model. For
example, Qwen2.5-Coder 0.5B Q4_0 and Q8_0 count as one model.

A model qualifies for the launch catalog only when it has:

1. A traceable upstream model and immutable downloadable GGUF artifact.
2. Explicit license and access metadata, including gating and noncommercial
   restrictions.
3. A ModelJars marker with a pinned revision, byte size, and SHA-256 digest.
4. A mandatory models-library test that downloads and runs the real artifact.
5. A tokenizer reference and deterministic inference oracle from an independent
   runtime such as a pinned llama.cpp build.
6. An honest backend claim. A marker must not advertise `pure-java=true` before
   that exact artifact passes the pure-Java contract.

The catalog currently contains 14 GGUF marker artifacts representing 12
distinct models. Qwen2.5-Coder 0.5B and 1.5B each have two quantizations.

## Launch roster

| # | Distinct model | Launch role | License/access | GGUF source posture | Pure-Java status and required work |
|---:|---|---|---|---|---|
| 1 | Qwen3 0.6B | Small general/chat fixture | Apache-2.0 | Official | Supported and mandatory. |
| 2 | Qwen3 1.7B | General small model | Apache-2.0 | Official | Supported and mandatory. |
| 3 | Qwen3 8B | Large general/reasoning model | Apache-2.0 | Official | Supported by an isolated exact-oracle slow-test job. |
| 4 | Qwen2.5-Coder 0.5B Instruct | Tiny code/FIM | Apache-2.0 | Official | Supported in Q4_0 and Q8_0. |
| 5 | Qwen2.5-Coder 1.5B Instruct | Small code/FIM | Apache-2.0 | Official | Supported in Q4_0 and Q8_0. |
| 6 | Qwen2.5-Coder 3B Instruct | Mid-size code/FIM | Qwen Research | Official | Supported in Q4_0. |
| 7 | Qwen2.5-Coder 7B Instruct | Large code/FIM | Apache-2.0 | Official | Supported by an isolated slow-test job. |
| 8 | SmolLM2 360M Instruct | Edge/general fixture | Apache-2.0 | Official | Supported and mandatory. |
| 9 | TinyLlama 1.1B Chat v1.0 | Small fine-tuning baseline | Apache-2.0 | Community GGUF | Supported; validates SentencePiece. |
| 10 | DeepSeek-Coder 1.3B Instruct | Small code/FIM | DeepSeek model license | Community GGUF | Supported; validates mixed K-quants. |
| 11 | DeepSeek-Coder 6.7B Instruct | Large code/FIM | DeepSeek model license | Community GGUF | Supported by an isolated slow-test job. |
| 12 | MiniCPM5 1B | On-device tools/reasoning | Apache-2.0 | Official | Supported; validates explicit Q/K/V widths and MiniCPM5 BPE. |
| 13 | DeepSeek-R1-Distill-Qwen-7B | Reasoning | MIT | Trusted community GGUF required | Near target: reuse Qwen2 and K-quant support; add pinned oracle and reasoning template. |
| 14 | Qwen2.5-Math 1.5B Instruct | Math/education | Apache-2.0 | Community GGUF required | Near target: reuse Qwen2; add math prompt/template and exact oracle. |
| 15 | SQLCoder-7B-2 | Text-to-SQL/data | CC-BY-SA-4.0 | Upstream Q5_K_M; pin a verified Q4_K_M conversion | Near target: Llama-family weights and SentencePiece; add SQL prompt resource and read-only-use warning. |
| 16 | EuroLLM 1.7B Instruct | Multilingual/translation | Apache-2.0 | Trusted community GGUF required | Near target: dense Llama-family structure; validate tokenizer and 35-language metadata. |
| 17 | Mistral 7B Instruct v0.3 | General/chat/tool calling | Apache-2.0 | Trusted community GGUF required | Add Mistral metadata, tokenizer v3, and sliding-window semantics. This is the Mistral-family foundation. |
| 18 | BioMistral 7B | Biomedical research | Apache-2.0 | Official GGUF | Reuse the Mistral foundation; add research-only health warnings and a deterministic biomedical-format smoke test. |
| 19 | SaulLM-7B Instruct v1 | Legal | MIT | Trusted community GGUF required | Reuse the Mistral foundation; preserve legal limitations and add a deterministic legal-format smoke test. |
| 20 | Llama 3.1 8B Instruct | Widely adopted general model | Gated Llama 3.1 Community License | Gated source or verified conversion | Add gated-artifact resolution, Llama 3 BPE, piecewise RoPE scaling, attribution metadata, and chat templates. |
| 21 | AdaptLLM Finance Chat 7B | Financial question answering | Llama 2 Community License | Trusted community GGUF required | Near target: reuse the Llama-family path; preserve base-model terms and add a deterministic finance prompt oracle. |
| 22 | Foundation-Sec-8B Instruct | Cybersecurity | Upstream NOTICE plus Llama 3.1 terms; verify before marker | Official Q8_0 GGUF; seek a trusted Q4_K_M if needed | Reuse the Llama 3.1 foundation; add security-use metadata and an exact domain prompt oracle. |
| 23 | OLMo 2 1B Instruct | Fully open training/fine-tuning | Apache-2.0 | Official GGUF | Add OLMo2 block semantics, tokenizer/template support, and an exact small-model oracle. |
| 24 | Granite 4.1 8B | Enterprise/tools/coding | Apache-2.0 | Official GGUF | Add Granite metadata and attention, embedding, residual, and logits scaling semantics before the 8B proof. |
| 25 | Phi-4 Mini Instruct | Compact reasoning/code/tools | MIT | Trusted community GGUF required | Add Phi-3/Phi-4 tensor layout, fused projections, long-context RoPE, tokenizer, and template support. |

## Why this portfolio is diverse

The roster spans 360M through 8B parameters and covers general chat, code,
fill-in-the-middle, reasoning, mathematics, SQL, translation, tool calling,
biomedicine, law, finance, cybersecurity, and transparent fine-tuning. It also
avoids making 25 by publishing many quantizations of a few Qwen checkpoints.

The implementation work is intentionally grouped by reusable foundations:

1. Existing Qwen/Llama foundation: DeepSeek-R1-Distill-Qwen, Qwen2.5-Math,
   SQLCoder, EuroLLM, and AdaptLLM Finance Chat.
2. Mistral foundation: Mistral 7B Instruct, BioMistral, and SaulLM.
3. Llama 3 foundation: Llama 3.1 Instruct and Foundation-Sec.
4. New independent foundations: OLMo 2, Granite 4.1, and Phi-4 Mini.

This sequence reaches 25 with five focused runtime foundations after the
current Qwen/Llama path rather than implementing 13 unrelated backends.

## Vertical-model qualification

Vertical models need stronger provenance and warning metadata than general
chat models. Popularity is supporting evidence, not proof of quality.

| Vertical | Candidate | Evidence observed in July 2026 | Decision |
|---|---|---|---|
| SQL/data | [SQLCoder-7B-2](https://huggingface.co/defog/sqlcoder-7b-2) | 436 likes and about 22K monthly downloads; upstream GGUF; published SQL-Eval results | Launch target. Preserve CC-BY-SA and warn that generated SQL must run through read-only credentials. |
| Healthcare | [BioMistral-7B-GGUF](https://huggingface.co/BioMistral/BioMistral-7B-GGUF) | Official Apache-2.0 GGUF, paper, 190 likes, and about 1K monthly downloads | Launch target for research and evaluation, not clinical use. The upstream card explicitly advises against professional medical deployment. |
| Legal | [SaulLM-7B-Instruct-v1](https://huggingface.co/Equall/Saul-7B-Instruct-v1) | MIT model, paper, 116 likes, and continued pretraining on a large legal corpus | Launch target after Mistral support. Do not represent output as legal advice. |
| Finance | [AdaptLLM Finance Chat](https://huggingface.co/AdaptLLM/finance-chat) | ICLR domain-adaptation work, 100 likes, and a finance model reported to compete with much larger domain models | Launch target after a community GGUF lineage audit. Preserve Llama 2 terms. |
| Finance | [Fino1-8B](https://huggingface.co/TheFinAI/Fino1-8B) | Newer Llama 3.1 financial-reasoning fine-tune with a paper, 35 likes, and community quantization | Watchlist alternate. Reconsider after Llama 3 support and GGUF lineage verification. |
| Cybersecurity | [Foundation-Sec-8B-Instruct](https://huggingface.co/fdtn-ai/Foundation-Sec-8B-Instruct) | Cisco Foundation AI release, technical report, 71 likes, and an official GGUF variant | Provisional launch target. Resolve the upstream NOTICE/license mapping before publication. |
| Insurance | [Mistral-7B-Insurance](https://huggingface.co/bitext/Mistral-7B-Insurance) | Apache-2.0 and technically reusable after Mistral support, but only about 155 monthly downloads and 3 likes | Watchlist; insufficient adoption evidence for a launch slot. |
| Insurance | [InsureLLM-4B](https://huggingface.co/piyushptiwari/InsureLLM-4B) | Apache-2.0 Qwen3 fine-tune, but 0 likes, 9 monthly downloads, and self-reported domain score 0.25 | Reject for the launch roster pending independent evaluation and adoption. |

ModelJars metadata must retain upstream risk statements. Catalog inclusion is
not an endorsement of medical, legal, financial, insurance, or security output.

## CI and acceptance contract

No real-model test may pass as skipped because a model is missing. The fixture
task must resolve the marker, download the immutable artifact, verify size and
SHA-256, and then run the test.

Each model acceptance PR must provide:

1. A failing marker-resolution test before catalog metadata is added.
2. A failing runtime test before its fixture task is added.
3. A parsed GGUF metadata and tensor inventory assertion.
4. Tokenizer IDs compared with the pinned reference runtime.
5. At least one greedy token sequence compared with the pinned reference
   runtime.
6. A model-specific CI cache key and mandatory job.
7. A vertical prompt resource and limitations metadata where applicable.

CI should be tiered without weakening acceptance:

- PR acceptance: run every model changed by the PR, plus every affected family
  when shared inference, tokenizer, quantization, or cache code changes. Small
  fixtures run in the default integration job; large fixtures run in isolated
  matrix jobs.
- Nightly: run all 25 cached model jobs.
- Weekly: run all 25 from a clean model cache to verify upstream availability,
  byte size, and digest.
- Release: require a complete 25-model matrix with zero skips.

## CI cache capacity and estimated cost

The GitHub Actions cache is an accelerator, not a source of truth. A July 2026
API snapshot of `integrallis/models` reported:

- repository cache limit: 10 GB;
- active usage: 9,131,752,244 bytes in two cache entries; and
- local size of the current 14 pinned GGUF artifacts: about 23 GiB.

The repository is already in cache-thrashing territory. GitHub's default is 10
GB per repository; older caches are evicted by last access when the limit is
exceeded. Paid cache limits are configurable. GitHub's published examples imply
$0.07 per additional GB-month: 50 GB fully used costs $2.80/month and 200 GB
costs $13.30/month. See the
[GitHub dependency caching reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching).

One selected CI quantization for each launch model is expected to require about
65-75 GiB before cache overhead. The initial recommendation is a 100 GB
repository cache limit, which is approximately $6.30/month when fully utilized
after the included 10 GB. This is an estimate from GitHub's examples, not a
quoted invoice. Do not raise the paid limit without explicit approval.

Keep cache entries per model and key them by immutable revision, variant, size,
and SHA-256. Do not cache every published quantization. Upstream download plus
digest verification remains the fallback, and gated weights must never be
copied into a public mirror. A persistent self-hosted runner or an authorized
read-through object store can replace paid Actions cache later if CI traffic
justifies the operational complexity.

## Models evaluated but deferred beyond launch

| Model | Reason for deferral |
|---|---|
| Qwen3.5 9B | Gated DeltaNet/linear recurrent state, gated attention, multimodal encoder, and MTP make it a major new hybrid runtime. |
| Gemma 4 E4B | Per-layer embeddings, hybrid local/global attention, proportional RoPE, and multimodal inputs make it substantially larger than a normal dense 4.5B integration. |
| Aya Expanse 8B | Useful multilingual model, but gated CC-BY-NC terms are a poor fit for the unrestricted launch roster. |
| Insurance-specific fine-tunes | Current candidates do not yet combine meaningful adoption, independent evaluation, clear lineage, and a strong immutable GGUF source. |

## Primary sources

- [Qwen3 8B official GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF)
- [DeepSeek-R1-Distill-Qwen-7B](https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B)
- [Qwen2.5-Math 1.5B Instruct](https://huggingface.co/Qwen/Qwen2.5-Math-1.5B-Instruct)
- [EuroLLM 1.7B Instruct](https://huggingface.co/utter-project/EuroLLM-1.7B-Instruct)
- [Mistral 7B Instruct v0.3](https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3)
- [AdaptLLM Finance Chat](https://huggingface.co/AdaptLLM/finance-chat)
- [OLMo 2 1B Instruct](https://huggingface.co/allenai/OLMo-2-0425-1B-Instruct)
- [Granite 4.1 8B](https://huggingface.co/ibm-granite/granite-4.1-8b)
- [Granite 4.1 8B official GGUF](https://huggingface.co/ibm-granite/granite-4.1-8b-GGUF)
- [Phi-4 Mini Instruct](https://huggingface.co/microsoft/Phi-4-mini-instruct)
- [Llama 3.1 8B Instruct](https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct)
- [GitHub Actions dependency caching limits](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)
