# Qwen2.5 0.5B Instruct Q5_K_M

This new candidate passes the production RAG gate on released Models 0.5.9 and
Vectors 0.1.32. Qualification uses the immutable Qwen GGUF revision and SHA in
models.json, same-host Ollama 0.40.13, nine general RAG cases, one warmup and three
measured iterations. No existing qualification was changed.

The native-eight-worker / fused-Java-attention configuration passed its selected
screen and both fresh, counterbalanced confirmations:

| Run | Decode / Ollama | p95 E2E / Ollama | Verdict |
|---|---:|---:|---|
| Selected screen | 0.832 | 1.232 | QUALIFIED |
| Confirmation 1 | 0.827 | 1.254 | QUALIFIED |
| Confirmation 2 | 0.821 | 1.085 | QUALIFIED |

The unchanged gates require decode >=0.80 and E2E <=1.50. The final confirmation
is the canonical catalog report; Models' evidence test re-derives all three
verdicts. Each pair also passed an untuned library-default correctness smoke.

Raw generated text, output-token counts, prompt hashes and evaluations matched
controls. Model contribution was 66.7%, with 100% correctness among contributed
answers. Token IDs were not recorded. This is not broad accuracy evidence.

The automatic performance profile is restricted to Linux amd64, AMD EPYC7R13,
16 logical CPUs, Temurin25.0.3+9-LTS, HotSpot C2, and 256-bit vectors. It recommends
only existing settings: models.native.kernels.threads=8 and
models.purejava.fusedGroupedAttention=true. Decode defaults to pool size8; a new
decode switch or runtime release is unnecessary. Other hosts retain their own
existing selection, rather than inheriting an unmeasured recommendation.

Fused attention and native pool size were isolated in separate same-host
comparisons. The performance profile links both experiments and the shared
answer-signature hash. Its metric table compares pool16 to pool8 with fused
attention fixed; it does not compare timings across hosts. Earlier failures,
including the fused screen's narrow latency miss, remain in the evidence.

An experimental F16C conversion patch did not improve performance repeatably.
It was reverted on both branches and PR319 was closed without canceling CI.
No additional runtime implementation is retained.
