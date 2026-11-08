# Pre-merge LLaVA catalog verification

Targets are `second_state_llava_v1_5_7b_gguf_q4_k_s` and
`second_state_llava_v1_5_7b_gguf_q4_k_m`, with their pinned F16 projector. Qwen3-VL
is outside the runtime supported by Models PR380.

The catalog descriptors are candidates. They do not enter the public catalog or
publication plan until backend admission, ModelJars execution, and released-runtime
verification all pass. Existing catalog qualifications are unchanged.

## Evidence and admission

The frozen workload, hashes, gates, raw two-arm outputs and final attempt report live
in Models `experiments/vision-catalog-qualification/`. It uses 32 VQAv2 questions,
32 TextVQA questions and 16 paired blank-image controls per quantization. The bounded
subset is not a publisher benchmark reproduction. See that protocol for the exact
accuracy, token, component, numerical and strongest-reference performance gates.

Import an attempt only after its report is committed:

```sh
node tools/import-vision-qualification.mjs \
  /path/to/admission-attempt.json \
  https://raw.githubusercontent.com/integrallis/models/FULL_RESULT_COMMIT/experiments/vision-catalog-qualification/results/RUN/admission-attempt.json \
  catalog/vision-qualifications.json
```

The importer derives every score and ratio from the report, binds both GGUF digests,
checks all gates, records the measured Models commit, and initializes both publication
verification flags to false. A failed attempt remains rejected evidence. It refuses
to overwrite a nonempty qualification manifest.

A branch backend pass can be loaded from its explicit preview marker. It cannot enter
the public runtime registry, aggregate catalog, site, or publication plan. ModelJars
and release verification flags require checksummed, commit-pinned receipts; released
evidence must name the exact measured Models release version. Never relabel source
measurements as released-runtime measurements.

## Build the branch payload

The optional source composite enables a pre-merge integration test without publishing
unreleased libraries. Release dependency validation continues to reject source
substitution. No release versions or dependency locks are changed for publication.

```sh
./gradlew -PmodelsSource=../models-vision-qualification \
  -PmodelsVersion=0.5.14 -PvectorsVersion=0.1.33 --write-locks \
  :modeljars-core:test :modeljars:test :modeljars-cli:test \
  :modeljars:stageVisionQualificationRuntime :modeljars-cli:installDist \
  :modeljars-catalog:markerJarSecondStateLlavaV157bGgufQ4KS \
  :modeljars-catalog:markerJarSecondStateLlavaV157bGgufQ4KM
```

Preserve the resolved preview locks alongside the payload receipt, then restore those
local lockfile changes. The staged runtime lives at
`build/vision-qualification-runtime/lib`. Add each generated marker from
`modeljars-catalog/build/libs/markers`, and record every JAR hash. Copy this isolated
payload to the Linux worker; never download weights or run model inference on the Mac.
Compile the owned Rust kernels on Linux at the same Models runtime commit.

Generate the ordinary CLI demo with the preview marker on the CLI classpath,
`-Dmodeljars.catalog.offline=true` and an empty
`-Dmodeljars.catalog.cache=/tmp/vision-cli-catalog`. Otherwise the CLI correctly loads
the current public catalog, which does not contain an unpublished candidate. The demo
must call `ModelJars.openVisionRuntime` and accept an image path. Execute its unchanged
Java source against the isolated preview runtime. This proves the generated source,
not Central/JBang dependency resolution; the latter requires the later release repeat.

## Public API smoke protocol

After all paired measurements finish on the worker, run `PreviewVision.java` with
no tuning properties. The only runtime property needed by this source payload is the
explicit Linux native-library path; `modeljars.cache` selects an empty isolated cache.
The first call must install and verify both HTTPS artifacts through the public loader.

The TSV input has three rows: the first selected original VQAv2 question, the first
selected original TextVQA question, and the first question again to exercise reset.
Each row contains case ID, image path, base64 question and base64 expected answer
from the measured candidate output, separated by tabs. No labels are used to choose
cases, no prompt changes and no sampling changes. Output must match the already
measured candidate in all three calls. Record context capacity, backend diagnostics,
JVM arguments, properties, file hashes, load duration and every answer/latency.

Run the CLI-generated demo on the first of those images as a separate process using
the same cache. Keep stdout and stderr. If backend qualification fails, the public
loader must reject it before downloading or opening a model; record that refusal
instead of manufacturing a passing marker.

After a successful release train (Vectors → Models → ModelJars), repeat the consumer
smoke with actual released JARs and the generated JBang dependencies. Attach its
immutable receipt, then admit the entries for marker publication. This pre-merge
branch must not publish a marker whose runtime dependency lacks the API it calls.
