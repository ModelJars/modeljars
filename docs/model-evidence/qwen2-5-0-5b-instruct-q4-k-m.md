# Qwen2.5 0.5B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream model: `Qwen/Qwen2.5-0.5B-Instruct`
- GGUF source: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- immutable revision: `9217f5db79a29953eb74d5343926648285ec7e67`
- file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- size: 491,400,032 bytes
- SHA-256:
  `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3. The
canonical run loaded the ModelJar alias through Models Rust/FFM without manual
performance properties. Backend diagnostics prove that the exact
EPYC-Milan/Java-25 profile enabled eight native kernel workers and quantized
decode.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM | PRODUCTION_READY; qualified | 519.7 ms | 67.53 tok/s | 933.6 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 375.3 ms | 46.05 tok/s | 1,088.0 ms | 100% |
| llama.cpp b10012 | PRODUCTION_READY | 488.8 ms | 98.05 tok/s | 783.4 ms | 100% |

Models reaches 146.6% of Ollama and 68.9% of llama.cpp decode throughput. Its
p95 end-to-end latency is 0.858x Ollama and 1.192x llama.cpp. All 27 grounded
answers were correct; all 12 model answers accepted by the grounding policy
were also correct. This claim is SHA-bound to the artifact and scoped to the
committed general RAG workload, not arbitrary ungrounded generation.

## Profile Evidence

The retained baseline disables native quantized decode and reaches 22.80
tok/s. The marker-selected profile reaches 67.53 tok/s with identical raw
generations and grounded answers across all 27 attempts. The catalog binds the
baseline, candidate, comparator, and qualification reports by SHA-256.
