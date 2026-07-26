# EuroLLM 1.7B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker, pure-Java oracle, and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream model: `utter-project/EuroLLM-1.7B-Instruct`
- Upstream revision: `a25c7fa65fc2a644e6270b8940dbe295b51da681`
- GGUF source: `mradermacher/EuroLLM-1.7B-Instruct-GGUF`
- GGUF revision: `2951f08f66429c934c8b01a94347161362430808`
- File: `EuroLLM-1.7B-Instruct.Q4_K_M.gguf`
- Size: `1,045,157,088` bytes
- SHA-256: `1cade17f491ea46a686dbee51fbd52442e0f001f102380c3b9d66b4a77f84093`
- License: Apache-2.0

The file was downloaded from the revision-pinned URL and its local byte count
and SHA-256 were verified on July 11, 2026. Both repositories provide
structured lineage and Apache-2.0 metadata. The conversion identifies
`utter-project/EuroLLM-1.7B-Instruct` as its quantized base.

The GGUF retains the internal display name `EuroLLM 1B Annealed Fw` and an
older Unbabel base-model namespace from the checkpoint's training metadata.
Its conversion card and GGUF source URL point to the official utter-project
instruct checkpoint. Runtime acceptance must use the immutable lineage and
tensor contract, not the inherited display name.

## GGUF Contract

Reference runtime: llama.cpp b9960, commit `a935fbffe`

- GGUF version: 3
- Architecture: `llama`
- Metadata entries: 44
- Tensor count: 219
- Tensor types: 49 F32, 145 Q4_K, 25 Q6_K
- Blocks: 24
- Training context: 8,192
- Embedding width: 2,048
- Feed-forward width: 5,632
- Attention heads: 16
- Key/value heads: 8
- RoPE dimensions: 128
- RoPE base: 10,000
- RMS normalization epsilon: 0.00001
- Tokenizer: SentencePiece
- Vocabulary size: 128,000
- Add space prefix: true
- Add BOS: true
- Add EOS: false
- Chat template: ChatML with an empty system turn

## Deterministic Oracle

The oracle applies the embedded ChatML template to this user message:

```text
Translate the following English source text to Portuguese:
English: The sky is blue.
Portuguese:
```

Rendered prompt:

```text
<|im_start|>system
<|im_end|>
<|im_start|>user
Translate the following English source text to Portuguese:
English: The sky is blue.
Portuguese:<|im_end|>
<|im_start|>assistant
```

Rendered prompt token IDs, including the configured BOS token:

```text
[1, 3, 2205, 271, 4, 119715, 271, 3, 15236, 271, 31702, 31817, 557, 5302, 6771, 7684, 6001, 591, 53439, 119782, 271, 31601, 119782, 806, 14930, 656, 15388, 119735, 271, 23392, 19269, 1046, 119782, 4, 119715, 271, 3, 58406, 271]
```

Greedy continuation at temperature 0, including the end-of-generation token:

```text
[119802, 83672, 775, 35784, 119735, 4]
```

Decoded continuation:

```text
O céu é azul.<|im_end|>
```

## Limitations

The upstream model card says the instruct model was not aligned to human
preferences and may produce hallucinated, harmful, or false output. The marker
therefore includes `unaligned-output-warning`. Catalog inclusion is not an
endorsement of generated or translated content.

## Runtime Acceptance

The ModelJars marker advertises `pure-java=true`. The mandatory
`EuroLlmModelJarsIntegrationTest` downloads this exact artifact, verifies size
and SHA-256, asserts the GGUF contract above, reproduces the SentencePiece and
ChatML token IDs, and matches the six raw token IDs returned by llama.cpp
b9960's `/completion` endpoint with `return_tokens=true`. The fixture cannot
turn a missing artifact into a skipped or passing test.

## Production Qualification

The exact artifact is qualified for the versioned multilingual guarded-RAG
workload on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3.
Every row below was measured sequentially on that same host, against the same
GGUF bytes, corpus, prompts, context, token limit, sampling controls, warmup,
and 27 measured requests. Each backend ran in an isolated process. The
canonical run loaded the ModelJar alias through Models Rust/FFM without manual
performance properties. Backend diagnostics prove that the exact
EPYC-Milan/Java-25 profile selected its 64-token prefill batch, batched
attention paths, final-layer prompt pruning, KV-only final-layer prefill, and
native quantized decode.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM | USABLE; qualified | 1,514.3 ms | 31.86 tok/s | 2,548.4 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 537.9 ms | 29.02 tok/s | 1,709.6 ms | 100% |
| llama.cpp b10012 | USABLE | 1,104.3 ms | 49.06 tok/s | 1,804.7 ms | 100% |

Models reaches 109.8% of Ollama and 64.9% of llama.cpp decode. Its p95
end-to-end latency is 1.491x Ollama and 1.412x llama.cpp, within the unchanged
production policy ceilings of 1.5x and 2.0x. All 27 grounded answers were
correct; all 21 model answers accepted by the grounding policy were also
correct. This claim is SHA-bound to the artifact and scoped to the committed
multilingual RAG workload, not arbitrary ungrounded generation. These ratios
are same-host comparator results; they do not claim equivalent ratios on
different CPU architectures, JDKs, or operating systems.
