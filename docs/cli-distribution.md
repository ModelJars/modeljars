# ModelJars CLI distribution

The `modeljars` CLI is compiled with GraalVM Native Image and requires no Java installation at
runtime. It deliberately contains catalog, provenance, download, verification, and cache-management
code only. In-process inference remains in the Java 25-or-newer `org.modeljars:modeljars` runtime.

## User commands

Run `modeljars` with no arguments to open the interactive prompt. Command history is stored at
`~/.modeljars/cli-history`, tab completes commands and options, and `exit`, `quit`, or Ctrl-D closes
the session. Supplying any arguments executes exactly one command and returns to the calling shell.

```bash
modeljars search [query] [--capability <name>] [--backend <name>] [--sort <field>] [--details]
modeljars show <alias|source|coordinate> [--coordinates] [--details]
modeljars pull <alias|source|coordinate> [--cache <directory>] [--progress <mode>] [--quiet]
modeljars list [--coordinates] [--details]
modeljars snippet <model> [--tool <tool>]
modeljars info
modeljars remove <exact-model>
modeljars cache-dir
modeljars version
```

`search` reads the qualified catalog embedded in the CLI. It searches names, descriptions,
catalog domains/tags, capabilities, and common discovery aliases (`fintech` finds the canonical
`finance` tag). Its default table is a compact one-row summary; `--details` adds complete
capabilities and supported backends beneath each result. `show --details` exposes the same two
fields for one model. `show` prints the exact source page,
download URLs, upstream revision, byte sizes, SHA-256 digests, license, and destination cache path.
`pull` downloads and verifies the complete artifact manifest before atomically placing it in the
shared content-addressed cache. A GGUF marker commonly has one file; a Safetensors marker can require
configuration, tokenizer, and one or more weight files. `list` contains complete installed artifacts;
`pull` and the JVM Runtime verify every declared file again before use. Familiar aliases include
`available`, `ls`, `inspect`, `rm`,
`coordinates`, `coords`, `dependency`, `deps`, `system`, and `env`.

`pull` uses structured byte-level installer events. A capable terminal receives a continuously
updated two-line region for both download and SHA-256 verification, including percentage, bytes,
smoothed transfer speed, and ETA. Retries remain visible above the live region, cached artifacts are
identified explicitly, and the prompt is restored before the command returns. `--progress` accepts
`auto`, `bar`, `plain`, or `off`; automatic mode falls back to stable 25-percent phase lines when
stderr is not an interactive terminal. Progress uses stderr, while JSON, plain, and quiet results
remain clean on stdout. `--quiet` suppresses progress regardless of the global setting.

`snippet` emits copy-ready Maven, Gradle, and Gradle Kotlin DSL declarations by default. Select any
combination of Maven, Gradle, Gradle Kotlin DSL, sbt, Ivy, Leiningen, and JBang by repeating
`--tool`; `coordinates`, `coords`, `dependency`, and `deps` are aliases. Release builds include both
the matching ModelJars runtime and model marker; use `--marker-only` when dependency management
already supplies the runtime. `info` reports host CPU,
physical/logical cores, SIMD, memory, graphics devices, CLI runtime, inference-backend status,
catalog capabilities, and cache usage. The project banner heads the interactive prompt and human
`info` output; JSON and plain modes remain decoration-free. Hardware detection and backend
usability are separate: a
GPU may be detected while GPU model offload remains unsupported, and Apple Foundation Models are
reported as eligible until `backend-apple` performs its runtime availability check.

The default output is a responsive table. Installed-model listings preserve complete model names
and requested dependency coordinates instead of truncating identifiers; `--details` opts into
complete capability and backend fields. Global `--output json` and `--output plain` modes provide
stable automation surfaces. ANSI color is enabled only for
terminals by default, respects
`NO_COLOR`, and can be forced with `--color always` or disabled with `--color never`. `--width`
overrides terminal-width discovery for narrow consoles and snapshots.

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

SDKMAN requires its one-time vendor onboarding process before the workflow can publish. Create the
`modeljars` candidate with `distribution = "PLATFORM_SPECIFIC"` in SDKMAN's database-migrations
repository without adding any versions. Send the project's armored public GPG key as a plain-text
attachment to `info@sdkman.io`; SDKMAN returns an encrypted message containing the vendor API
credentials. Store the decrypted values in the `ModelJars/modeljars` repository as
`SDKMAN_CONSUMER_KEY` and `SDKMAN_CONSUMER_TOKEN`. They are issued by SDKMAN and are not GitHub
tokens or GPG fingerprints.

`.github/workflows/sdkman-publish.yml` can be dispatched independently for an existing GitHub
release, which allows an SDKMAN publication to backfill `v0.1.18` without rebuilding native
binaries or republishing other package channels. It validates that every ZIP has a single
`modeljars-<version>/` root and the expected executable under `bin/`, then submits all five platform
archives with their SHA-256. Only after every platform succeeds does it set the version as the
candidate default, announce it once, and verify that the SDKMAN public API exposes the version.

SDKMAN publication uses the `sdkman` GitHub environment. Repository administrators may add
required reviewers to that environment when they want a manual approval immediately before the
external publication.

## Release sequence

1. Merge a fully verified version change to `main`.
2. Publish the signed Maven Central bundle.
3. Create and publish `v<version>` on the same commit.
4. The CLI workflow builds and attaches native assets, publishes the GitHub Maven package, and
   updates Homebrew and Scoop.
5. After SDKMAN approval, dispatch the SDKMAN workflow; it publishes every platform, sets the stable
   default, announces it, and verifies the public candidate listing.
6. Verify a clean install through Homebrew, Scoop, and the direct install script before announcing.

To backfill an existing release after the two SDKMAN secrets have been configured:

```bash
gh workflow run sdkman-publish.yml --repo ModelJars/modeljars -f tag=v0.1.18
```

Both `make_default` and `announce` default to `true` for a stable backfill. Set `announce=false`
when retrying a release that was already broadcast, so an idempotent platform retry does not create
a duplicate announcement. The main CLI workflow also has a `publish_sdkman` dispatch input for a
deliberate combined retry; it defaults to `false`. Automatic GitHub releases never contact SDKMAN.
