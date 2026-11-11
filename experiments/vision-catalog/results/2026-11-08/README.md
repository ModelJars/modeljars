# Pre-merge LLaVA catalog attempt

**Neither candidate is admitted or published.** The immutable
[Models report](https://github.com/integrallis/models/blob/318d0068555b7474c927d65a1eb15482fa47d740/experiments/vision-catalog-qualification/results/2026-11-08/README.md)
records failures in token fidelity and faster-reference decode performance. Both
entries in `catalog/vision-qualifications.json` import those verdicts with
`qualified=false`, `modeljarsRuntimeVerified=false` and
`releasedRuntimeVerified=false`. The importer and evidence gate verify the exact
report bytes and both artifact identities.

## Actual public entry-point result

The ordinary CLI-generated demo sources in this directory ran on the remote Linux
worker against source-built ModelJars JARs from
`920708bd20ab8a4f6379979e0b3c36646f16ee19`, byte-identical measured Models/Vectors
dependencies, and newly generated markers containing the measured rejected evidence.
The only runtime properties selected the native library and a separate empty cache.
No inference settings or admission bypass were supplied.

| Candidate | Demo result | Cache after execution | Admission |
| --- | --- | --- | --- |
| Q4_K_S | `No qualified vision execution` | Empty | Rejected |
| Q4_K_M | `No qualified vision execution` | Empty | Rejected |

The exception originates in the public `ModelJars.openVisionRuntime` qualification
lookup, before installation. Each `consumer-results/*/execution.json` retains the
marker hashes, source runtime revision, harness hash, exit code and cache inventory.
Its `passed=true` verifies the expected rejection; `backendQualified=false` remains
the model verdict. No positive public-API inference smoke is claimed.

The Java sources were executed unchanged using the isolated preview classpath. Their
`//DEPS` lines were not resolved through Central/JBang: the preview uses unreleased API
code. Successful dependency resolution and released-runtime inference remain required
after a future ordered release train.

## Integration defects found and fixed

- Real vision evidence exposed an undefined case count in the detail page. The page
  now derives the count from both datasets; tests exercise passing and rejected raw
  evidence. The original failing Node log is retained as `node-before-render-fix.log.gz`.
- The publication CLI ignored a standalone `--vision-qualifications` option unless
  the RAG manifest was also supplied. It now applies any supplied qualification
  manifests in manual and delta modes. `publication-plan-before-fix.json` preserves
  the erroneous plan, not an executed publication. The corrected
  [publication plan](publication-plan.json) contains zero publications and removals.
- Preview and diagnostic compilation exclude AppleDouble metadata and verify actual
  executable JAR hashes. No measured model JAR was replaced to correct that packaging
  issue.

## Validation

- 402 Node tests pass, including the full raw-catalog detail-page regression.
- 51 focused publication tests pass, including vision-only manual and delta requests.
- `verifyCatalog generateSite` passes: 469 generated markers, 121 qualified public
  model artifacts, and two qualified compositions. Existing admission is unchanged.
- The generated-site render check exercises all 123 served entries with zero failures.
- The remote evidence gate validates both imported records against the immutable report.
- The qualification-generation gate passes against the branch's main base.

Logs and receipts are adjacent. Earlier source-composite JVM Runtime, core, and CLI tests
also passed, including the native encoder/decoder/attention requirement. This is not
a default released-dependency build or a publication qualification. Fix the backend
fidelity and decode failures, rerun the frozen campaign, then run the positive public
API/reset/demo smoke before proceeding toward release.

## Evidence retention and worker cleanup

The full raw archive and compact observations are retained under
`java-ai/audit-artifacts/pr380-vision-catalog-2026-11-08/`. Their exact sizes and
SHA-256 digests are in [archives.json](archives.json). Both downloaded digests match
the worker's originals. The full archive contains 1,455 entries, including numerical
vectors, executable payloads, reference libraries and consumer receipts, without
model weights or credentials. Scores and admission verdicts reproduce again from
the downloaded compact archive.

The worker is terminated, and its temporary AWS key pair, security group and local
private key are deleted. [Cleanup receipt](worker-lifecycle.json) records verification
at 2026-11-08 02:26 UTC, before the control-record deadline.
