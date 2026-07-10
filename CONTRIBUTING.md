# Contributing to ModelJars

ModelJars is intended to be a neutral, community-maintained catalog of JVM model marker metadata.

Anyone may open a pull request. Only maintainers can merge catalog changes, and only GitHub Actions
running from the protected `main` branch can publish artifacts.

## Catalog Changes

Catalog pull requests must:

- add or update metadata only for models whose upstream source is public and attributable;
- include the upstream source URL and model license;
- avoid mirroring model weights in this repository;
- describe the format, architecture, quantization, capabilities, and supported backends;
- use a new immutable marker artifact version for every published metadata change.

## Review

Catalog metadata changes require approval from `@modeljars/catalog-maintainers`.
Infrastructure, build, and workflow changes require approval from `@modeljars/infra-maintainers`.
Core API changes require approval from `@modeljars/core-maintainers`.

## Publishing

Publishing is performed by GitHub Actions from `main` through the protected `maven-central`
environment. Contributors should not publish ModelJars artifacts from local machines.
