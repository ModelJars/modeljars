# Qwen2.5-Math 1.5B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM math guarded-RAG runtime accepted

## Artifact

- upstream conversion: `bartowski/Qwen2.5-Math-1.5B-Instruct-GGUF`
- immutable revision: `951ed2aea09c43e331c612e74d83e4a23ca98e3b`
- file: `Qwen2.5-Math-1.5B-Instruct-Q4_K_M.gguf`
- size: 986,048,832 bytes
- SHA-256:
  `9614a50f03c897028920ca0dc4365da570bf587f9ee7768261216fe370b37e8e`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned math guarded-RAG workload
on a dedicated 8-vCPU AMD EPYC-Milan host. Every row below was measured
sequentially on that same host, against the same GGUF SHA, corpus, prompts,
`. ` stop sequence, context, token limit, sampling controls, warmup, and 27
measured requests. Each backend ran in a separate isolated process with the
same eight-thread CPU budget. GraalVM Community Java 25.0.3 applies only to
Models; the comparator rows used their native runtimes.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, explicit settings | PRODUCTION_READY | 781.2 ms | 27.02 tok/s | 2,093.7 ms | 100% |
| Models Rust/FFM, ModelJar profile | PRODUCTION_READY; qualified | 754.8 ms | 26.99 tok/s | 2,079.1 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 606.3 ms | 26.67 tok/s | 3,219.7 ms | 100% |
| llama.cpp b10012 | USABLE | 1,084.4 ms | 44.92 tok/s | 2,415.2 ms | 100% |

Models reaches 101.2% of Ollama and 60.1% of llama.cpp decode throughput. Its
p95 end-to-end latency is 0.646x Ollama and 0.861x llama.cpp. All 27 grounded
answers were correct; all nine model answers accepted by grounding policy v13
were also correct. These are controlled same-hardware ratios, not
cross-hardware performance guarantees.

## Profile Evidence

The marker-only run supplied no performance properties beyond the native
library path. The retained profile selected 32-token batched prefill, disabled
batched attention scores and values, selected eight native workers, and
enabled native quantized decode. Diagnostics recorded the profile as enabled
with no runtime-selector or launch-argument mismatch.

All 27 prompt hashes, raw generations, grounding decisions, evaluations, and
raw evaluations match the explicit-settings control exactly. The immutable
reports and recomputed qualification are retained in the Models repository at
commit `5458fed899ba3c790e13bfe6fce343d4e7e251de`.
