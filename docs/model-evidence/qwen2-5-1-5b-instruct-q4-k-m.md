# Qwen2.5 1.5B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream model: `Qwen/Qwen2.5-1.5B-Instruct`
- GGUF source: `Qwen/Qwen2.5-1.5B-Instruct-GGUF`
- immutable revision: `91cad51170dc346986eccefdc2dd33a9da36ead9`
- file: `qwen2.5-1.5b-instruct-q4_k_m.gguf`
- size: 1,117,320,736 bytes
- SHA-256:
  `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3. Every row
below was measured sequentially on that same host, against the same GGUF bytes,
corpus, prompts, context, token limit, sampling controls, warmup, and 27
measured requests. Each backend ran in an isolated process.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, default prefill | USABLE; failed Ollama latency gate | 1,923.9 ms | 28.69 tok/s | 2,905.2 ms | 100% |
| Models Rust/FFM, profiled | USABLE; qualified | 1,874.1 ms | 31.25 tok/s | 2,771.0 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 677.4 ms | 28.76 tok/s | 1,864.3 ms | 100% |
| llama.cpp b10012 | USABLE | 1,138.2 ms | 44.93 tok/s | 1,806.2 ms | 100% |

Models reaches 108.7% of Ollama and 69.6% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.486x Ollama and 1.534x llama.cpp. All 27 grounded
answers were correct; all nine model answers accepted by the grounding policy
were also correct. These ratios are same-host comparator results; they do not
claim equivalent ratios on different CPU architectures, JDKs, or operating
systems.

## Profile Evidence

The retained profile selects a 64-token prefill batch, batched attention score
and value paths, eight native kernel workers, and native quantized decode.
Each prefill control improved a one-iteration screen independently before the
combined 27-attempt run. The profiled and default runs have identical raw
generations, grounding decisions, and final answers.
