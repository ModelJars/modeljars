# MiniCPM5 1B Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream model: `openbmb/MiniCPM5-1B-GGUF`
- immutable revision: `87007042419d30c1d8f38ef065424ee33870831e`
- file: `MiniCPM5-1B-Q4_K_M.gguf`
- size: 688,065,920 bytes
- SHA-256:
  `81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned coding guarded-RAG workload
on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3. Every row
below was measured sequentially on that same host, against the same GGUF bytes,
corpus, prompts, context, token limit, sampling controls, warmup, and 27 measured
requests. Each backend ran in an isolated process.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, default prefill | USABLE | 1,094.6 ms | 44.21 tok/s | 2,349.3 ms | 100% |
| Models Rust/FFM, profiled | USABLE; qualified | 1,042.4 ms | 49.01 tok/s | 2,152.1 ms | 100% |
| Ollama 0.32.0 | USABLE | 419.9 ms | 37.35 tok/s | 2,421.0 ms | 100% |
| llama.cpp b10012 | USABLE | 637.2 ms | 72.52 tok/s | 1,499.0 ms | 100% |

Models reaches 131.2% of Ollama and 67.6% of llama.cpp decode throughput. Its
p95 end-to-end latency is 0.889x Ollama and 1.436x llama.cpp. These ratios are
same-host comparator results; they do not claim equivalent ratios on different
CPU architectures, JDKs, or operating systems.

## Profile Evidence

The retained profile selects a 64-token prefill batch, batched attention score
and value paths, eight native kernel workers, and native quantized decode. The
profile improves median decode throughput by 10.9%, p95 TTFT by 4.8%, and p95
end-to-end latency by 8.4% over the default prefill controls. All 27 raw
generations, grounding decisions, evaluations, and final answers are identical
between the default and profiled Models runs.

All 27 grounded answers were correct. Twelve answers retained model text, twelve
used validated extractive fallback, and three correctly abstained.
