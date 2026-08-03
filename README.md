<p align="center">
  <img src="media/icons/android-chrome-512x512.png" alt="ModelJars logo" width="200">
</p>

# ModelJars

ModelJars is a community-owned marker-JAR convention for local and remote model artifacts.
It borrows the useful WebJars idea of using normal JVM dependency coordinates and classpath
metadata. Large model weights remain external; compact, license-compatible model payloads can be
bundled when doing so makes the artifact directly usable.

Every marker JAR carries machine-readable runtime and catalog metadata:

```text
META-INF/modeljars/registry.properties
META-INF/modeljars/model.json
META-INF/modeljars/performance-v1.properties
META-INF/modeljars/performance-v1.json
META-INF/modeljars/qualifications-v1.properties
META-INF/modeljars/qualifications-v1.json
```

Descriptors point to upstream model locations or bundled resources, checksums, licenses, formats,
quantization variants, runtime feature flags, and backend compatibility. A bundled payload lives
below `META-INF/modeljars/models/<catalog-id>/` and is verified against the same size and SHA-256
metadata as an external model.

Model identity has one source of truth, `catalog/models.json`; controlled performance evidence has
the independent versioned source `catalog/performance-profiles.json`. Gradle generates the aggregate
classpath candidate catalog and one marker build per entry. The aggregate JAR embeds
`META-INF/modeljars/catalog.json`, so adding a candidate does not require a new Gradle module or
source folder.

The public site and publication plan use `catalog/qualifications.json` as a separate release
boundary. They include only the qualified subset whose artifact SHA-256 and byte size exactly match
the candidate catalog. Recording candidate metadata does not create a public model page or authorize
publication.

## Install the CLI

The `modeljars` CLI is a self-contained GraalVM native executable; using it does not require a JDK.
It follows the familiar local-model workflow used by tools such as Ollama:

```bash
# macOS or Linux
brew install integrallis/tap/modeljars

# macOS or Linux without Homebrew
curl -fsSL https://raw.githubusercontent.com/ModelJars/modeljars/main/install.sh | sh

# Windows
scoop bucket add integrallis https://github.com/integrallis/scoop-bucket
scoop install modeljars
```

Search the qualified catalog, inspect immutable provenance, prefetch weights, and see what is
already cached:

```bash
modeljars search gemma
modeljars show ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m
modeljars pull ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m
modeljars list
```

`pull` uses the same content-addressed cache as the JVM Runtime. It downloads from the descriptor's
exact immutable URL, verifies byte size and SHA-256, and never starts inference. Release automation
builds native binaries for macOS, Linux, and Windows on their host architectures and publishes an
executable fallback JAR to Maven Central and GitHub Packages. SDKMAN multi-platform archives are
also generated; publication begins once the `modeljars` candidate completes SDKMAN vendor
onboarding.

## Dependency

Applications use the stable JVM Runtime artifact and add one model dependency for every model they
intend to ship:

```kotlin
dependencies {
    implementation("org.modeljars:modeljars:0.1.3")
    implementation(
        "org.modeljars.huggingface:" +
            "ggml-org.qwen3-0.6b-gguf.q4_0:" +
            "3.0.0-q4_0.1",
    )
}
```

```xml
<dependency>
  <groupId>org.modeljars</groupId>
  <artifactId>modeljars</artifactId>
  <version>0.1.3</version>
</dependency>
<dependency>
  <groupId>org.modeljars.huggingface</groupId>
  <artifactId>ggml-org.qwen3-0.6b-gguf.q4_0</artifactId>
  <version>3.0.0-q4_0.1</version>
</dependency>
```

`modeljars` exposes `modeljars-core`, Models 0.2.3, and both Models execution backends. Each marker
JAR contributes its own descriptor, qualification evidence, performance profiles, and generated
Java reference. Applications using the JVM Runtime require Java 25 or newer. `modeljars-core` and
the fallback CLI JAR remain usable by Java 21 registry and build tooling without the Models runtime.

Marker dependencies are build-time model-version declarations and contain no transitive runtime
dependencies. Add each selected marker in compile scope so its generated reference is available to
application source.

## Example markers

