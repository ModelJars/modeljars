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
profile.qwen3_0_6b_q4_0_epyc_milan_jdk25.recommendation.models.purejava.q4Kernel=unsigned-pairwise
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

The later Qwen-specific Q4 graph changed Qwen's recommendation. The current unsigned-nibble kernel
computes each Q8 zero-point correction once and lets Graal lower the packed arithmetic to the AVX2
pairwise multiply-add instruction family. Six counterbalanced process pairs produced 18 trials per
mode: median decode improved from 37.947 to 58.036 tok/s (+52.94% by aggregate medians), prefill
improved from 90.179 to 115.616 tok/s (+28.21%), TPOT fell from 25.527 to 17.218 ms (-32.55%), and
TTFT fell from 1,733.321 to 1,349.070 ms (-22.17%). Every corresponding input count, output count,
and output hash matched. The candidate won all 18 paired decode, prefill, TTFT, TPOT, and CPU
comparisons. The catalog publishes this profile only for the exact Qwen artifact, EPYC Milan host,
Graal build, Java feature, and vector capability; it does not infer the same choice for other Q4
artifacts or runtimes.

Fresh same-host controls measured llama.cpp `b10012-c71854292` at 101.485 tok/s and Ollama 0.32.0
at 43.309 tok/s. The profiled Java path therefore reaches 57.19% of llama.cpp and 134.00% of Ollama
for decode. Java prefill remains about 25.5% of both native engines, and its median TTFT remains
3.77x llama.cpp, so this result closes the decode target for this profile without closing the prompt
processing gap. RSS is recorded but is not a recommendation claim because compiler-lifetime
residency differed between modes.

A second profile on that exact artifact and runtime measures batched attention-value accumulation
independently. Six counterbalanced process pairs produced 18 trials per mode. Median decode improved
from 35.389 to 36.367 tok/s (+2.76%), TPOT fell from 28.259 to 27.497 ms (-2.70%), TTFT fell from
1,787.030 to 1,761.082 ms (-1.45%), and prefill improved from 87.640 to 89.112 tok/s (+1.68%). All
18 paired token counts and output hashes matched. Process RSS is retained in the evidence but is not
used as a recommendation claim because residency changed over the experiment lifetime.

A third independent profile retains exact two-row attention-score batching while keeping the value
candidate enabled in both modes. The raw 18-trial medians moved from 37.747 to 37.834 decode tok/s,
26.502 to 26.431 ms TPOT, and 1,761.976 to 1,740.809 ms TTFT. Pairing corresponding prompts isolates
the smaller kernel effect: median paired decode improved 1.89%, 16 of 18 decode trials won, and five
of six process-pair medians won. A 64-warmup/256-measure-token follow-up improved all three process
pairs, from a 35.30 tok/s baseline median to 36.48 tok/s, with one checksum and zero GC. The profile
therefore recommends `models.purejava.batchedAttentionScores=true` only for the same exact artifact,
Graal build, processor, vector width, and launch arguments.

A fourth profile retains the staged Q4 feed-forward schedule. It combines gate/up projection,
exact SwiGLU, Q8 preparation, and down projection under one two-stage persistent-worker operation.
Ten warmups and five measured prompts per process across six counterbalanced pairs produced 30
trials per mode. Median TTFT fell from 1,345.784 to 1,249.069 ms (-7.19%) and median prefill rose
from 115.847 to 124.822 tok/s (+7.75%); five of six process pairs won and all corresponding hashes
matched. That profile recommends `models.purejava.stagedQ4Ffn=true` only for the exact measured
Qwen/Graal/EPYC runtime.

A fifth profile retunes prefill batch size after the unsigned-Q4 and staged-FFN graph changes. A
counterbalanced screen covered batches 16, 24, 32, 48, 64, 96, and 128 with 15 trials per size.
Only batch 24 advanced against the established batch-32 profile. The acceptance gate used ten
warmups and five trials per process across six counterbalanced pairs, or 30 trials per mode:

| Metric | Batch 32 | Batch 24 | Change |
| --- | ---: | ---: | ---: |
| Median TTFT | 1,240.231 ms | 1,206.816 ms | -2.69% aggregate; -0.68% paired median |
| p95 TTFT | 1,278.463 ms | 1,263.832 ms | -1.14% |
| Median prefill | 125.061 tok/s | 129.481 tok/s | +3.53% aggregate; +0.89% paired median |
| Median CPU | 8,455 ms | 8,150 ms | -3.61% aggregate; -0.99% paired median |

Batch 24 won 23 of 30 paired TTFT and prefill trials and five of six prefill process-pair medians.
All corresponding input-token and output-hash arrays matched. RSS is recorded without a memory
claim. The profile therefore recommends `models.purejava.prefillBatchSize=24` only for the exact
artifact and runtime selector; Models keeps 32 as its general default and explicit deployment
settings retain precedence.

With the later generic Q4 high-nibble cleanup in Vectors, the latest current-stack Qwen checkpoints
are 58.364 decode tok/s and 129.481 prefill tok/s. Against the recorded same-host controls, those are
57.51% and 28.63% of llama.cpp, and 134.76% and 28.17% of Ollama, respectively. Median Java TTFT is
3.37x llama.cpp and 2.42x Ollama. The native controls were not rerun for the batch-size gate, so
these ratios are comparisons with the pinned prior controls rather than a new cross-engine run.

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
