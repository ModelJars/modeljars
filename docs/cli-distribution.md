# ModelJars CLI distribution

The `modeljars` CLI is compiled with GraalVM Native Image and requires no Java installation at
runtime. It deliberately contains catalog, provenance, download, verification, and cache-management
code only. In-process inference remains in the Java 25-or-newer `org.modeljars:modeljars` runtime.

## User commands

```bash
modeljars search [query]
modeljars show <alias|coordinate>
modeljars pull <alias|coordinate> [--cache <directory>]
modeljars list
modeljars cache-dir
modeljars version
```

`search` reads the qualified catalog embedded in the CLI. `show` prints the exact source page,
download URL, upstream revision, byte size, SHA-256, license, and destination cache path. `pull`
downloads and verifies the artifact before atomically placing it in the shared content-addressed
cache. `list` contains the files currently present at known catalog paths; `pull` and the JVM
Runtime verify them again before use.

## Release assets

`.github/workflows/cli-release.yml` runs a native build on each target architecture rather than
cross-compiling:

| Target | GitHub runner | SDKMAN platform |
| --- | --- | --- |
| Linux x86-64 | `ubuntu-24.04` | `LINUX_64` |
| Linux ARM64 | `ubuntu-24.04-arm` | `LINUX_ARM64` |
| macOS Intel | `macos-15-intel` | `MAC_OSX` |
| macOS Apple Silicon | `macos-15` | `MAC_ARM64` |
| Windows x86-64 | `windows-2025` | `WINDOWS_64` |

Each job smoke-tests `version` and a real catalog search, then uploads a raw executable, its SHA-256
sidecar, an SDKMAN archive, and that archive's SHA-256 sidecar to the GitHub release.

The release also publishes `org.modeljars:modeljars-cli:<version>` to GitHub's Maven Packages. The
same executable JAR is part of the signed Maven Central bundle as a Java 21 fallback.

## Package channels

- Homebrew: `brew install integrallis/tap/modeljars`
- Scoop: add `https://github.com/integrallis/scoop-bucket`, then `scoop install modeljars`
- Direct installer: `curl -fsSL https://raw.githubusercontent.com/ModelJars/modeljars/main/install.sh | sh`
- GitHub Releases: raw executables and SDK archives for every supported target
- GitHub Packages and Maven Central: Java 21 executable JAR
- SDKMAN: `sdk install modeljars` after candidate approval

Homebrew and Scoop publication requires the `PACKAGES_PUBLISH_TOKEN` repository secret. The token
must be able to push to `integrallis/homebrew-tap` and `integrallis/scoop-bucket`.

SDKMAN requires its vendor onboarding process before the workflow can publish. Create the
`modeljars` candidate in SDKMAN's database-migrations repository, send the project's armored public
GPG key to SDKMAN, then store the returned credentials as `SDKMAN_CONSUMER_KEY` and
`SDKMAN_CONSUMER_TOKEN`. The workflow already submits every supported platform with a SHA-256.

## Release sequence

1. Merge a fully verified version change to `main`.
2. Publish the signed Maven Central bundle.
3. Create and publish `v<version>` on the same commit.
4. The CLI workflow builds and attaches native assets, publishes the GitHub Maven package, and
   updates Homebrew and Scoop.
5. After SDKMAN onboarding, the same workflow publishes the platform archives there.
6. Verify a clean install through Homebrew, Scoop, and the direct install script before announcing.
