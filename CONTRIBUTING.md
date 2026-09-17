# Contributing to ModelJars

ModelJars is intended to be a neutral, community-maintained catalog of JVM model marker metadata.

Anyone may open a pull request. Only maintainers can merge catalog changes, and only GitHub Actions
running from the protected `main` branch can publish artifacts.

## Submit a Candidate

Install the ModelJars CLI, then point it at a public Hugging Face repository:

```bash
modeljars contribute Qwen/Qwen2.5-0.5B-Instruct --domain general
```

The command resolves the requested revision to an immutable commit, selects a single GGUF or the
complete standard Safetensors bundle, verifies byte sizes and SHA-256 digests, and writes a new
candidate issue body. It then prints one copy-ready `gh issue create` command. Repositories with
multiple GGUF variants require `--file`; `--license`, `--capability`, and `--domain` can correct or
complete upstream metadata.

This is the preferred contribution path. A candidate issue is an intake record, not a claim that
Models can execute the artifact or that it passed qualification. Maintainers carry the accepted
candidate through runtime tests, controlled measurements, the catalog pull request, and publishing.

## Catalog Changes

Catalog pull requests must:

- add or update entries only in `catalog/models.json`; generated marker JARs and website data must
  not be committed;
- add or update metadata only for models whose upstream source is public and attributable;
- include the upstream source URL and model license;
- pin an immutable upstream revision, download URL, byte size, and SHA-256 digest;
- avoid mirroring model weights in this repository;
- describe the format, architecture, quantization, capabilities, and supported backends;
- let maintainers record `catalogPublishedAt` as the first public marker publication instant;
- use a new immutable marker artifact version for every published metadata change.

Run `./gradlew spotlessCheck test verifyCatalog` before opening a pull request. Use
`./gradlew spotlessApply` to format Java sources. CI generates every marker JAR and
the website catalog from the metadata, then rejects duplicate coordinates, mutable download URLs,
invalid versions, missing integrity fields, or inconsistent filenames.

## Generation Profiles and Memory Fit

`catalog/model-profiles.json` records, per exact artifact, the vendor-published generation settings
and a computed memory fit. It is regenerated with `npm run catalog:profiles` (set `HF_TOKEN` for
gated repositories) and checked with `npm run catalog:profiles:check`.

- **Generation profile.** Sampling values (temperature, top-p, top-k, min-p, repetition penalty),
  every declared end-of-sequence token ID, reasoning markers, and whether the chat template enables
  thinking by default are read only from files at the pinned revision: the repository's
  `generation_config.json` and the GGUF header (`general.sampling.*`, `tokenizer.ggml.eos_token_id`
  / `eot_token_id` / `eom_token_id`, the vocabulary, and recognised `tokenizer.chat_template`
  idioms). When the GGUF chat template ends an assistant turn with a special token the header does
  not declare as end-of-sequence (Gemma 3 `<end_of_turn>`, MiniCPM5 `<|im_end|>`), that token is
  added to the end-of-sequence IDs with `tokenizer.chat_template` provenance and the token text. It
  is read by rendering the template itself (pinned `@huggingface/jinja`) on a user and assistant
  turn and taking the special token that immediately follows the assistant content; a template that
  does not render, or does not place a special token there, records "not determined" in the
  coverage and adds nothing. Every value names its source file, revision, SHA-256, and key. A value the pinned files
  do not publish is left absent; it is never guessed or copied from a different repository. When
  sources disagree, the `generation_config.json` value is recorded and the disagreement is kept.
- **Memory fit.** For GGUF generators the file records KV bytes per token at f16 and q8_0, the total
  at 4K/32K/128K/256K tokens (capped at the context length), and the largest context that fits
  8/16/24 GiB. It is computed from header metadata with the formula in `tools/model-profiles.mjs`
  and a stated 1 GiB runtime-overhead constant; it is not a measurement. Sliding-window layers are
  charged the declared window only, and a window without a declared per-layer pattern is charged as
  full attention and marked as an upper bound.

Profiles are deliberately outside `catalog/models.json`: they ship in the aggregate catalog, the
website, and the CLI, never inside a marker JAR, so adding or correcting one never requires a new
marker coordinate.

## Repetition-Loop Stop Rate

`catalog/generation-safety.json` holds one optional measured metric per exact artifact, backend,
and workload: the repetition-loop stop rate at the model's documented generation profile. Every
entry is absent until a run is recorded, and the website and `modeljars show` display "not
measured" until then. It is never computed, estimated, or defaulted to zero. The manifest's
`repetitionLoopMethod` is fixed by `tools/generation-safety.mjs`, which `npm test` validates.

