# Pre-merge LLaVA catalog verification

Targets are `second_state_llava_v1_5_7b_gguf_q4_k_s` and
`second_state_llava_v1_5_7b_gguf_q4_k_m`, with their pinned F16 projector. Qwen3-VL
is outside the runtime supported by Models PR380. The candidates do not claim stock
llama.cpp multimodal compatibility: the converted 23-block projector has an upstream
loader issue documented in Models. The independent benchmark uses PyTorch CLIP with
the external llama.cpp text decoder, not its stock multimodal loader.

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
verification flags to false. `npm run catalog:verify-vision` fetches the exact report,
checks its digest and compares every imported score, ratio and verdict to that report.
Validation, marker publication and Pages all run this gate. A failed attempt remains rejected evidence. It refuses
to overwrite a nonempty qualification manifest.

Separate quantization workers produce separate attempt reports. Import each into a new temporary
manifest, then combine their entries without changing the per-entry report URL or digest. A
single-variant report must declare the same variant in `receipt.variants` and name its measured
host. It must still cover the complete corpus, all performance cases and every admission gate.
Do not combine two hosts' measurements into a fabricated shared-host receipt.

A branch backend pass can be loaded from its explicit preview marker. It cannot enter
the public runtime registry, aggregate catalog, site, or publication plan. ModelJars
and release verification flags require checksummed, commit-pinned receipts; consumer receipts must
cover both datasets, reset, exact installed files, native execution and public defaults. Released
evidence must name the exact measured Models release version and carry a passing
`modeljars-vision-release-v1` report with that `modelsVersion` and its full `consumer`
report from `PreviewVision.java`. Never relabel source
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
Use `COPYFILE_DISABLE=1 tar ...` when packaging on macOS to exclude AppleDouble
metadata. The preview runner verifies executable JAR hashes and excludes `._*` files
from its explicit classpath.

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

`run-preview.py /opt/vision/final-repeat /opt/vision/modeljars-preview-COMMIT
--native-library /opt/vision/final-repeat/native-candidate.so` automates both
outcomes after measurement and diagnosis finish. It requires completed two-arm collection and
admission evaluation, and verifies that the native library matches the measured receipt hash.
Put the two markers generated from
the imported attempt in the payload's `measured-markers` directory. Passing candidates
run the three public API cases and the unchanged CLI-generated demo. Failed candidates
must produce the specific qualification rejection and leave the isolated cache empty.
An execution receipt marked `passed` for a rejected candidate means admission was
correctly refused; its separate `backendQualified` field remains false.

After the runtime release train (Vectors → Models → ModelJars), resolve the actual
released SDK and backend JARs from Central into a fresh dependency cache, including
the backend-native JAR that bundles the Linux native library. Repeat the consumer and generated Java source with
those released runtime JARs and the explicitly staged candidate marker. Record
every artifact coordinate and digest. Do not use the source-composite runtime or
an external native-library override for this release repeat. The candidate marker
is still local at this point; this does not prove its Central resolution.

The consumer records the actual JAR file names and digests that provide ModelJars,
Models API, Java backend, native backend, and Vectors classes. The release receipt
names their released versions and Central URLs. The evidence gate independently
fetches those Central JARs and compares their bytes with the loaded-JAR digests;
a release label or a successful source build cannot substitute for that check.

Attach the immutable passing release receipt, then admit only the exact qualified
entries for marker publication. After publishing and verifying those marker JARs
on Central, run the unchanged generated JBang demos in a fresh cache using their
ordinary dependency declarations. Preserve that final consumer result before
publishing the site update. This order verifies the released library before model
publication without pretending an unpublished marker already resolves on Central.

The Linux release runner resolves the SDK through a fresh Gradle user home and a
Central-only repository, verifies the five loaded runtime JARs against independently
fetched Central bytes, and writes one release receipt for each passing backend candidate:

```sh
python3 run-release.py --campaign /opt/vision/campaign-quick \
  --payload /opt/vision/modeljars-release-payload --output /opt/vision/released-consumers \
  --gradle-wrapper /opt/vision/modeljars-wrapper/gradlew --java /opt/java/bin/java \
  --modeljars-version MODELJARS_RELEASE --models-version MODELS_RELEASE --vectors-version VECTORS_RELEASE
```

Use actual published versions. The payload contains `PreviewVision.java` and the
measured candidate markers under `measured-markers`. The runner resolves the released
CLI separately, verifies its JAR against Central, and uses it to generate each demo.
It checks that the generated JBang declarations name the exact SDK release and marker,
then executes the unchanged source using only the SDK dependency graph. CLI dependencies
cannot conceal a missing SDK dependency. The receipt records the CLI JAR, dependency
graph, and generated source hashes.
The markers must carry the released backend version after that release is verified.
The runner rejects an attempt with no passing candidates, preserves existing output,
and never supplies an external native-library path. Its `markerPublished: false`
field records the remaining, separate Central/JBang marker check.
