# Llama 3.2 1B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream conversion: `bartowski/Llama-3.2-1B-Instruct-GGUF`
- immutable revision: `067b946cf014b7c697f3654f621d577a3e3afd1c`
- file: `Llama-3.2-1B-Instruct-Q4_K_M.gguf`
- size: 807,694,464 bytes
- SHA-256:
  `6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83`
- license: Llama 3.2 Community License

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3. Every row
below was measured sequentially on that same host, against the same GGUF bytes,
corpus, prompts, context, token limit, sampling controls, warmup, and 27 measured
requests. Each backend ran in an isolated process.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, default prefill | USABLE | 1,348.4 ms | 37.71 tok/s | 2,023.1 ms | 100% |
| Models Rust/FFM, profiled | USABLE; qualified | 1,312.6 ms | 38.73 tok/s | 1,962.3 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 543.3 ms | 34.75 tok/s | 1,640.1 ms | 100% |
| llama.cpp b10012 | PRODUCTION_READY | 799.4 ms | 57.06 tok/s | 1,412.5 ms | 100% |

Models reaches 111.4% of Ollama and 67.9% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.196x Ollama and 1.389x llama.cpp. These ratios are
same-host comparator results; they do not claim equivalent ratios on different
CPU architectures, JDKs, or operating systems.

## Profile Evidence

The retained profile selects a 64-token prefill batch, batched attention score
and value paths, eight native kernel workers, and native quantized decode. The
profile improves median decode throughput by 2.7%, p95 TTFT by 2.7%, and p95
end-to-end latency by 3.0% over the default prefill controls. All 27 raw
generations, grounding decisions, evaluations, and final answers are identical
between the default and profiled Models runs.

All 27 grounded answers were correct. Fifteen answers retained model text, nine
used validated extractive fallback, and three correctly abstained.
