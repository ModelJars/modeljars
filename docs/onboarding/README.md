# October 2026 model onboarding

The exact-artifact intake is recorded before catalogue publication:

| Candidate | Exact scope | Runtime evidence | Publication status |
| --- | --- | --- | --- |
| [EmbeddingGemma 2 Q8_0](https://github.com/ModelJars/modeljars/issues/214) | 309,855,456-byte text GGUF, 768-dimensional output | Released Models 0.3.58 passes all eight oracle probes; minimum cosine 0.9998087 | Qualified for text embedding; Central marker publication follows this catalog merge |
| [Bonsai 2 27B PTQ1_0](https://github.com/ModelJars/modeljars/issues/215) | 5,946,648,928-byte text GGUF, signed Hadamard transforms | Eight greedy continuation tokens match the pinned Prism reference; separate-session test passes | Candidate; production qualification remains pending |

The Models protocol and raw evidence are under `docs/findings/2026-10-11-model-onboarding.md`
and `benchmark-results/2026-10-11-model-onboarding/` in the Models repository. Both exact
weight files have been installed and SHA-256 verified locally. The 5.95 GB figure describes
Bonsai's artifact, not the memory required to run it.

The initial Bonsai intake lacked an accepted comparator for the exact PTQ1_0 file. The
original `production-rag-model-contribution-v6` protocol required Ollama or Transformers;
Prism's publisher reference must not be described as Ollama. That failed intake remains part
of the history. Models now has a separate, explicit `production-rag-publisher-reference-v1`
protocol for the pinned publisher implementation and exact artifact. The current work reruns
its 200 public QA cases on the frozen candidate and measures correctness, latency, throughput,
and resources separately. Production qualification is still pending; comparator availability
alone does not qualify the model. Its computed candidate profile remains in `bonsai2/profile.json`;
the public profile manifest covers published models.

EmbeddingGemma 2's image, audio and video encoders are separate artifacts and future modality
work. Bonsai's vision projector is likewise outside the text candidate. Neither entry claims
those capabilities.

For EmbeddingGemma retrieval, the publisher prescribes `task: search result | query: ...` for
queries and `title: none | text: ...` for documents without titles. The raw eight-probe oracle
suite is kept unchanged to test execution equivalence; it does not measure retrieval quality.

## Release sequence

1. Merge [Models #263](https://github.com/integrallis/models/pull/263) after its CI and
   real-weight integration checks pass. Publish Models **0.3.58** through the documented
   dry run and release workflows. Its Vectors dependency remains **0.1.29**.
2. Fetch the published runtime JARs from Maven Central and rerun EmbeddingGemma's immutable
   oracle probes with those JARs on an isolated classpath. Retain dependency checksums,
   raw vectors, token IDs and the report in Models before deriving catalog qualification.
3. Prepare ModelJars **0.1.57**, with **all** Models dependencies locked to **0.3.58**.
   Run catalog, dependency-train and independent published-consumer checks, plus
   `:modeljars:embeddingGemma2IntegrationTest` against the locally verified real weights.
4. Rebase-merge the catalog PR; let GitHub Packages finish. Dispatch marker verification,
   then Central staging for only `ggml_org_embeddinggemma_2_gguf_q8_0`, finalize the exact
   deployment count and download its POM/JAR to verify publication. Bonsai is excluded
   until its production qualification passes.
5. Verify and publish the ModelJars library, finalize its deployment, verify the published
   dependency graph, and release its CLI for the supported platforms. Deploy Pages after
   the newly qualified marker is available from Central. Update the local CLI and Maven
   artifacts, then run and record the article's `gemma.java` against released dependencies.

Historical measurements and previous marker coordinates remain immutable. New modality or
production-performance claims require their own evidence; none is inferred from file size,
short token parity, or passing a publication workflow.

## Dimension labels

EmbeddingGemma 2 has a 512-wide hidden state and 768-dimensional output. The catalog keeps
`dimensions.embeddingLength` as the hidden-state width and obtains output width from the
measured embedding qualification. CLI and website descriptor labels now say “Hidden width”;
the website's qualified “Vector width” remains the actual output size. The projected-embedding
regression failed with the old website label and passes with the correction.

## Release verification

Models 0.3.58 is available from Maven Central. Its dry run and real release
([38156648214](https://github.com/integrallis/models/actions/runs/38156648214))
passed, including all native platforms and independent consumer graphs. The new
embedding qualification is derived from `embeddinggemma2-released-equivalence.json`,
whose isolated classpath uses Central runtime JARs and retains their digests.

The publication planner selects exactly one new marker, EmbeddingGemma 2, and no removals.
The generated catalog contains 459 candidates, 101 qualified model artifacts and
two qualified compositions. Existing individual model markers and measured qualification
reports are unchanged; the composition library coordinates advance as described below.

## Composition release coordinates

The generated site exposed one more dependency mismatch: Granite and Harriet still
selected their 0.1.55 libraries while this release builds 0.1.57. Their catalog
coordinates, model versions and download URLs now follow 0.1.57. The underlying
member artifacts and measured qualification reports are unchanged. A new npm/CI
consistency check rejects any future drift from `modeljarsVersion`; it failed on
the original 0.1.55 entries before this correction. Pages must wait for both the
new embedding marker and the composition libraries to reach Central.
