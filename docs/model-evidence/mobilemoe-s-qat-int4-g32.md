# MobileMoE-S QAT INT4 G32 acceptance evidence

Status: qualified pure-Java generation and guarded-RAG runtime

## Artifact

- upstream model: `facebook/MobileMoE-S-QAT`
- immutable revision: `afda132cad380ac47da5ef055f186884d1c12f65`
- files: `config.json`, `model.safetensors`, `tokenizer.json`, and `tokenizer_config.json`
- packed model weights: 713,916,240 bytes
- model weights SHA-256:
  `1a54d8eb2adf19c296a1c129f9cd7d7395c21f5facfc6d13d2945e002d55e37d`
- license: Fair Noncommercial Research License; Hugging Face access is gated

## Production qualification

The exact four-file artifact passed `production-rag-model-contribution-v6` through the
pure-Java Models backend on an 8-vCPU AMD EPYC-Milan host with Eclipse Adoptium Java 25.0.3. All 27
attempts completed and produced correct grounded answers. The separate nine-attempt run using only
library defaults also completed without tuning properties.

| Backend | Tier | p95 TTFT | p50 decode | p95 end to end | Correct |
| --- | --- | ---: | ---: | ---: | ---: |
| Models pure Java | PRODUCTION_READY | 957.97 ms | 21.83 tok/s | 2,315.56 ms | 100% |

The model directly answered nine requests correctly, used the policy's validated extractive
fallback for fifteen, and correctly abstained on three. Peak resident memory was 2,554,105,856
bytes. The qualification binds Models revision
`4c3e2690144414b9b93e8c85166385f8a4f0821c`, the exact artifact digest, and the retained report
digest `2e0857a7b8def3b4d0999493f85039eac561e5fb3abd3d85eabdf56698776b85`.

## Runtime boundary

Models parses and executes the upstream Safetensors checkpoint in-process. It prepares the packed
group-32 INT4 expert weights for the existing Java quantized kernels; neither a converted GGUF nor
an external inference engine is part of the application runtime. Download requires prior license
acceptance and `HF_TOKEN` or `HUGGING_FACE_HUB_TOKEN` in the CLI or application environment.
