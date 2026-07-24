# Security Policy

## Reporting Vulnerabilities

Please report security issues privately through GitHub Security Advisories once the public repository
is created. Do not open public issues for vulnerabilities in release automation, signing, credential
handling, or catalog validation bypasses.

## GitHub Actions

Pull request workflows must not publish artifacts or access Maven Central credentials.
Publishing credentials belong only in the protected `maven-central` GitHub Environment.

Avoid running untrusted pull request code in `pull_request_target` workflows. If a privileged workflow
is needed for labeling or comments, it must not check out or execute code from the pull request branch.

## Release Integrity

Every Maven Central deployment is assembled as a reproducible archive: JAR and ZIP entries have stable
ordering and do not preserve build timestamps. Every POM, module descriptor, and JAR has an OpenPGP
signature plus SHA-256 and SHA-512 checksums. The protected release job also creates a GitHub artifact
attestation for the exact Central deployment bundle before uploading it as a `USER_MANAGED` deployment.

Verify a downloaded release bundle against the public ModelJars build provenance:

```bash
gh attestation verify modeljars-central-bundle.zip --repo ModelJars/modeljars
```

After extracting the bundle, compare an artifact to its recorded SHA-256 value and verify its detached
OpenPGP signature with the published ModelJars release key:

```bash
test "$(sha256sum modeljars-0.1.0.jar | cut -d ' ' -f 1)" = \
  "$(tr -d '\n' < modeljars-0.1.0.jar.sha256)"
gpg --verify modeljars-0.1.0.jar.asc modeljars-0.1.0.jar
```

The generated ModelJar metadata independently pins each model payload by byte size and SHA-256. The
installer refuses a payload that does not match both values.

Obfuscation is not an integrity control and is intentionally excluded. It would make source-to-binary
review and reproducible-build comparisons harder without preventing modification. OpenPGP signatures,
checksums, and provenance attestations provide verifiable tamper evidence without changing runtime
performance.
