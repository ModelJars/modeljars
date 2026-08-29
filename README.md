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
META-INF/modeljars/tool-qualifications-v1.properties
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

Search or filter the qualified catalog, inspect immutable provenance, prefetch weights, see what is
cached, and check what the current machine can actually run:

```bash
# Start an interactive session
modeljars

# Or execute any command once
modeljars search gemma
modeljars models embedding --sort size
modeljars search --capability embedding --sort size
modeljars search fintech
modeljars search gemma --details
modeljars alias list
modeljars show gemma-26b
modeljars pull qwen-0.6b
modeljars demo qwen-0.6b "What is the capital of France? Reply with only the city name."
modeljars demo embeddinggemma "Public transit schedule"
modeljars ls
modeljars info
modeljars snippet ggml_org_gemma_4_26b_a4b_it_gguf_q4_k_m --tool maven
```

`pull` uses the same content-addressed cache as the JVM Runtime. It downloads every file declared by
the descriptor from its exact immutable revision, verifies each byte size and SHA-256, and never
starts inference. Single-file GGUF and CACT artifacts and multi-file Safetensors bundles use the
same command.
Release automation
builds native binaries for macOS, Linux, and Windows on their host architectures and publishes an
executable fallback JAR to Maven Central and GitHub Packages. SDKMAN distribution remains disabled
until its vendor onboarding approval is complete.

On a terminal, `pull` renders one animated download and verification region with byte progress,
smoothed transfer speed, and ETA, then replaces it with the verified path and coordinate. Redirected
and dumb terminals receive stable phase updates without control characters. Use
`--progress auto|bar|plain|off` to override detection; `--quiet` remains path-only.

Interactive output uses responsive, aligned tables and color only when stdout is a terminal.
Catalog and cache listings are compact by default; add `--details` to show complete capability and
backend continuation fields without truncation. `list --coordinates` independently adds the exact
marker coordinate. Search includes catalog domains/tags and common discovery aliases, such as
`fintech` for `finance`. Entries published during the previous 48 hours carry a visible `NEW`
marker; plain and JSON output expose the same state without relying on color.
Every discovery command also supports `--output json` for automation and `--output plain` for stable
line-oriented output; `NO_COLOR` and `--color never` disable ANSI output. `modeljars info` reports
CPU, physical and logical cores, SIMD, memory, graphics hardware, Java/native runtime, backend
eligibility, catalog capability counts, and local cache usage. A detected GPU is reported separately
from a usable inference backend so hardware inventory is never mistaken for supported acceleration.
Run `modeljars help` or `modeljars <command> --help` for the complete command surface. Use
`modeljars snippet <model> --tool maven` or `--tool gradle-kotlin` for a copy-ready dependency;
`coordinates`, `coords`, `dependency`, and `deps` are aliases for the same command. With no
arguments, `modeljars` opens a prompt with history and tab completion; `exit`, `quit`, or Ctrl-D
returns to the shell. Supplying any command keeps the normal one-shot behavior used by scripts.

The CLI generates a short name for every model from the active catalog. It starts with the model
family and adds only the semantic details needed to remain unambiguous, such as `qwen-0.6b`,
`qwen-coder-0.5b-q4`, or `qwen-embedding`. The names are recomputed whenever a refreshed catalog is
loaded, so newly published models receive names without a CLI release or local configuration. A
name can become more specific when a new model would otherwise collide; the full catalog ID and
marker coordinate remain stable selectors. `modeljars alias list` shows the complete mapping.

Tab completion includes generated short names, full catalog IDs, and optional user-defined aliases
for every model-taking command. Create a persistent custom alias with
`modeljars alias set <name> <model>` and remove it with `modeljars alias rm <name>`. A custom alias
cannot replace a generated short name or qualified catalog ID.

`modeljars demo <model> [input]` writes a small, editable Java 25 JBang program for the model's
qualified capability. Embedding demos print the input, full vector, dimensions, and load/execution
times. Chat demos render the qualified template and report runtime-owned tokenization, prompt
preparation, prefill, TTFT, decode, and token-count measurements. Tool-calling models generate a
complete in-memory smart-home example that declares tools, constrains decoding, parses calls, and
executes them. Run the printed `jbang <file>` command; inference then follows the same public
ModelJars and Models APIs used by an application, with no external model server. `script`, `run`,
`chat`, `embed`, and `embedding` are aliases for `demo`.

Before catalog-backed commands run, the CLI compares the SHA-256 published at
`https://modeljars.org/catalog/registry.properties.sha256` with its local qualified catalog. A
different hash triggers a download of `registry.properties`; the CLI verifies and parses the file,
then atomically promotes it to `${user.home}/.modeljars/catalog/registry.properties`. That verified
download replaces the catalog bundled with the executable. If ModelJARs.org cannot be reached, the
CLI uses the last verified download, or the bundled catalog before the first successful refresh.
Set `MODELJARS_CATALOG_OFFLINE=true` to suppress network refreshes explicitly.

