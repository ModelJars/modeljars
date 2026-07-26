# Gemma 3 1B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- Upstream conversion: `bartowski/google_gemma-3-1b-it-GGUF`
- immutable revision: `116f76234503685a98f572982177b11d44ec8ff1`
- file: `google_gemma-3-1b-it-Q4_K_M.gguf`
- size: 806,058,496 bytes
- SHA-256:
  `12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d`
- license: Gemma Terms of Use

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on an 8-vCPU AMD EPYC-Milan host with GraalVM Community Java 25.0.3. Every row
below was measured sequentially on that same host, against the same GGUF bytes,
corpus, prompts, context, token limit, sampling controls, warmup, and 27 measured
requests. Each backend ran in an isolated process.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, Java decode | PRODUCTION_READY | 976.7 ms | 14.63 tok/s | 3,134.9 ms | 100% |
| Models Rust/FFM, native decode | PRODUCTION_READY; qualified | 957.5 ms | 40.91 tok/s | 1,619.7 ms | 100% |
| Ollama 0.32.0 | USABLE | 1,130.8 ms | 29.90 tok/s | 2,101.0 ms | 100% |
| llama.cpp b10012 | PRODUCTION_READY | 940.3 ms | 48.85 tok/s | 1,524.0 ms | 100% |

Models reaches 136.8% of Ollama and 83.7% of llama.cpp decode throughput. Its
p95 end-to-end latency is 0.771x Ollama and 1.063x llama.cpp. These ratios are
same-host comparator results; they do not claim equivalent ratios on different
CPU architectures, JDKs, or operating systems.

## Profile Evidence

The retained profile selects eight native kernel workers and native quantized
decode. Native decode improves median throughput by 2.796x, p95 end-to-end
latency by 48.3%, and peak RSS by 34.8% over Java decode. All 27 raw
generations, grounding decisions, evaluations, and final answers are identical.

A separate 64-token prefill and batched-attention candidate was rejected because
it reduced median decode throughput and increased p95 end-to-end latency. Those
settings are deliberately absent from this model-specific profile.

All 27 grounded answers were correct. Nine answers retained model text, fifteen
used validated extractive fallback, and three correctly abstained.
