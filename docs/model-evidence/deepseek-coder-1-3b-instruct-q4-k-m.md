# DeepSeek-Coder 1.3B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM coding guarded-RAG runtime accepted

## Artifact

- upstream conversion: `TheBloke/deepseek-coder-1.3b-instruct-GGUF`
- immutable revision: `4595af8c3dff738094bd6c86054dfb5a90d5c41e`
- file: `deepseek-coder-1.3b-instruct.Q4_K_M.gguf`
- size: 873,582,624 bytes
- SHA-256:
  `04cebb6fafa40ae628cf6bfeb76032ec792852f54020c559ad0a56b9f2839118`
- license: DeepSeek Model License

## Production Qualification

The exact artifact is qualified for the versioned coding guarded-RAG workload
on a dedicated 8-vCPU AMD EPYC-Milan host. Every row below was measured
sequentially on that same host, against the same GGUF SHA, corpus, prompts,
context, token limit, sampling controls, warmup, and 27 measured requests.
Each backend ran in a separate isolated process with the same eight-thread CPU
budget. GraalVM Community Java 25.0.3 applies only to Models; the comparator
rows used their native runtimes.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
|---|---|---:|---:|---:|---:|
| Models Rust/FFM, explicit settings | USABLE | 1,049.6 ms | 30.07 tok/s | 3,006.1 ms | 100% |
| Models Rust/FFM, ModelJar profile | USABLE; qualified | 1,010.2 ms | 29.95 tok/s | 2,972.0 ms | 100% |
| Ollama 0.32.0 | PRODUCTION_READY | 596.4 ms | 32.68 tok/s | 2,272.1 ms | 100% |
| llama.cpp b10012 | USABLE | 1,218.5 ms | 50.46 tok/s | 2,452.8 ms | 100% |

Models reaches 91.6% of Ollama and 59.4% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.308x Ollama and 1.212x llama.cpp. All 27 grounded
answers were correct; all nine model answers accepted by grounding policy v14
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
commit `6cea1f5d58861d898f1f4fab6f0b97bef3fd33ba`.