## Dependency

Applications use the stable JVM Runtime artifact and add one model dependency for every model they
intend to ship:

```kotlin
val modeljarsVersion = providers.gradleProperty("modeljarsVersion").get()

dependencies {
    implementation("org.modeljars:modeljars:$modeljarsVersion")
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
  <version>${modeljars.version}</version>
</dependency>
<dependency>
  <groupId>org.modeljars.huggingface</groupId>
  <artifactId>ggml-org.qwen3-0.6b-gguf.q4_0</artifactId>
  <version>3.0.0-q4_0.1</version>
</dependency>
```

`modeljars` exposes `modeljars-core`, Models, and both Models execution backends. Each marker
JAR contributes its own descriptor, qualification evidence, performance profiles, and generated
Java reference. The runtime also carries the current qualification decisions so corrected evidence
or a revocation supersedes older marker metadata on the classpath. It does not add the aggregate
model catalog. Applications using the JVM Runtime require Java 25 or newer. `modeljars-core` remains
usable by Java 21 registry and build tooling; the executable CLI JAR is compiled for Java 25. The
native CLI contains catalog, download, verification, and demo-generation code, but not a second
copy of the Models inference runtime.

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
source page, immutable file URLs, revision, byte counts, and SHA-256 digests. The JVM Runtime and CLI
store verified files together in a content-addressed cache below
`${user.home}/.modeljars/cache/sha256/`.
Application code never constructs or passes that path. Run `modeljars show <model>` to inspect all
of that provenance before downloading.

The first qualified Safetensors bundle is available as:

```text
org.modeljars.huggingface:qwen.qwen2.5-0.5b-instruct.bf16:2.5.0-bf16.1
```

Its marker pins `config.json`, `model.safetensors`, `tokenizer.json`, and
`tokenizer_config.json`. `modeljars pull qwen_qwen2_5_0_5b_instruct_bf16`
installs and verifies the complete directory required by Models.

The first qualified CACT artifact is Needle 2, a compact tool-calling model:

```text
org.modeljars.huggingface:cactus-compute.needle2-cact.cq2_mixed:2.0.0-cq2_mixed.1
```

`modeljars pull needle` installs the pinned
`needle2.cact` bytes. Models parses the embedded tokenizer and mixed CQ2/CQ4 graph and executes
generation, constrained tool syntax, retrieval, and auxiliary heads in Java.

```java
import static org.modeljars.catalog.Cactus_Compute_Needle2_Cact_Cq2_Mixed.MODEL;

var weather = new ToolSpec(
    "get_weather", "Get weather for a city.",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
        + "\"required\":[\"city\"]}");
var options = SamplingOptions.builder().temperature(0).maxTokens(128).build();

try (var runtime = ModelJars.openRuntime(MODEL)) {
    var tools = List.of(weather);
    var prompt = runtime.chatTemplate().render(
        List.of(ChatMessage.user("weather in Lagos")), tools);
    var constraint = ToolCallTokenConstraints.compile(
        runtime.tokenizer(), runtime.chatTemplate().toolSyntax(), tools,
        ignored -> List.of()).orElseThrow();
    var output = runtime.pipeline().generate(prompt, options, constraint);
    var calls = ToolCallScanner.scan(
        output, runtime.chatTemplate().toolSyntax()).toolCalls();
    var evidence = runtime.toolQualification().orElseThrow();
}
```

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

import com.integrallis.models.api.InferenceContextWindow;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.InferencePipeline;

var options = SamplingOptions.builder()
    .temperature(0).maxTokens(128).build();

