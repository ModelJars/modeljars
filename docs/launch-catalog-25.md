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

The catalog currently contains 17 GGUF marker artifacts representing 15
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
| 13 | DeepSeek-R1-Distill-Qwen-7B | Reasoning | MIT | Pinned trusted community Q4_K_M | Supported by a mandatory exact-oracle large-model test on the Qwen2 and K-quant foundation. |
| 14 | Qwen2.5-Math 1.5B Instruct | Math/education | Apache-2.0 | Pinned trusted community Q4_K_M | Supported by a mandatory checksum, metadata, tokenizer, and exact-oracle test. |
| 15 | SQLCoder-7B-2 | Text-to-SQL/data | CC-BY-SA-4.0 | Pinned official upstream Q5_K_M | Marker and independent oracle are complete. The strict pure-Java test is intentionally red until the separately owned Q5_K vectors work lands on `main`; preserve a read-only-use warning. |
| 16 | HuatuoGPT-o1-7B | Medical reasoning | Apache-2.0 | Pin trusted community Q4_K_M converted from the official weights | Near target: Qwen2.5-7B architecture and tokenizer reuse the current backend. Add the thinking/final-response format, exact oracle, and no-clinical-use metadata. |
| 17 | Fin-R1 | Financial reasoning | Apache-2.0 on the GGUF release | Pin and audit the community Q4_K_M lineage | Near target: `qwen2` GGUF architecture reuses the current backend. Add bilingual finance prompt coverage, exact oracle, and no-financial-advice metadata. |
| 18 | EuroLLM 1.7B Instruct | Multilingual/translation | Apache-2.0 | Trusted community GGUF required | Near target: dense Llama-family structure; validate tokenizer and 35-language metadata. |
| 19 | Mistral 7B Instruct v0.3 | General/chat/tool calling | Apache-2.0 | Trusted community GGUF required | Add Mistral metadata, tokenizer v3, and sliding-window semantics. This is the Mistral-family foundation. |
| 20 | SaulLM-7B Instruct v1 | Legal | MIT | Trusted community GGUF required | Reuse the Mistral foundation; preserve legal limitations and add a deterministic legal-format smoke test. |
| 21 | Llama 3.1 8B Instruct | Widely adopted general model | Gated Llama 3.1 Community License | Gated source or verified conversion | Add gated-artifact resolution, Llama 3 BPE, piecewise RoPE scaling, attribution metadata, and chat templates. |
| 22 | Foundation-Sec-8B Instruct | Cybersecurity | Llama 3.1 Community License for the base plus Apache-2.0 for Cisco changes | Official Q8_0 GGUF; seek a trusted Q4_K_M if needed | Reuse the Llama 3.1 foundation; add dual-use security metadata, safeguards, and an exact domain prompt oracle. |
| 23 | MedGemma 4B IT | Medical text and imaging foundation | Gated Health AI Developer Foundations terms | Trusted Q4_K_M GGUF exists; marker must retain gating and upstream terms | Add Gemma 3 tokenizer and alternating local/global attention. Prove text-only inference first; add the multimodal projector as a separate capability. |
| 24 | Granite 4.1 8B | Enterprise/tools/coding | Apache-2.0 | Official GGUF | Add Granite metadata and attention, embedding, residual, and logits scaling semantics before the 8B proof. |
| 25 | Phi-4 Mini Instruct | Compact reasoning/code/tools | MIT | Trusted community GGUF required | Add Phi-3/Phi-4 tensor layout, fused projections, long-context RoPE, tokenizer, and template support. |

## Why this portfolio is diverse

The roster spans 360M through 8B parameters and covers general chat, code,
fill-in-the-middle, reasoning, mathematics, SQL, translation, tool calling,
healthcare, law, finance, cybersecurity, and enterprise workflows. It also
avoids making 25 by publishing many quantizations of a few Qwen checkpoints.

The implementation work is intentionally grouped by reusable foundations:

1. Existing Qwen2 foundation: DeepSeek-R1-Distill-Qwen, Qwen2.5-Math,
   HuatuoGPT-o1, and Fin-R1.
2. Existing Llama-family foundation: SQLCoder and EuroLLM.
3. Mistral foundation: Mistral 7B Instruct and SaulLM.
4. Llama 3 foundation: Llama 3.1 Instruct and Foundation-Sec.
5. New independent foundations: Gemma 3/MedGemma, Granite 4.1, and Phi-4 Mini.