How a value is measured:

1. Use Models 0.3.41 or later, the first release with the detector. Enable it with
   `SamplingOptions.repetitionLoopDetection(new RepetitionLoopDetection(maxSpan, minRepeats,
   minLoopTokens))`. It is off by default.
2. Apply every sampling value that `catalog/model-profiles.json` documents for the exact artifact
   (temperature, top-p, top-k, min-p, repetition penalty), using exactly those values. A model
   whose profile documents no sampling value has no documented generation profile and cannot carry
   this metric. Today that includes the Qwen3 GGUF artifacts, whose pinned files publish no
   sampling settings.
3. Generate once per workload case with fresh model state on the recorded backend. `stops` is the
   delta of `RuntimeTextGenerationModel.repetitionLoopStops()` (or `GenerationLoop` /
   `ContinuousBatchingMetrics`) across the run, cross-checked against each generation's
   `StopReason.REPETITION_LOOP`. `generations` counts completed generations, and
   `stopRate = stops / generations`.
4. Record the detector thresholds, the sampling actually applied, the Models version and commit,
   the workload, and the raw report path and SHA-256. The rate depends on the thresholds, so it
   is never compared across different detector settings.

What it cannot show: the detector stops only exactly periodic output. Instructed or legitimately
repeated output counts as a stop, and a loop whose tokens drift is not caught. A zero rate on a
workload that never elicits loops is no data about loops, not evidence that they are absent.

The manifest lives outside `catalog/qualifications.json` on purpose. Qualification entries are
embedded in marker JARs, so a metric added after publication would force a new marker coordinate.
Like model profiles, it ships only in the aggregate catalog, the website, and the CLI.

## Qualification

Catalog registration is not publication approval. A marker appears on ModelJARs.org and becomes
eligible for GitHub Packages or Maven Central only when the exact artifact has a qualified entry in
`catalog/qualifications.json`.

Qualification requires:

