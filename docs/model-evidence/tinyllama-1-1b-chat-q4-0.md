# TinyLlama 1.1B Chat Q4_0 Acceptance Evidence

Status: catalog marker and Rust/FFM general guarded-RAG runtime accepted

## Artifact

- upstream conversion: `TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF`
- immutable revision: `52e7645ba7c309695bec7ac98f4f005b139cf465`
- file: `tinyllama-1.1b-chat-v1.0.Q4_0.gguf`
- size: 637,699,456 bytes
- SHA-256:
  `da3087fb14aede55fde6eb81a0e55e886810e43509ec82ecdc7aa5d62a03b556`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on a dedicated 8-vCPU AMD EPYC-Milan host. Every row below was measured
sequentially on that same host, against the same GGUF SHA, corpus, Zephyr
prompts, context, token limit, sampling controls, warmup, and 27 measured
requests. Each backend ran in a separate isolated process with the same
eight-thread CPU budget. Eclipse Adoptium Java 25.0.3 with HotSpot C2 applies
only to Models; the comparator rows used their native runtimes.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, explicit settings | USABLE | 1,347.5 ms | 34.18 tok/s | 3,194.7 ms | 100% |
| Models Rust/FFM, ModelJar profile | USABLE; qualified | 1,346.7 ms | 34.26 tok/s | 3,188.5 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 498.1 ms | 31.45 tok/s | 2,940.9 ms | 100% |
| llama.cpp b10012 | PRODUCTION_READY | 972.2 ms | 64.00 tok/s | 1,970.1 ms | 100% |

Models reaches 108.9% of Ollama and 53.5% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.084x Ollama and 1.618x llama.cpp. All 27 grounded
answers were correct; all nine model answers accepted by grounding policy v15
were also correct. The one-third model-contribution rate meets the production
floor exactly. These are controlled same-hardware ratios, not cross-hardware
performance guarantees.

## Profile Evidence

The marker-only run supplied no performance properties beyond the native
library path. The retained profile selected 32-token batched prefill, disabled
batched attention scores and values, selected eight native workers, and
enabled native quantized decode. Diagnostics recorded the profile as enabled
with no runtime-selector or launch-argument mismatch.

All 27 prompt hashes, raw generations, grounding decisions, evaluations, and
raw evaluations match the explicit-settings control exactly. The immutable
reports and recomputed qualification are retained in the Models repository at
commit `79181e6561875a5e7ca0f324fe261fef49391b84`.
