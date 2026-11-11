# Public LLaVA API and CLI preview on Models 0.5.19

Both Q4_K_S and Q4_K_M passed the unchanged public API and CLI preview protocol
on 2026-11-11, 01:13:05–01:20:44 UTC, after all backend measurements completed.
Each candidate installed and SHA-256-verified its two HTTPS artifacts into a new
cache, answered the first frozen VQAv2 question and first TextVQA question, then
repeated the first question to exercise reset. All answers exactly matched the
measured backend outputs. Each unchanged CLI-generated demo passed separately.

The SDK and CLI were built from ModelJars commit
`36ad63c25c45a8c57c7067d8582a17322d7fdcdd`. Models 0.5.19 and Vectors 0.1.34
runtime JARs match independently fetched Maven Central bytes; see
`central-runtime-receipt.json` and `receipt.json`. SDK and CLI classpaths were
staged separately. The payload archive SHA-256 is
`4859b67faa25547399cf26dc61c8311d0344c3bb9aa723104a4682c2c4e5cf1e`.

This is a source SDK preview, not a released SDK or marker-resolution proof.
The explicit native path selects the exact library extracted from the published
backend-native JAR and used in the completed backend admission. All inference
settings use public defaults; `modeljars.cache` selects the fresh installation.
The consumer reports retain the actual native diagnostics and loaded JAR digests.
The generated sources here still name the source build's SDK version and were
executed using the recorded preview classpath, not by resolving those declarations.

The backend attempt is the unchanged eight-gate pass committed in Models
`4ef8e5b56e92689ef4b9424d928a39627b9713a6` and merged through Models #420.
The markers retain its `models-source:214d53f928923d21514cf2d8bca6e02712d4dd51`
evidence label until the separate released SDK proof passes.

`consumer-results/*/consumer.json` establishes the public API checks and
`execution.json` establishes each generated demo check. Model weights, executable
JARs, and caches are excluded from Git; the payload receipt records their hashes.
Catalog publication remains closed until the released SDK, published markers, and
ordinary generated JBang demos are independently verified.
