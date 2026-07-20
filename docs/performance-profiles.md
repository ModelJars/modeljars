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
- recommendations and/or a typed Java launch profile backed by a controlled comparison.

Build validation rejects unknown models, coordinate or SHA mismatches, unsupported backends,
duplicate IDs, empty selectors, profiles with neither recommendations nor launch guidance, invalid
launch arguments, invalid timestamps, and invalid metrics.
Runtime matching requires every selector entry to match. Extra runtime facts are allowed.

## Java Launch Profiles

Compiler and JVM startup options cannot be applied after the Java process starts. An optional
`javaLaunch` object therefore carries a runtime-family identifier, recommended Java feature version,
and an ordered list of JVM arguments. The generated properties resource uses numbered keys so order
is stable:

```properties
profile.qwen3_0_6b_q4_0_epyc_milan_jdk25.launch.runtime=graal-jvmci
profile.qwen3_0_6b_q4_0_epyc_milan_jdk25.launch.javaFeature=25
profile.qwen3_0_6b_q4_0_epyc_milan_jdk25.launch.jvmArgument.000=-Djdk.graal.MaximumInliningSize=10000
profile.qwen3_0_6b_q4_0_epyc_milan_jdk25.launch.jvmArgument.001=-Dvectors.gguf.q4ShortPairwise=true
```

`JavaLaunchProfile.command(...)` returns an argument list suitable for `ProcessBuilder`; it does not
construct a shell command. `missingArguments(...)` supports startup diagnostics against the JVM's
actual input arguments. Recommendation-only profiles do not require a `javaLaunch` block.

## Evidence

Evidence records the benchmark ID and timestamp, baseline and candidate names, warmup and trial
counts, generated-token count, baseline/candidate metric maps, run controls, and whether every
output hash matched. The initial profiles compare unchanged Models code under HotSpot C2 and a
post-fix GraalVM JVMCI build on the controlled eight-vCPU AMD EPYC Milan host.

The first compiler-only data deliberately demonstrated that a compiler cannot be a global default:

| Model | HotSpot decode | Graal decode | Change | Recommendation |
| --- | ---: | ---: | ---: | --- |
| Qwen3 0.6B Q4_0 | 25.86 tok/s | 19.65 tok/s | -24.0% | HotSpot C2 |
| SmolLM2 360M Q8_0 | 21.23 tok/s | 44.70 tok/s | +110.6% | Graal JVMCI |
| MiniCPM5 1B Q4_K_M | 13.19 tok/s | 15.34 tok/s | +16.3% | Graal JVMCI |

All ten output hashes matched for every comparison. A separate Qwen run with six warmups remained
at 19.75 tok/s, ruling out insufficient warmup as the explanation for its regression.

The later Qwen-specific Q4 graph changed Qwen's recommendation. Under the exact post-fix GraalVM
build and bounded launch profile above, 12 trials per mode measured 28.752 to 34.844 decode tok/s
(+21.19%), 70.006 to 88.512 prefill tok/s (+26.43%), and 2,220.792 to 1,757.506 ms median TTFT
(-20.86%). All token counts and output hashes matched. The catalog now publishes this profile rather
than the obsolete default-graph HotSpot recommendation; it does not infer the same choice for other
Q4 artifacts or Graal builds.

The same exact model and runtime may have multiple profiles when each recommendation has its own
controlled evidence. Recommendations must be non-conflicting when their selectors overlap. The
MiniCPM5 mixed-K profile is separate from its compiler profile because it measures a different
choice:

| JVM | Independent decode | Mixed Q4_K/Q6_K decode | Change | Recommendation |
| --- | ---: | ---: | ---: | --- |
| OpenJDK 25.0.3 HotSpot | 13.05 tok/s | 13.37 tok/s | +2.52% | `models.purejava.mixedKProjections=true` |
| GraalVM CE 25.2.4-dev | 15.28 tok/s | 15.47 tok/s | +1.31% | corroborating gate |

Each JVM result combines ten control-first and ten candidate-first trials after two warmups per
process. All 40 paired output hashes matched; mean TTFT changed by +0.02% on HotSpot and -0.06% on
Graal. The profile's primary evidence map records the HotSpot comparison, while its controls retain
the independent Graal values and exact Models/Vectors commits.

## Safety Contract

`ModelPerformanceProfile.safeForAutomaticSelection()` requires exact output-hash agreement and at
least one backend recommendation or Java launch profile. It is an evidence gate, not an execution
mechanism. A consuming backend must still:

1. match the exact model artifact and complete runtime selector;
2. whitelist each recommendation key and permitted value;
3. keep unsupported keys inert and visible in diagnostics;
4. allow an explicit operator override; and
5. rerun exactness and full-model performance gates when its implementation changes.

Profiles with mismatched output hashes remain representable as negative evidence, but cannot pass
the automatic-selection gate. Marker JARs without measurements publish an empty schema-v1 resource;
the catalog never infers a recommendation from architecture or quantization alone.
