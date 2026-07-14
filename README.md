# ModelJars

ModelJars is a community-owned marker-JAR convention for local and remote model artifacts.
It borrows the useful WebJars idea of using normal JVM dependency coordinates and classpath
metadata. Large model weights remain external; compact, license-compatible model payloads can be
bundled when doing so makes the artifact directly usable.

Every marker JAR carries machine-readable runtime and catalog metadata:

```text
META-INF/modeljars/registry.properties
META-INF/modeljars/model.json
```

Descriptors point to upstream model locations, expected local cache paths or bundled resources,
checksums, licenses, formats, quantization variants, runtime feature flags, and backend
compatibility. A bundled payload lives below `META-INF/modeljars/models/<catalog-id>/` and is
verified against the same size and SHA-256 metadata as an external model.

The catalog has one source of truth: `catalog/models.json`. Gradle generates the aggregate
classpath catalog, one publishable marker JAR per entry, Maven publications, and the website search
catalog. The aggregate JAR embeds `META-INF/modeljars/catalog.json`; the static website extracts
that resource instead of maintaining a model list in JavaScript. Adding a model does not require a
new Gradle module or source folder.

## Dependency

Applications use the stable facade artifact and may add explicit marker dependencies to record the
models they intend to ship:

```kotlin
dependencies {
    implementation("org.modeljars:modeljars:0.1.0")
    runtimeOnly(
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
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.modeljars.huggingface</groupId>
  <artifactId>ggml-org.qwen3-0.6b-gguf.q4_0</artifactId>
  <version>3.0.0-q4_0.1</version>
  <scope>runtime</scope>
</dependency>
```

`modeljars` exposes `modeljars-core` and the aggregate `modeljars-catalog` at runtime. This makes the
complete catalog discoverable with one ergonomic dependency. Explicit marker dependencies remain
useful as build-time model-version declarations; duplicate descriptors from the aggregate and an
individual marker are deduplicated by marker coordinate.

## Supported markers

The first catalog marker is the model already used by `projects/models` integration tests:

```text
org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1
```

It resolves the upstream source:

```text
hf://ggml-org/Qwen3-0.6B-GGUF
```

and the local path:

```text
${user.home}/.jvllm/models/Qwen3-0.6B-Q4_0.gguf
```

The first pure-Java coder targets are Qwen2.5-Coder small GGUF variants:

```text
org.modeljars.huggingface:qwen.qwen2.5-coder-0.5b-instruct-gguf.q4_0:2.5.0-q4_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-0.5b-instruct-gguf.q8_0:2.5.0-q8_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-1.5b-instruct-gguf.q4_0:2.5.0-q4_0.1
org.modeljars.huggingface:qwen.qwen2.5-coder-1.5b-instruct-gguf.q8_0:2.5.0-q8_0.1
```

The first bundled semantic-order model is the proven-optimal 40,000-term WordTour artifact:

```text
org.modeljars.github:joisino.wordtour-glove-6b-300d.optimal:1.0.0-optimal.1
```

## Runtime use

```java
ModelJarRegistry registry = ModelJarRegistry.fromClasspath();

ModelJarDescriptor descriptor = registry.resolve(
    ModelJarRequirement.forSource("hf://ggml-org/Qwen3-0.6B-GGUF")
        .variant("q4_0")
        .backend("pure-java")
        .capability("text-generation")
        .build()
).orElseThrow();

Path model = descriptor.localPath().orElseThrow();
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

To download and verify the pinned artifact instead of requiring it to exist already:

```java
Path model = new ModelJarInstaller(registry).install(
    ModelJarRequirement.forSource("hf://ggml-org/Qwen3-0.6B-GGUF")
        .variant("q4_0")
        .backend("pure-java")
        .build()
);
```

`ModelJarInstaller` verifies both the byte size and SHA-256 digest before atomically moving the
download into the local cache.

Compact bundled payloads use the same verification contract without an installation step:

```java
ModelJarDescriptor descriptor = registry.resolve(
    ModelJarRequirement.forSource("github://joisino/wordtour")
        .variant("optimal")
        .backend("semantic-order")
        .build()
).orElseThrow();

byte[] payload = new ModelJarResourceLoader(
    Thread.currentThread().getContextClassLoader()
).readVerified(descriptor);
```

## Catalog development

```bash
./gradlew test verifyCatalog verifyRemoteCatalogMetadata
./gradlew generateSite
npm ci
npm test
npm run catalog:enrich
```

The generated site is written to `build/site`. Individual marker JARs are written under
`modeljars-catalog/build/libs/markers`. Classpath payloads are fetched from their pinned source
revision during the build and must pass size, digest, format, vocabulary, and uniqueness checks.

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
