# GitHub Setup

GitHub does not expose organization creation through the `gh org` command. Create the organization
once in the GitHub web UI, then use the CLI for the repository setup.

## 1. Create Organization

Create:

```text
modeljars
```

Recommended organization settings:

- require two-factor authentication for members;
- default repository permission: read;
- restrict member repository creation;
- create teams `infra-maintainers`, `core-maintainers`, and `catalog-maintainers`.

## 2. Create Repository

From this local repository:

```bash
gh repo create ModelJars/modeljars \
  --public \
  --description "Community-maintained JVM marker JAR catalog for local and remote AI models" \
  --homepage "https://modeljars.org" \
  --source=. \
  --remote=origin \
  --push
```

## 3. Protect `main`

Configure branch protection for `main`:

- require pull request before merging;
- require CODEOWNERS review;
- require status check `Build and test`;
- require conversation resolution;
- restrict who can push.

## 4. Configure Publishing Environment

Create a GitHub Environment:

```text
maven-central
```

Add required reviewers from `infra-maintainers`.

Add secrets:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
```

Publishing should remain blocked until the metadata-driven catalog generator and Maven Central
signing/publishing plugin are wired into the build.