This sequence reaches 25 while using the current Qwen2 path for the next two
vertical models. The new architecture work then unlocks more than one catalog
entry: Mistral unlocks legal and biomedical alternates, Llama 3 unlocks
security, finance, healthcare, and insurance alternates, and Gemma 3 unlocks a
large fine-tuning ecosystem beyond MedGemma.

## Vertical-model qualification

Vertical models need stronger provenance and warning metadata than general
chat models. Popularity is supporting evidence, not proof of quality. Download
and like counts below are a July 11, 2026 snapshot and will change.

| Vertical | Candidate | Evidence observed in July 2026 | Decision |
|---|---|---|---|
| SQL/data | [SQLCoder-7B-2](https://huggingface.co/defog/sqlcoder-7b-2) | 436 likes and about 22K monthly downloads; upstream GGUF; published SQL-Eval results | Launch target. Preserve CC-BY-SA and warn that generated SQL must run through read-only credentials. |
| Healthcare | [HuatuoGPT-o1-7B](https://huggingface.co/FreedomIntelligence/HuatuoGPT-o1-7B) | Apache-2.0 Qwen2.5 medical-reasoning model with a paper, 60 likes, about 2.1K monthly downloads, and 13 quantizations; a trusted Q4_K_M GGUF is 4.68 GB | Launch target and next pure-Java vertical after SQLCoder because it reuses the Qwen2.5 runtime. Require research-only/no-clinical-use metadata. |
| Healthcare | [MedGemma 4B IT](https://huggingface.co/google/medgemma-4b-it) | 1.01K likes, 284K monthly downloads, 623 fine-tunes, 44 quantizations, and extensive Google benchmarks; trusted Q4_K_M GGUF is 2.49 GB | Launch target with gated terms. Implement text-only Gemma 3 inference before vision. Preserve Google's requirement for adaptation, validation, and independent verification. |
| Healthcare | [OpenBioLLM Llama3 8B](https://huggingface.co/aaditya/Llama3-OpenBioLLM-8B) | 247 likes, about 7K monthly downloads, 16 quantizations, but no completed accompanying paper and Llama 3 terms | Strong post-launch alternate after Llama 3 support; MedGemma has better provenance and adoption for the core 25. |
| Healthcare | [BioMistral-7B-GGUF](https://huggingface.co/BioMistral/BioMistral-7B-GGUF) | Official Apache-2.0 GGUF, paper, 190 likes, and about 1K monthly downloads | Post-launch alternate after Mistral support. Retain research-only health warnings. |
| Legal | [SaulLM-7B-Instruct-v1](https://huggingface.co/Equall/Saul-7B-Instruct-v1) | MIT model, paper, 116 likes, about 1.8K monthly downloads, and 16 quantizations | Launch target after Mistral support. Do not represent output as legal advice. |
| Finance | [Fin-R1 GGUF](https://huggingface.co/Mungert/Fin-R1-GGUF) | Apache-2.0 Qwen2 financial-reasoning release with a paper, 14 likes, 733 monthly GGUF downloads, and a 4.68 GB Q4_K_M | Launch target and next pure-Java finance model. Audit and pin the community conversion lineage before publishing its marker. |
| Finance | [AdaptLLM Finance Chat](https://huggingface.co/AdaptLLM/finance-chat) | ICLR domain-adaptation work and 100 likes, but only 283 monthly downloads and Llama 2 terms | Research-backed alternate; Fin-R1 is closer to the current runtime and has a simpler catalog license posture. |
| Finance | [Fino1-8B](https://huggingface.co/TheFinAI/Fino1-8B) | Llama 3.1 financial-reasoning fine-tune with a paper, 35 likes, 197 monthly downloads, and community quantizations | Watchlist alternate after Llama 3 support and GGUF lineage verification. |
| Cybersecurity | [Foundation-Sec-8B-Instruct](https://huggingface.co/fdtn-ai/Foundation-Sec-8B-Instruct) | Cisco release with a technical report, 71 likes, 13.6K monthly downloads, and 10 quantizations | Launch target after Llama 3.1. Its NOTICE maps the Meta base to the Llama 3.1 Community License and Cisco changes to Apache-2.0; preserve dual-use restrictions and human-oversight guidance. |
| Code | [StarCoder2 3B](https://huggingface.co/bigcode/starcoder2-3b) | BigCode OpenRAIL-M, 221 likes, 178K monthly downloads, 294 adapters, 50 fine-tunes, and 29 quantizations | Highest-priority post-launch code foundation. It is not in the core 25 because six Qwen/DeepSeek code models are already implemented; add StarCoder2 decoder, sliding-window attention, tokenizer, and FIM support next. |
| Insurance | [Open-Insurance-LLM-Llama3-8B](https://huggingface.co/Raj-Maharajwala/Open-Insurance-LLM-Llama3-8B) | Llama 3 fine-tune on InsuranceQA with 4 likes and 460 monthly upstream downloads; trusted Q4_K_M GGUF is available | Best current insurance watchlist candidate, but no independent evaluation or paper justifies a launch slot. Reassess after Llama 3 support. |
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
| StarCoder2 3B | Excellent adoption and ecosystem signal, but the launch roster already contains six implemented Qwen/DeepSeek code models. Make it the first post-launch code architecture. |
| BioMistral 7B | Solid permissive biomedical alternate that becomes inexpensive after Mistral support, but HuatuoGPT-o1 is closer to the current runtime and MedGemma has much stronger adoption. |
| AdaptLLM Finance Chat 7B | Strong research lineage, but Fin-R1 has Qwen2 runtime proximity and Apache-2.0 GGUF metadata. |
| OLMo 2 1B Instruct | Fully open and valuable for fine-tuning, but MedGemma provides more launch diversity and much stronger demonstrated adoption. Keep OLMo 2 near the top of the post-launch transparent-model list. |
| Qwen3.5 9B | Gated DeltaNet/linear recurrent state, gated attention, multimodal encoder, and MTP make it a major new hybrid runtime. |
| Gemma 4 E4B | Per-layer embeddings, hybrid local/global attention, proportional RoPE, and multimodal inputs make it substantially larger than a normal dense 4.5B integration. |
| Aya Expanse 8B | Useful multilingual model, but gated CC-BY-NC terms are a poor fit for the unrestricted launch roster. |
| Insurance-specific fine-tunes | Current candidates do not yet combine meaningful adoption, independent evaluation, clear lineage, and a strong immutable GGUF source. |

## Primary sources

- [Qwen3 8B official GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF)
- [DeepSeek-R1-Distill-Qwen-7B](https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B)
- [Qwen2.5-Math 1.5B Instruct](https://huggingface.co/Qwen/Qwen2.5-Math-1.5B-Instruct)
- [HuatuoGPT-o1-7B](https://huggingface.co/FreedomIntelligence/HuatuoGPT-o1-7B)
- [HuatuoGPT-o1-7B trusted GGUF](https://huggingface.co/bartowski/HuatuoGPT-o1-7B-GGUF)
- [Fin-R1 GGUF](https://huggingface.co/Mungert/Fin-R1-GGUF)
- [EuroLLM 1.7B Instruct](https://huggingface.co/utter-project/EuroLLM-1.7B-Instruct)
- [Mistral 7B Instruct v0.3](https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3)
- [AdaptLLM Finance Chat](https://huggingface.co/AdaptLLM/finance-chat)
- [OLMo 2 1B Instruct](https://huggingface.co/allenai/OLMo-2-0425-1B-Instruct)
- [MedGemma 4B IT](https://huggingface.co/google/medgemma-4b-it)
- [MedGemma 4B IT trusted GGUF](https://huggingface.co/bartowski/google_medgemma-4b-it-GGUF)
- [StarCoder2 3B](https://huggingface.co/bigcode/starcoder2-3b)
- [SaulLM 7B Instruct v1](https://huggingface.co/Equall/Saul-7B-Instruct-v1)
- [Foundation-Sec 8B Instruct](https://huggingface.co/fdtn-ai/Foundation-Sec-8B-Instruct)
- [Foundation-Sec NOTICE](https://huggingface.co/fdtn-ai/Foundation-Sec-8B-Instruct/blob/main/NOTICE.md)
- [Open-Insurance-LLM Llama3 8B](https://huggingface.co/Raj-Maharajwala/Open-Insurance-LLM-Llama3-8B)
- [Granite 4.1 8B](https://huggingface.co/ibm-granite/granite-4.1-8b)
- [Granite 4.1 8B official GGUF](https://huggingface.co/ibm-granite/granite-4.1-8b-GGUF)
- [Phi-4 Mini Instruct](https://huggingface.co/microsoft/Phi-4-mini-instruct)
- [Llama 3.1 8B Instruct](https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct)
- [GitHub Actions dependency caching limits](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)
