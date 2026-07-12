# ModelJars

ModelJars is a community-owned marker-JAR convention for local and remote model artifacts.
It borrows the useful WebJars idea of using normal JVM dependency coordinates and classpath
metadata, but it does not put large model weights in ordinary JARs.

The marker JAR carries descriptors only:

```text
META-INF/modeljars/registry.properties
```

Those descriptors point to upstream model locations, expected local cache paths, checksums,
licenses, formats, quantization variants, and backend compatibility.

The catalog has one source of truth: `catalog/models.json`. Gradle generates the aggregate
classpath catalog, one publishable marker JAR per entry, Maven publications, and the website search
catalog. Adding a model does not require a new Gradle module or source folder.

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
```

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

## Catalog development

```bash
./gradlew test verifyCatalog
./gradlew generateSite
```

The generated site is written to `build/site`. Individual marker JARs are written under
`modeljars-catalog/build/libs/markers`.

## Reference repos

The WebJars repositories used as design references are cloned under `../../references`:

- `webjars/webjars`
- `webjars/webjars-locator-core`
- `webjars/webjars-locator-lite`

The first implementation follows the locator-lite approach: no startup classpath scan, just
well-known metadata resources. A richer scanner and public catalog service can come later.

## Reports

- [ModelJars.org operations and local model candidates](docs/modeljars-operations-and-model-candidates.md)
- [25-model launch catalog and vertical-model qualification](docs/launch-catalog-25.md)
