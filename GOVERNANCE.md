# ModelJars Governance

ModelJars should be owned by the `modeljars` GitHub organization, not by a vendor organization.
The project can acknowledge sponsors, but project identity, Maven coordinates, and release authority
belong to the ModelJars community.

## Maintainer Teams

- `catalog-maintainers`: review model metadata and catalog entries.
- `core-maintainers`: review runtime API, registry, and locator code.
- `infra-maintainers`: review CI, publishing, repository settings, and release credentials.

## Release Authority

Artifacts are released only by GitHub Actions from the protected `main` branch. The release workflow
must use a protected GitHub Environment for Maven Central credentials and should require a maintainer
approval before deployment.

Published Maven artifacts are immutable. Incorrect metadata must be corrected by publishing a new
marker artifact version, not by attempting to replace an existing release.

## Repository Protection

The `main` branch should require:

- pull requests before merging;
- required status checks from `.github/workflows/validate.yml`;
- CODEOWNERS review;
- conversation resolution;
- signed commits when practical;
- restricted direct pushes.
