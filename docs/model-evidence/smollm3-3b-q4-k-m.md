# SmolLM3 3B Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM general guarded-RAG runtime accepted

## Artifact

- upstream conversion: `ggml-org/SmolLM3-3B-GGUF`
- immutable revision: `4965cb60b150737b68a0408c36aeefb65078f894`
- file: `SmolLM3-Q4_K_M.gguf`
- size: 1,915,305,312 bytes
- SHA-256:
  `8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e`
- license: Apache-2.0

## Production Qualification

The exact artifact is qualified for the versioned general guarded-RAG workload
on a dedicated 8-vCPU AMD EPYC-Milan host. Every row below was measured
sequentially on that same host, against the same GGUF SHA, corpus, prompts,
context, token limit, sampling controls, warmup, and 27 measured requests.
Each backend ran in a separate isolated process with the same eight-thread CPU
budget. GraalVM Community Java 25.0.3 applies only to Models; the comparator
rows used their native runtimes.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, explicit settings | USABLE | 1,745.2 ms | 15.88 tok/s | 5,343.1 ms | 100% |
| Models Rust/FFM, ModelJar profile | USABLE; qualified | 1,727.9 ms | 15.93 tok/s | 5,318.9 ms | 100% |
| Ollama 0.32.0 | USABLE | 1,125.9 ms | 17.43 tok/s | 3,875.3 ms | 100% |
| llama.cpp b10012 | OFFLINE | 2,434.1 ms | 24.32 tok/s | 4,823.9 ms | 100% |

Models reaches 91.4% of Ollama and 65.5% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.373x Ollama and 1.103x llama.cpp. All 27 grounded
answers were correct; all twelve model answers accepted by grounding policy v14
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
commit `6756812c16ff795dc21ff09f44be8287a74950f5`.
