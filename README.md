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

## First supported marker

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

## Reference repos

The WebJars repositories used as design references are cloned under `../../references`:

- `webjars/webjars`
- `webjars/webjars-locator-core`
- `webjars/webjars-locator-lite`

The first implementation follows the locator-lite approach: no startup classpath scan, just
well-known metadata resources. A richer scanner and public catalog service can come later.

