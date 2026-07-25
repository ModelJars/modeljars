# UmarTransit-1B Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream model: `umarfarookm/UmarTransit-1B`
- immutable revision: `086c12aadfee08963ed70ab73ae2cb3997dbde3a`
- file: `UmarTransit-1B.Q4_K_M.gguf`
- size: 986,048,096 bytes
- SHA-256:
  `db1a4489626110145274f508b3fa30439516a47b4e721fe02d67df4679db5b9a`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned transportation guarded-RAG
workload on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3.
Every row below was measured sequentially on that same host, against the same
GGUF bytes, corpus, prompts, context, token limit, sampling controls, warmup,
and 27 measured requests. Each backend ran in an isolated process.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, default prefill | USABLE | 1,437.7 ms | 28.95 tok/s | 2,278.8 ms | 100% |
| Models Rust/FFM, profiled | USABLE; qualified | 1,402.7 ms | 30.93 tok/s | 2,172.4 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 557.4 ms | 26.84 tok/s | 1,550.0 ms | 100% |
| llama.cpp b10012 | USABLE | 1,008.7 ms | 44.97 tok/s | 1,569.7 ms | 100% |

Models reaches 115.2% of Ollama and 68.8% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.402x Ollama and 1.384x llama.cpp. These ratios are
same-host comparator results; they do not claim equivalent ratios on different
CPU architectures, JDKs, or operating systems.

## Profile Evidence

The retained profile selects a 64-token prefill batch, batched attention score
and value paths, eight native kernel workers, and native quantized decode. The
profile improves median decode throughput by 7.1% and p95 end-to-end latency by
4.7% over the default prefill controls. The marker-only and default runs have
identical raw generations, grounding decisions, evaluations, and final answers.

All 27 grounded answers were correct. Fifteen answers retained model text with
derived trusted citations, nine used validated extractive fallback, and three
correctly abstained.
