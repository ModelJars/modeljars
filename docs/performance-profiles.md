# ModelJars Performance Profiles

Performance profiles carry measured model-specific guidance without coupling the marker identity
schema to a particular inference backend. The source is
`catalog/performance-profiles.json` with schema version 1. Gradle publishes equivalent resources:

```text
META-INF/modeljars/performance-v1.properties
META-INF/modeljars/performance-v1.json
```

The properties resource keeps `modeljars-core` dependency-free. The JSON resource feeds catalog
tooling and is embedded in the generated website catalog under each model's
`performanceProfiles` field.

## Identity And Scope

Every profile must bind all of these values:

- a unique profile ID and existing catalog model ID;
- the exact marker Maven coordinate and artifact SHA-256;
- one supported inference backend;
- explicit runtime selector properties; and
- recommendations backed by a controlled comparison.

Build validation rejects unknown models, coordinate or SHA mismatches, unsupported backends,
duplicate IDs, empty selectors, empty recommendations, invalid timestamps, and invalid metrics.
Runtime matching requires every selector entry to match. Extra runtime facts are allowed.

## Evidence

Evidence records the benchmark ID and timestamp, baseline and candidate names, warmup and trial
counts, generated-token count, baseline/candidate metric maps, run controls, and whether every
output hash matched. The initial profiles compare unchanged Models code under HotSpot C2 and a
post-fix GraalVM JVMCI build on the controlled eight-vCPU AMD EPYC Milan host.

The initial data deliberately demonstrates that a compiler cannot be a global default:

| Model | HotSpot decode | Graal decode | Change | Recommendation |
| --- | ---: | ---: | ---: | --- |
| Qwen3 0.6B Q4_0 | 25.86 tok/s | 19.65 tok/s | -24.0% | HotSpot C2 |
| SmolLM2 360M Q8_0 | 21.23 tok/s | 44.70 tok/s | +110.6% | Graal JVMCI |
| MiniCPM5 1B Q4_K_M | 13.19 tok/s | 15.34 tok/s | +16.3% | Graal JVMCI |

All ten output hashes matched for every comparison. A separate Qwen run with six warmups remained
at 19.75 tok/s, ruling out insufficient warmup as the explanation for its regression.

## Safety Contract

`ModelPerformanceProfile.safeForAutomaticSelection()` requires exact output-hash agreement and at
least one recommendation. It is an evidence gate, not an execution mechanism. A consuming backend
must still:

1. match the exact model artifact and complete runtime selector;
2. whitelist each recommendation key and permitted value;
3. keep unsupported keys inert and visible in diagnostics;
4. allow an explicit operator override; and
5. rerun exactness and full-model performance gates when its implementation changes.

Profiles with mismatched output hashes remain representable as negative evidence, but cannot pass
the automatic-selection gate. Marker JARs without measurements publish an empty schema-v1 resource;
the catalog never infers a recommendation from architecture or quantization alone.
