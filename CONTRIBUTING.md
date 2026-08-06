# Contributing to ModelJars

ModelJars is intended to be a neutral, community-maintained catalog of JVM model marker metadata.

Anyone may open a pull request. Only maintainers can merge catalog changes, and only GitHub Actions
running from the protected `main` branch can publish artifacts.

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
- identical GGUF bytes, prompts, controls, and host hardware for Models, Ollama, and llama.cpp
  comparator reports in the separate tuned performance phase;
- raw report files, artifact and report SHA-256 values, environment identity, and a passing
  `production-rag-model-contribution-v4` verdict.

New or changed qualified entries must include `defaultConfigurationSmoke`
metadata pointing to the immutable Models report. CI fetches that report from
the declared Models commit, verifies its SHA-256 and exact artifact/backend,
and rejects tuned properties or any failed attempt. Existing evidence is
grandfathered until it changes; tuned benchmark success cannot override a
failed default-configuration smoke.

### Embedding artifacts

Embedding models do not run the RAG workload. Their gate is
`./gradlew :models-bench:run --args="embedding-equivalence --model <artifact.gguf> --report <out>"`
from the Models repository, which compares vectors for a pinned probe set against a pinned
llama.cpp build over the same bytes.

Submissions carry the report, the artifact and report SHA-256 values, the probe-set SHA-256, the
pinned oracle version, and environment identity. `defaultConfigurationSmoke` does not apply.

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
