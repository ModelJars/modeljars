# Llama 3.2 3B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker and Rust/FFM guarded-RAG runtime accepted

## Artifact

- upstream conversion: `bartowski/Llama-3.2-3B-Instruct-GGUF`
- immutable revision: `5ab33fa94d1d04e903623ae72c95d1696f09f9e8`
- file: `Llama-3.2-3B-Instruct-Q4_K_M.gguf`
- size: 2,019,377,696 bytes
- SHA-256:
  `6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff`
- license: Llama 3.2 Community License

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
| Models Rust/FFM, explicit attention-off | USABLE | 1,591.3 ms | 15.65 tok/s | 3,765.6 ms | 100% |
| Models Rust/FFM, rejected attention-on | USABLE | 1,646.7 ms | 16.10 tok/s | 3,775.6 ms | 100% |
| Models Rust/FFM, ModelJar profile | USABLE; qualified | 1,621.7 ms | 15.70 tok/s | 3,789.3 ms | 100% |
| Ollama 0.32.0 | USABLE | 1,226.7 ms | 17.57 tok/s | 3,273.9 ms | 100% |
| llama.cpp b10012 | OFFLINE | 2,355.2 ms | 23.34 tok/s | 3,846.9 ms | 100% |

Models reaches 89.3% of Ollama and 67.2% of llama.cpp decode throughput. Its
p95 end-to-end latency is 1.157x Ollama and 0.985x llama.cpp. All 27 grounded
answers were correct; all eighteen model answers accepted by grounding policy
v13 were also correct. These are controlled same-hardware ratios, not
cross-hardware performance guarantees.

## Profile Evidence

The marker-only run supplied no performance properties beyond the native
library path. The retained profile selected 32-token batched prefill, disabled
batched attention scores and values, selected eight native workers, and
enabled native quantized decode. Diagnostics recorded the profile as enabled
with no runtime-selector or launch-argument mismatch.

The rejected attention-on run improved median decode by about 2.6%, but
regressed p95 TTFT, prefill throughput, and CPU use. Its p95 end-to-end result
differed from the two attention-off runs by less than 0.4% and changed
direction between repetitions, so it did not establish a stable aggregate
gain.

All 27 prompt hashes, raw generations, grounding decisions, evaluations, and
raw evaluations match the explicit-settings and rejected-attention controls
exactly. The immutable reports and recomputed qualification are retained in
the Models repository at commit
`60e441df4a3e3d0f6798851c6861f85f8c885933`.
