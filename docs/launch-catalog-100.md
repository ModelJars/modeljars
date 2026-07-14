# ModelJars 100+ Model Launch Catalog

Status: July 2026 implementation baseline

## Current catalog

The metadata-driven catalog currently contains:

- 112 marker artifacts;
- 111 pinned GGUF files;
- 109 distinct Hugging Face model repositories;
- 24 GGUF architecture identifiers;
- 19 artifacts from 17 sources with a verified `pure-java` backend claim; and
- one bundled WordTour semantic-order model.

Distinct models are counted by upstream `sourceId`. Multiple quantizations are separate artifacts,
not additional models. Catalog availability and runtime acceptance are separate claims: most of the
expanded catalog currently advertises `llama.cpp=true` and `pure-java=false` until the exact artifact
passes the models-library oracle tests.

## One source of truth

`catalog/models.json` is the only hand-reviewed catalog. There is no model-per-folder structure and
no model list in the website code. The build generates:

1. one self-describing marker JAR per catalog entry;
2. `modeljars-catalog.jar` with the aggregate registry and `catalog.json`;
3. the static Cloudflare/GitHub Pages `catalog.json` extracted from that aggregate JAR; and
4. Maven publications for the facade, core, aggregate catalog, and markers.

An individual marker embeds both `META-INF/modeljars/registry.properties` for the dependency-free
Java loader and `META-INF/modeljars/model.json` for richer tooling. The aggregate JAR embeds the same
descriptors as `META-INF/modeljars/catalog.json`.

Applications can enumerate the launch catalog directly:

```java
List<ModelJarDescriptor> models =
    ModelJarRegistry.fromClasspath().descriptors();
```

The `org.modeljars:modeljars` facade brings both the API and aggregate catalog at runtime. Explicit
marker dependencies can still serve as model-version declarations; exact duplicates are removed
when the classpath registry loads them.

## Metadata contract

Every GGUF marker must include:

- immutable source revision, exact download path, byte size, and SHA-256;
- source, download, and license links;
- model version, format, architecture, quantization, capabilities, domains, and backend claims;
- parameter count, context length, embedding width, block count, and attention block count;
- attention and KV head counts, explicit key/value widths where present, feed-forward width, and
  MoE expert dimensions where present.

Dimensions are extracted from the exact pinned file with the official `@huggingface/gguf` parser.
It uses HTTP range requests and tensor shapes, so validation does not download multi-gigabyte model
weights. Hybrid models retain separate total and full-attention block counts; this matters for KV
cache sizing in Qwen 3.5, Qwen3-Next, and LFM2.

`ModelJarDescriptor.estimateMemory(context, precision)` returns a weights-plus-KV baseline only when
the required dimensions are present. It does not claim backend workspace, tensor repacking,
allocator, JVM, or OS memory. Those costs require backend- and hardware-specific measurements.

## Verification gates

CI applies independent checks:

1. Parse and validate the catalog schema, coordinates, licenses, and backend claims.
2. Query each immutable Hugging Face revision and verify the exact filename, LFS byte size, and
   SHA-256.
3. Range-parse every pinned GGUF and compare its architecture, dimensions, and parameter count with
   the catalog.
4. Build every marker JAR and verify its properties and JSON resources.
5. Build the aggregate JAR, extract its catalog into the static site, and run browser-module tests.
6. Resolve at least 100 distinct sources through the Java classpath registry and smoke-test the
   published facade POM.

The marker's `pure-java=true` claim has a stronger gate in `projects/models`: the exact artifact must
be downloaded, checksum-verified, tokenized against an independent runtime, and run against a
deterministic inference oracle. Missing model files may not turn those tests into passes or skips.

## Portfolio

The current catalog covers general chat, coding, reasoning, mathematics, SQL, healthcare, finance,
legal, translation, transportation, embeddings, and retrieval. It includes dense transformers,
MoE models, hybrid recurrent/attention models, embedding models, and quantizations from sub-1B to
tens of billions of parameters. Search and filtering use catalog domains and capabilities rather
than a manually curated website table.

The older [25-model planning report](launch-catalog-25.md) is retained as historical decision
context. Counts and implementation status in this document and `catalog/models.json` supersede it.
