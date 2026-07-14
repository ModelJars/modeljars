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

The Integrallis namespace and credentials do not authorize publication under `org.modeljars`.
Register a publisher identity at the [Central Portal](https://central.sonatype.com/), request the
`org.modeljars` namespace, and prove ownership by placing the verification TXT value on the exact
`modeljars.org` DNS name. Verify the DNS record before asking Central to check it so an NXDOMAIN
response is not cached.

After the namespace is verified, generate a dedicated Central Portal user token for CI. A
`modeljars.org` mailbox and a project-specific signing key are recommended for maintainership
continuity, but the namespace is authorized through DNS rather than the mailbox address. The
Gradle Plugin Portal is not involved unless ModelJars later publishes a Gradle plugin.

Publishing should remain blocked until Central Portal upload and signing are wired into the build.
The facade release must contain these coordinates and transitive dependency:

```text
org.modeljars:modeljars:0.1.0
  -> org.modeljars:modeljars-core:0.1.0
```