try (var runtime = ModelJars.openRuntime(MODEL)) {
    InferencePipeline pipeline = runtime.pipeline();
    Tokenizer tokenizer = runtime.tokenizer();
    InferenceContextWindow context = runtime.contextWindow();
    ModelPrompt prompt = runtime.chatTemplate().render(
        List.of(ChatMessage.user("Name one JVM language.")));
    String answer = pipeline.generate(prompt, options);
}
```

`ChatTemplate.render(...)` returns `com.integrallis.models.api.ModelPrompt`. `ModelPrompt` preserves
the distinction between template control tokens and user text; it is intentionally not a `String`.
`runtime.pipeline()` exposes the same owning model through the complete Models inference API,
including structured tokenization, metadata, active context capacity and position, prefill,
forward-pass logits, reset, checkpoint, and rewind. `runtime.model()` remains the high-level
`TextGenerationModel` view and delegates structured prompts to that pipeline.

`ModelJars.openRuntime` resolves the exact qualified descriptor, selects its qualified Models backend
and chat template,
downloads missing weights, verifies their size and SHA-256 digest, and applies every non-conflicting
artifact-bound performance profile that matches the current runtime. Profiles with Java launch
requirements apply only when every required JVM argument is active; omitted profiles and missing
arguments remain visible in backend diagnostics. The runtime owns the backend and closes it at the
end of the `try` block. The older `ModelJars.open` model-only API remains available when the caller
already owns prompt selection.

Production inference always runs in process through Models. ModelJars never delegates generation or
embedding to Ollama, llama.cpp, Python, Needle, or a hosted service. Those systems may be used as
isolated correctness or performance comparators during qualification; the optional Models
Rust/FFM backend contains project-owned math kernels while Java retains the model graph.

Qualified embedding markers use the same path-free loading contract. Pooling, normalization,
vector width, artifact digest, and backend come from the marker's equivalence evidence rather than
application configuration:

```java
import static org.modeljars.catalog.Ggml_Org_Embeddinggemma_300m_Gguf_Q8_0.MODEL;

try (var embeddings = ModelJars.openEmbedding(MODEL)) {
    float[] vector = embeddings.embed("Where is the maintenance schedule?");
}
```

Use `ModelJars.openEmbeddingRuntime(MODEL)` when the application also needs the exact descriptor
and `ModelEmbeddingQualificationRegistry.Entry` selected for the loaded model. Applications never
construct a cache path or choose pooling themselves.

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
val modelsVersion = providers.gradleProperty("modelsVersion").get()

implementation("com.integrallis:models-rag:$modelsVersion")
implementation("com.integrallis:models-langchain4j:$modelsVersion")
implementation("dev.langchain4j:langchain4j:1.17.2")
```

For Spring AI:

```kotlin
val modelsVersion = providers.gradleProperty("modelsVersion").get()

implementation("com.integrallis:models-rag:$modelsVersion")
implementation("com.integrallis:models-spring-ai:$modelsVersion")
implementation("org.springframework.ai:spring-ai-client-chat:2.0.0")
implementation("org.springframework.ai:spring-ai-rag:2.0.0")
```

When a catalog artifact is qualified for tool calling, pass its descriptor capabilities to the
adapter. Spring AI's `ChatClient` then executes registered Java callbacks and returns the model's
follow-up answer on both blocking and streaming paths. An artifact qualified only for chat fails
clearly before generation instead of silently attempting a tool workflow:

```java
try (var runtime = ModelJars.openRuntime(MODEL)) {
    var model = new ModelsSpringAiChatModel(
        runtime.model(),
        runtime.descriptor().alias(),
        runtime.chatTemplate(),
        SamplingOptions.builder().build(),
        runtime.descriptor().capabilities());

    String answer = ChatClient.create(model)
        .prompt()
        .user("What is the weather in Austin?")
        .tools(new WeatherTools())
        .call()
        .content();
}
```

The current Apple Foundation Models bridge does not expose Apple's guided-generation or tool API,
so registering framework tools with that backend is not yet supported.

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
parser/tokenizer/generation tests, then executes the complete controlled RAG workload using the
selected Models backend and library-default properties. Every default generation must succeed with
perfect deterministic correctness before any tuning is applied. A separate performance phase checks
absolute quality and latency plus same-host performance against Ollama. llama.cpp is retained as a
second independent comparator; neither comparator is a runtime dependency.

Embedding artifacts use a separate policy: we test that the model produces the same vectors as
llama.cpp, for a pinned probe set over the same bytes. Agreement is gated at 0.999 cosine, where a
correct run measures 0.99950 and wrong pooling measures 0.66156. Cosine is scale-invariant, so
vector length is gated separately at 1e-3. Evidence is recorded as `ModelEmbeddingQualification`,
whose tier is `SEMANTIC_SEARCH` or `UNQUALIFIED`.

The [qualification and submission guide](https://modeljars.org/contribute/) lists candidate
submission, maintainer host prerequisites, harness commands, acceptance thresholds, evidence files,
and publication steps.
“Not yet qualified” means the controlled run has not occurred; it is not a failed result.

The native CLI prepares a new candidate from a public Hugging Face repository in one command:

```bash
modeljars contribute Qwen/Qwen2.5-0.5B-Instruct --domain general
```

It pins the immutable revision, selects the required GGUF, CACT, or Safetensors files, verifies their
sizes and SHA-256 digests, writes the candidate report, and prints the single `gh issue create`
command needed to submit it. Compatibility, qualification, and publication remain reviewed
maintainer gates.

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
- [Advanced inference pipeline access](docs/inference-pipeline.md)
