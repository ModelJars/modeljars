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
and a clean-host Java 25 run. The clean-host output log is pinned by a raw `integrallis/models` URL at
any 40-hex commit and byte-verified by its recorded sha256 and size; it is usually an earlier commit
than `evidenceRevision`, because the report at `evidenceRevision` embeds the log's URI.

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

### Publishing newly qualified models, step by step

Follow these in order. Every step below exists because skipping it has already cost a failed
publication, and several of them fail in ways that look like a broken model rather than a skipped
step.

**0. Run the gates locally first.** All of them finish in under a minute each, and each one
corresponds to a required check. Running them here instead of discovering them in CI is the
difference between one push and five:

```bash
npm test
npm run catalog:verify-components
npm run catalog:verify-compositions
npm run catalog:profiles:check          # regenerate with: npm run catalog:profiles
npm run catalog:enrich -- --changed-from=origin/main    # add --write to refresh stale GGUF profiles
./gradlew test
node tools/qualification-smoke-gate.mjs \
  --previous <catalog/qualifications.json from origin/main> \
  --current catalog/qualifications.json --catalog catalog/models.json --verify-remote
node tools/plan-model-publications.mjs ...              # see .github/workflows/model-artifacts.yml
node tools/qualification-generation-gate.mjs --base origin/main
```

Advance `generatedAt` in any qualification manifest you change. The generation gate fails otherwise,
and its reason is not cosmetic: markers published from the earlier content would conflict with the
bundled catalogue at the same instant.

`plan-model-publications.mjs` is the one most easily forgotten and the one that blocks hardest: a
marker jar embeds the model entry, its performance profiles **and** its qualification manifests, so
qualifying a model that already existed as a candidate changes its marker and the planner demands a
new `markerCoordinate`. Bump only the trailing publication revision (`…-q4_k_m.1` ->
`…-q4_k_m.2`); the groupId and artifactId must never change. Repoint any
`catalog/performance-profiles.json` entry that cites the old coordinate.

Changing the qualification of a model that is **already published** has a second consequence: if that
model is a member of a composition, the composition's measured evidence report names its members by
coordinate, and bumping the member makes that evidence disagree with the catalogue. Do not edit the
report to match — re-measure the composition in the same pass, or leave that model's qualification
alone for this publish.

**1. Merge the catalog to `main`.** This repository allows **rebase merges only** — not merge
commits, not squash. `gh pr merge <n> --rebase`.

**2. Let the push-triggered `Model artifacts` run finish.** A push to `main` publishes markers to
**GitHub Packages only**. Its `maven-central` job is guarded by `inputs.target` and never runs on a
push, so nothing has reached Central yet.

**3. Run `Model artifacts` with `target=verify` before publishing anything.** This is the documented
precondition for either publication target and it reports what is publishable without touching
Central.

**4. Dispatch `Model artifacts` with `target=maven-central` and the EXACT ids.** The reserved value
`all` **bootstraps a complete catalogue** and must not be used for an incremental publish: Central
refuses to republish a component that already exists, so every already-published marker in the batch
fails validation and `Finalize Central deployments` then refuses the whole batch. Pass only the
qualified models whose coordinate is genuinely absent from Central. To compute that set, probe each
coordinate rather than assuming:

```bash
# for each qualified entry's markerCoordinate group:artifact:version
curl -s -o /dev/null -w '%{http_code}' \
  "https://repo1.maven.org/maven2/${group//.//}/${artifact}/${version}/${artifact}-${version}.pom"
```

Intersect "qualified" with "404 from repo1". Compositions
(`granite_4_1_3b_answerability_hybrid`, `harriet_qwen3_5_4b_decisions`) are not in
`catalog/models.json` and publish through their own `modeljars-composite-*` modules — exclude them
from `model_ids`. The resulting count should equal the number of publications
`plan-model-publications.mjs` reports; if it does not, stop and find out why.

**5. Approve the `maven-central` environment.** Both the staging run and the finalize run pause in
GitHub's `waiting` state for this approval. `waiting` is not a failure — do not treat it as one.

**6. Wait for every `Stage …` job to reach a terminal state, polling the JOBS and not the run.**
GitHub has been observed reporting a run `completed/success` while one matrix job was still
`in_progress`. Gate on
`gh api repos/ModelJars/modeljars/actions/runs/<id>/jobs?per_page=100` and count conclusions.

**7. Dispatch `Finalize Central deployments` with both required inputs.** Staging uploads each marker
as a USER_MANAGED deployment named `modeljars-<model-id>-<run-id>`, so:

- `deployment_name_filter` = the staging run id
- `expected_count` = the number of successful `Stage …` jobs in that run

The workflow refuses to publish unless the count matches, which is what stops a half-staged batch
going out. Never finalize a partial batch.

**8. Verify the artifacts on `repo1.maven.org`, never the workflow status.** `Model artifacts` and
`publish` can both report failure while the deployment is still `PUBLISHING` on Sonatype's side and
succeeds minutes later; conversely a green workflow is not proof a jar resolves. Use
`node tools/verify-central-catalog.mjs build/site/catalog.json` and wait for it to pass.

**9. Only then dispatch `pages`.** The site deploy hard-verifies every marker's POM and JAR against
`repo1`. Dispatching it before Central has synchronized fails the deploy on a 404 that looks like a
missing model but is only a missing publication step.

**If a release workflow fails waiting for `PUBLISHED`,** do not re-run it. A second deployment of the
same version collides with the one already in flight. Check
<https://central.sonatype.com/publishing/deployments>, and if the artifact later appears on `repo1`,
finish the release by hand — for `models` that means creating the tag and GitHub release, which the
workflow does after the publish gate it never reached.