The production-qualified subset is searchable at [modeljars.org](https://modeljars.org). A compact
Qwen marker is:

```text
org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1
```

It resolves the upstream source:

```text
hf://ggml-org/Qwen3-0.6B-GGUF
```

For this launch catalog, external weights are downloaded directly from an exact, commit-pinned
Hugging Face revision. The marker JAR contains metadata rather than the large weights, including the
source page, immutable download URL, revision, byte count, and SHA-256. The JVM Runtime and CLI store
verified weights in a content-addressed cache below `${user.home}/.modeljars/cache/sha256/`.
Application code never constructs or passes that path. Run `modeljars show <model>` to inspect all
of that provenance before downloading.

Qwen2.5-Coder markers include:

```text
org.modeljars.huggingface:qwen.qwen2.5-coder-0.5b-instruct-gguf.q4_0:2.5.0-q4_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-0.5b-instruct-gguf.q8_0:2.5.0-q8_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-1.5b-instruct-gguf.q4_0:2.5.0-q4_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-1.5b-instruct-gguf.q8_0:2.5.0-q8_0.1
```

The catalog also includes the bundled 40,000-term WordTour semantic-order artifact:

```text
org.modeljars.github:joisino.wordtour-glove-6b-300d.optimal:1.0.0-optimal.1
```

## Runtime use

```java
import static org.modeljars.catalog.Qwen3_0_6b_Q4_0.MODEL;

import com.integrallis.models.api.ModelPrompt;

var options = SamplingOptions.builder()
    .temperature(0).maxTokens(128).build();

try (var runtime = ModelJars.openRuntime(MODEL)) {
    ModelPrompt prompt = runtime.chatTemplate().render(
        List.of(ChatMessage.user("Name one JVM language.")));
    String answer = runtime.model().generate(prompt, options);
}
```

`ChatTemplate.render(...)` returns `com.integrallis.models.api.ModelPrompt`. `ModelPrompt` preserves
the distinction between template control tokens and user text; it is intentionally not a `String`.

`ModelJars.openRuntime` resolves the exact qualified descriptor, selects its qualified Models backend
and chat template,
downloads missing weights, verifies their size and SHA-256 digest, and applies every non-conflicting
artifact-bound performance profile that matches the current runtime. Profiles with Java launch
requirements apply only when every required JVM argument is active; omitted profiles and missing
arguments remain visible in backend diagnostics. The runtime owns the backend and closes it at the
end of the `try` block. The older `ModelJars.open` model-only API remains available when the caller
already owns prompt selection.

Local inference must start Java 25 or newer with the Vector module resolved:

```text
--add-modules=jdk.incubator.vector
```

ModelJars checks this before resolving or downloading model weights. When automatic selection picks
the Rust/FFM backend, it also checks for `--enable-native-access=ALL-UNNAMED` before downloading and
explains how to select a qualified Java backend when available.

If an error says class-file version `69.0` but the runtime only recognizes up to `61.0`, the model
library was compiled for Java 25 (`69`) but the process actually launched with Java 17 (`61`). Java
26 can run Java 25 bytecode; the usual cause is Maven, Gradle, or an IDE using a different JDK than
the shell. Check every launcher involved:

```bash
java -version
mvn -v
./gradlew --version
```

For Maven's in-process `exec:java`, pass the module to the Maven JVM:

```bash
MAVEN_OPTS="--add-modules=jdk.incubator.vector" mvn exec:java
```

For a packaged application, place the option before `-jar`:

```bash
java --add-modules=jdk.incubator.vector -jar application.jar
```

## RAG framework dependencies

ModelJars does not force a LangChain4j or Spring AI version on applications. Add the Models adapter,
the framework-neutral grounding module, and the chosen framework explicitly. For LangChain4j:

```kotlin
implementation("com.integrallis:models-rag:0.2.3")
implementation("com.integrallis:models-langchain4j:0.2.3")
implementation("dev.langchain4j:langchain4j:1.17.2")
```

For Spring AI:

```kotlin
implementation("com.integrallis:models-rag:0.2.3")
implementation("com.integrallis:models-spring-ai:0.2.3")
implementation("org.springframework.ai:spring-ai-client-chat:2.0.0")
implementation("org.springframework.ai:spring-ai-rag:2.0.0")
```

Use `GroundedRagPrompt.prepare(...)` to screen retrieved evidence and construct the canonical prompt.
Place its `instructions()` in the framework system message and its `request()` in the user message,
then render both with `runtime.chatTemplate()`.

Offline loading and explicit backend selection are available without exposing the cache path:

```java
var options = ModelLoadOptions.builder()
    .offline(true)
    .backend(ModelBackend.JAVA)
    .build();

try (var model = ModelJars.open(MODEL, options)) {
    // The verified artifact must already be in the ModelJars cache.
}
```

Configuration-driven applications can select the same classpath marker by its complete coordinate:

```java
try (var model = ModelJars.open(
    "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1")) {
    // Generate text through the qualified backend.
}
```

## Catalog metadata

Registry APIs remain available when an application needs model metadata without loading inference:

```java
ModelJarDescriptor descriptor =
    ModelJarRegistry.fromClasspath().resolve(MODEL).orElseThrow();

Set<String> requiredFeatures = descriptor.features();
ModelDimensions dimensions = descriptor.dimensions();
Optional<ModelMemoryEstimate> baseline =
    descriptor.estimateMemory(4096, KvCachePrecision.FLOAT16);
```

Descriptors also expose display name, description, domains, upstream and download links, license
link, exact artifact byte size, parameter count, context length, embedding width, total and
attention block counts, attention/KV heads, feed-forward width, and MoE dimensions when present.
Memory estimates are deliberately limited to model-file bytes plus the requested KV cache. Backend
workspace, tensor repacking, allocator overhead, the JVM, and the operating system are excluded.

Feature flags expose requirements and handling metadata such as `q4-k`, `chatml`,
`community-conversion`, and `medical-use-warning`. Markers created before the feature property was
introduced remain loadable and return an empty set.

Versioned performance profiles are discovered separately and bind every recommendation to the
exact marker coordinate, model SHA-256, backend, runtime selector, and reproducible before/after
evidence:

```java
ModelPerformanceProfileRegistry profiles =
    ModelPerformanceProfileRegistry.fromClasspath();

List<ModelPerformanceProfile> measured = profiles.profilesFor(descriptor);
```

The ModelJars JVM Runtime calls `matching(descriptor, backend, runtimeFacts)` with the complete
structured runtime fingerprint before loading the selected Models backend. It combines independent
recommendations with different keys, rejects conflicting overlapping profiles at registry load,
and verifies typed Java launch requirements against the active JVM input arguments.

`safeForAutomaticSelection()` means the profile has recommendations and exact output hashes
matched in its comparison. It does not authorize arbitrary runtime properties or native code;
backends must whitelist supported recommendation keys and retain their own correctness checks. See
[Performance profiles](docs/performance-profiles.md) for the schema and contribution rules.

The lower-level installer remains available to registry tooling that needs the verified file:

```java
ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
Path artifact = new ModelJarInstaller(registry).install(MODEL);
```

`ModelJarInstaller` verifies both the byte size and SHA-256 digest before atomically moving the
download into place. Most applications should use `ModelJars.open`.

Compact bundled payloads use the same verification contract without an installation step:

```java
ModelJarDescriptor descriptor =
    registry.resolve(
        ModelJar.of("github://joisino/wordtour")
            .variant("optimal")
            .backend("semantic-order"))
        .orElseThrow();

byte[] payload = new ModelJarResourceLoader(
    Thread.currentThread().getContextClassLoader()
).readVerified(descriptor);
```

## Qualification

A public ModelJar represents one exact model artifact, not a claim about every conversion or
quantization of the upstream model. Qualification pins the artifact and Models revision, runs
parser/tokenizer/generation tests, executes a controlled RAG workload, and checks absolute quality
and latency plus same-host performance against Ollama. llama.cpp is retained as a second independent
comparator; neither comparator is a runtime dependency.

The [qualification and submission guide](https://modeljars.org/contribute/) lists host
prerequisites, the harness command, acceptance thresholds, evidence files, and pull request steps.
“Not yet qualified” means the controlled run has not occurred; it is not a failed result.

## Catalog development

```bash
./gradlew test verifyCatalog verifyRemoteCatalogMetadata
./gradlew generateSite
npm ci
npm test
npm run catalog:enrich
```

The generated GitHub Pages site is written to `build/site`. Individual marker JARs are written
under `modeljars-catalog/build/libs/markers`. Classpath payloads are fetched from their pinned
source revision during the build and must pass size, digest, format, vocabulary, and uniqueness
checks.

`npm run catalog:enrich -- --write` uses Hugging Face's official range-aware GGUF parser to update
dimensions from each exact revision-pinned artifact without downloading its tensors. The same
command without `--write` is the CI verification mode.

## Reference repos

The WebJars repositories used as design references are cloned under `../../references`:

- `webjars/webjars`
- `webjars/webjars-locator-core`
- `webjars/webjars-locator-lite`

The first implementation follows the locator-lite approach: no startup classpath scan, just
well-known metadata resources. A richer scanner and public catalog service can come later.

## Reports

- [ModelJars.org operations and local model candidates](docs/modeljars-operations-and-model-candidates.md)
- [100+ model launch catalog and metadata contract](docs/launch-catalog-100.md)
- [Performance profile schema and safety contract](docs/performance-profiles.md)
- [Native CLI distribution and release channels](docs/cli-distribution.md)
