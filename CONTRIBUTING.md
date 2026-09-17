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
  idioms). Every value names its source file, revision, SHA-256, and key. A value the pinned files
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
before any composition can name it. Two component shapes exist, declared per entry by
`specialistKind`:

- `trained-tool-specialist` (the default): an adapter we trained. Provenance binds the frozen
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

Both shapes require real-weight JVM mechanics (physical storage identity, exact base continuation,
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
