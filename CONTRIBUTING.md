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
- use a new immutable marker artifact version for every published metadata change.

Run `./gradlew test verifyCatalog` before opening a pull request. CI generates every marker JAR and
the website catalog from the metadata, then rejects duplicate coordinates, mutable download URLs,
invalid versions, missing integrity fields, or inconsistent filenames.

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
  `production-rag-model-contribution-v5` verdict.

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