- exact-artifact parser, tokenizer, tensor-layout, and generation tests in
  [`integrallis/models`](https://github.com/integrallis/models);
- a controlled Java 25 run using
  `scripts/run-controlled-rag-qualification.sh` from the Models repository;
- a successful `default-correctness` report for the exact model/backend pair,
  using library-default Models properties, the longest-common-prefix cache,
  every workload case, and no failed generation attempts;
- a format-compatible independent reference in the separate performance phase: Ollama for
  compatible GGUF artifacts or the pinned Transformers reference for formats Ollama and llama.cpp
  cannot ingest; llama.cpp remains supporting GGUF evidence;
- raw report files, artifact and report SHA-256 values, environment identity, and a passing
  `production-rag-model-contribution-v6` verdict.

### Admission policy for new entries

These rules apply to entries proposed from 2026-09-16 onward. Existing qualified entries are not
retroactively failed by them.

- **A fine-tune is admitted only if it beats the same-size base model quantisation on our harness.**
  The comparison uses the same quantisation of the base model, the same workload, and the same
  controlled run; a fine-tune that does not measurably beat that base is not qualified, whatever
  its own model card reports.
- **A qualified entry must carry its generation profile where the vendor publishes one.** If the
  pinned `generation_config.json` or GGUF header publishes sampling settings, end-of-sequence
  tokens, or reasoning markers, the entry's `catalog/model-profiles.json` record must contain them
  with provenance. Where nothing is published, the coverage record says so.

New or changed qualified entries must include `defaultConfigurationSmoke`
metadata pointing to the immutable Models report. CI fetches that report from
the declared Models commit, verifies its SHA-256 and exact artifact/backend,
and rejects tuned properties or any failed attempt. Existing evidence is
grandfathered until it changes; tuned benchmark success cannot override a
failed default-configuration smoke.

### Tool-calling template round trip

Every tool-qualified entry in `catalog/tool-qualifications.json` must survive a model-free round
trip through the chat template the runtime selects for it (the tool qualification's template and
that of any production RAG qualification for the same artifact). `ToolCallTemplateRoundTripTest`
in the `modeljars` module renders a conversation that declares a tool and contains an assistant
tool call through the Models `ChatTemplate`, scans the rendered assistant turn back with the same
template's `ToolSyntax` and `ToolCallScanner`, and requires the call name and JSON arguments to be
recovered exactly, including nested, escaped, and non-ASCII values. It runs in `./gradlew test`.
A template whose rendered calls its own scanner cannot recover invalidates every tool-calling
measurement taken through it, so a failing entry is not qualified.

### Embedding artifacts

We test that an embedding model produces the same vectors as llama.cpp. The harness is
`./gradlew :models-bench:run --args="embedding-equivalence --model <artifact.gguf> --report <out>"`
from the Models repository.

Submissions carry the report, the artifact and report SHA-256 values, the probe-set SHA-256, the
pinned oracle version, and environment identity.

### Composite artifacts

A routed conversation is not automatically a hybrid model, and shared text history is not shared
model state. A composite can be published only when its claimed handoff mechanism is implemented
and exercised end to end. Partial or negative results remain experiments and must not appear in the
qualified catalog.

For every proposed composite, CI requires a versioned machine-readable report that:

- has no unresolved required work and is fetched from an immutable 40-character Models commit with
  a matching SHA-256;
- runs every exact member artifact through the public Java API, without an external inference
  runtime;
- identifies an actual cache-state mechanism: exact KV-block sharing, qualified cross-model KV
  translation, or an activation-compatible adapter prefix;
- retains every native-correct exact answer in the declared long-context gate and passes the task
  correctness suite;
- beats target re-prefill after charging the complete handoff cost, at a declared context-length
  crossover; and
- records peak process memory, including all resident models, mapper weights, and cache state.

`tools/composition-evidence-gate.mjs` enforces this contract in validation, preview, artifact, and
release workflows. Documentation or catalog metrics cannot substitute for the underlying report.

### Activated specialist components

A hybrid's hidden adapter component is qualified separately by `tools/component-evidence-gate.mjs`
before any composition can name it. Three component shapes exist, declared per entry by
`specialistKind`:

- `trained-tool-specialist` (the default): a tool-calling adapter we trained. Provenance binds the frozen
  evaluation selection, training manifest, formatter, and trainer; task correctness is the fixed
  300-case tool window; real-weight plain Java, Spring AI, and LangChain4j tool loops are required.
- `upstream-rag-specialist`: a publisher-trained adapter that Models runs unchanged. Provenance
  binds the upstream repository, revision, adapter weights, configuration, model card, tokenizer
  files, and license. Task correctness is a frozen window of at least two public datasets with at
  least 100 cases each, every completion structured, balanced accuracy at least 0.80 and no worse
  than the unadapted base, and physical prefix sharing on every case, with the rendered prompts
  proven identical to the publisher's chat template. A window run on a native kernel arm must
  also bind token identity with pure Java on at least ten cases per suite and arm. The component
  claims no Spring AI or LangChain4j surface; it is usable through the Models Java activated API.
- `first-party-rag-specialist`: a RAG adapter Integrallis trained itself. It is not upstream, so
  its report says `upstream: false` and must not borrow the upstream fields. Provenance names the
  publisher, the training repository, and the 40-hex commit holding the trainer, data preparation,
  and manifests; the training manifest and prepared-data manifest are pinned as raw GitHub URLs at
  that commit and byte-verified by the gate, and the adapter weights and configuration must be the
  files the training manifest recorded (and the weights must be the file the catalog publishes).
  Provenance also binds the model card, adapter license, tokenizer files, and a license for every
  training dataset. Every task-correctness, conformance, mechanics, long-context, and sharing
  requirement of `upstream-rag-specialist` applies unchanged, with one tightening: a fine-tune is
  admitted only if it beats its base, so every suite must be strictly better than the unadapted
  base. A suite scored on confirmed rather than dataset labels (`labelSource: confirmed`) must bind
  the label set's `labelsSha256` and keep its dataset-label `originalBalancedAccuracy` beside it.
  ModelJars checks these numbers are present and consistent; it does not re-score the window, and
  the training data and trainer are evidence to inspect, not something the gate re-runs.

All three shapes require real-weight JVM mechanics (physical storage identity, exact base continuation,
disabled-adapter no-op), the fixed 4,096-token long-context retention gate, the 256/1,024/4,096
prefix-sharing crossover with complete memory accounting, released Maven Central Models artifacts,
and a clean-host Java 25 run.

The public [qualification and submission guide](https://modeljars.org/contribute/) explains the
acceptance gates and pull request contents. “Not yet qualified” means the controlled run has not
been completed; it does not mean the candidate failed.

## Review

Catalog metadata changes require approval from `@modeljars/catalog-maintainers`.
Infrastructure, build, and workflow changes require approval from `@modeljars/infra-maintainers`.
Core API changes require approval from `@modeljars/core-maintainers`.

## Publishing

Publishing is performed by GitHub Actions from `main` through the protected `maven-central`
environment. Contributors should not publish ModelJars artifacts from local machines.
