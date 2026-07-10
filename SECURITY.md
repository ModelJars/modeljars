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
