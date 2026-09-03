# ModelJars qualification evolution

Status: retained catalog and release-engineering journal, 2026-09-03.

This journal records how experimentally accepted work moves from Vectors and Models into an
immutable public ModelJar. It is the publication side of the
[Models experiment journal](https://github.com/integrallis/models/blob/main/benchmark-results/README.md)
and the
[experiment-backed JVM requests](https://github.com/integrallis/models/blob/main/JVM_NATIVE_INFERENCE_GAP.md).

## Evidence chain

1. **Vectors** proves a storage or compute primitive against numerical fixtures and measured JVM
   profiles. Rejected kernels remain recorded.
2. **Models** proves the complete architecture, tokenizer, public API, framework adapters, and
   controlled workload. External runtimes are reference oracles, not production dependencies.
3. **ModelJars** binds the accepted result to exact model bytes, runtime constraints, qualification
   evidence, and an immutable Maven coordinate.

A catalog-only addition does not require a Vectors or Models release when the already-published
runtime passes the exact artifact's policy. A runtime or kernel change is released in dependency
order: Vectors, Models, then ModelJars.

## Timeline

| Date | Catalog question | Retained result | Publication decision |
| --- | --- | --- | --- |
| 2026-07-10 to 2026-07-18 | Can Maven marker JARs describe models without bundling multi-gigabyte weights? | The catalog established checksum-bound external artifacts, generated marker classes, searchable metadata, and separately versioned performance profiles. | Keep weights at their pinned source; publish identity, provenance, runtime policy, and evidence in small marker JARs. |
| 2026-07-20 to 2026-07-24 | Can a performance recommendation be more precise than an architecture name? | Qwen and SmolLM experiments produced model-, quantization-, hardware-, and JVM-scoped profiles, including rejected staged and pairwise variants. | Profiles select only measured capabilities. Unsupported hardware and JVMs fall back to the portable Java path. |
| 2026-07-25 to 2026-07-29 | What makes a model public? | Twenty-five launch artifacts passed controlled grounded-RAG qualification. Publication was split into independent immutable marker packages, while the aggregate catalog remained generated from reviewed metadata. | A candidate entry alone is not public. A passing qualification record authorizes the model page, catalog entry, and marker publication. |
| 2026-08-01 to 2026-08-03 | How should runtime defects affect an existing qualification? | MiniCPM profile work and the tester-reported Gemma 4 failure added default-backend correctness smokes and hardware-specific regression evidence. | Fail closed: correct the evidence, publish a new marker revision, and restore qualification only after the exact artifact passes. |
| 2026-08-04 to 2026-08-09 | Can embeddings use the same contract? | E5-Mistral and EmbeddingGemma added full-vector reference-equivalence gates, pooling and normalization metadata, and public embedding APIs. | Publish embedding models only when the complete output vector clears the recorded cosine floor; a successful load is insufficient. |
| 2026-08-24 to 2026-08-25 | Must a ModelJar point to one GGUF file? | Qwen2.5 BF16 passed pure-Java qualification from a commit-pinned, sharded Hugging Face Safetensors bundle. | Make multi-file bundles first-class and verify every component digest. ModelJars is format-neutral when Models can execute the format in-process. |
| 2026-08-27 | Can a specialized tool model retain its native artifact format? | Needle 2 passed strict CACT parsing, upstream tokenizer/tensor fixtures, 13 playground tool cases, constrained arguments, and framework adapter tests. | Publish the exact CACT model and tool evidence through the common runtime; do not import the Needle engine or imply generic CACT support. |
| 2026-08-29 | Can Java-authored accelerator work be discoverable without changing model identity? | Qualified NVIDIA A16/A40 TornadoVM profiles preserved complete Qwen output while the catalog retained CPU fallback behavior. | Publish accelerator eligibility as runtime evidence, not as a second model artifact. Unqualified devices continue on Java Vector API execution. |
| 2026-08-30 | Can the catalog grow from new Java graph and tokenizer support? | All-MiniLM-L6-v2, Qwen3.5 0.8B, and Qwen2.5 3B passed their embedding or grounded-generation gates. | Promote exact artifacts and preserve their original publication timestamps so the CLI's 48-hour `NEW` signal is meaningful. |
| 2026-08-31 to 2026-09-01 | Can a compact multilingual embedding model clear a complete independent oracle? | Granite 107M produced a 384-dimensional vector with minimum cosine `0.9997466487182669` against the pinned llama.cpp reference; the artifact is `117011136` bytes with SHA-256 `4a0115de29aeeedc73175f14c6e2eee9da1d4b586cbe4c1e95b68b7e36aff36a`. | Publish `org.modeljars.huggingface:bartowski.granite-embedding-107m-multilingual-gguf.q4_k_m:1.0.0-q4_k_m.2` and expose it through the refreshed remote catalog. |
| 2026-09-01 | Can a 20 MiB cross-encoder become a first-class Java reranker rather than an embedding workaround? | The corrected MS MARCO MiniLM artifact reproduced six ONNX logits within `0.101034`, the same Q4_K artifact within `0.036392`, and exact top-two order. Three fresh JVMs measured pair p95 at no more than `174.357 ms` and six-document batch p95 at no more than `930.176 ms` on the controlled Intel Mac. | Publish a dedicated reranking marker and API. Keep external implementations as pinned oracles only; runtime execution remains pure Java. Record the missing scalar/vector `erf` as a JVM request with this workload as its acceptance test. |
| 2026-09-02 to 2026-09-03 | Can the JVM execute MobileMoE's original QAT checkpoint without converting it or importing another inference engine? | Models loaded the gated four-file Safetensors bundle, prepared its packed group-32 INT4 experts for Java execution, and passed 27 of 27 controlled RAG attempts plus a separate default-configuration smoke. The controlled host measured 957.97 ms p95 TTFT and 21.83 decode tokens/s. | Publish the immutable MobileMoE-S QAT bundle with the pure-Java backend. Require explicit upstream license acceptance and a Hugging Face read token; keep credentials out of marker metadata and redirected download requests. |

## Current public boundary

The public catalog contains 39 distinct qualified models:

- 32 controlled generation/RAG qualifications;
- 5 reference-equivalent embedding qualifications;
- 1 tool-calling qualification; and
- 1 numerical, ordering, and latency-qualified reranker.

The larger metadata registry is a candidate queue. It is not a claim that every recorded candidate
can execute or that every marker may be published.

## Release-engineering findings

### Immutable means content-equivalent, not merely present

Granite's first proposed marker revision already existed in GitHub Packages but did not contain the
current generated reference and qualification resources. A repository lookup alone would have
reported a false success. Publication now downloads an existing coordinate, compares the extracted
JAR entry contents with the candidate, and fails closed on any difference. ZIP byte identity is not
required because timestamps and entry ordering are not semantic package content.

The corrected Granite marker therefore uses revision `.2`; the conflicting `.1` coordinate remains
immutable and is not advertised.

### A catalog refresh should not require a CLI upgrade

Before a catalog-backed command runs, the CLI compares the published catalog SHA-256 with its active
snapshot. A changed registry is downloaded, verified, and atomically promoted. Offline execution
uses the last verified download, then the bundled catalog on a first run. This keeps catalog-only
promotion independent from the native CLI release train.

### Publication is independently scoped

- Runtime modules are released only when ModelJars code or dependencies change.
- Exact marker coordinates can be staged and finalized independently.
- The generated website and catalog can publish from a catalog change.
- Native CLI binaries are released only for CLI/runtime releases.
- SDKMAN automation remains present but disabled until vendor approval.

Each path verifies the artifact after publication instead of treating a successful build as proof
that the intended bytes reached the public repository.
