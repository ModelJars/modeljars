# Desert Ant candidate audit

Date: 2026-09-14

Status: feasibility research only. No model in this document is qualified or authorized for the
public ModelJars catalog.

## Runtime boundary

Models does not adopt the Desert Ant SDK, LiteRT, ONNX Runtime, MLX, a model server, or another
inference engine. Public artifacts must execute in the application process through a Models-owned
graph:

1. implement and test the graph in Java first;
2. compare it with pinned upstream outputs only as an oracle;
3. profile the complete Java path; and
4. add a Models-owned Rust/FFM kernel only for a measured bottleneck, with a Java reference and
   fallback.

Supporting an ONNX, TFLite, Core ML, or MLX/Safetensors artifact describes its storage format. It
does not authorize importing the runtime normally associated with that format.

The source reference used for this audit is
[`Desert-Ant-Labs/desert-ant-core`](https://github.com/Desert-Ant-Labs/desert-ant-core) revision
`c015d5d95028caba783e802442e30ddd66c9247e`. Its manifest pins the weight revisions recorded below.

## License gate

Every reviewed artifact uses `LicenseRef-DAL-Source-Available-1.0`, not an open-source license. The
published terms permit benchmarking and embedding a model in an application, require attribution,
require a commercial license above the stated usage threshold, and prohibit distributing a model
or SDK as a standalone product. ModelJars marker JARs would not contain these weights, but the CLI
would install them directly from Hugging Face and Models would provide an alternate runtime.

Before public qualification, obtain written confirmation from Desert Ant Labs that this exact
direct-download and independent in-process-runtime arrangement is allowed. If approved, the marker
must expose the license, attribution, usage threshold, upstream revision, every artifact hash, and
the fact that downloading accepts the license. Until then, experiments remain internal and may not
produce a catalog entry.

## Candidate order

| Priority | Candidate | Exact upstream artifact | Java feasibility | Admission state |
| --- | --- | --- | --- | --- |
| 1 | Tongue | `v1.0.0`; `tongue_int8.bin`, 2,104,940 bytes, SHA-256 `2cea7b6a92be043c58596d0b6f482fbc27fb901e5697d77159247d3ec6bdf767` | Excellent. Unicode-scalar FNV-1a n-grams, an int8 embedding gather, pooled features, one dense layer, masked softmax, and deterministic script routing. No generic ONNX executor is necessary. | License permission, a Models language-identification API, independent Java implementation, and held-out 84-language gates required. |
| 2 | Shapes | `v0.3.0`; `shapes.safetensors`, 202,320 bytes, SHA-256 `d0e2de0413af7e662906cf3a8463a0eee138ddf88b5a039e5fd0459c3ff06aae` | Excellent. Small fixed-shape classifier over a 256 by 3 stroke feature window. The existing Models Safetensors reader can supply weights; preprocessing and geometric rejection remain Java. | License permission, a shape-classification API, tensor-name audit, oracle vectors, and geometry/false-positive gates required. |
| 3 | Gist English | `v2.2.0`; 8.3 MB int8 static embedding, 0.4 MB tokenizer, and 13.0 MB head | Good. Unigram tokenization, int8 row gather, mean/L2 pooling, hashed n-grams, and a small classifier head. The head should be expressed directly in Java rather than loaded through LiteRT. | License permission, a general text-classification API, head-weight extraction supplied or explicitly permitted by the owner, and held-out topic gates required. |
| 4 | Gist multilingual | `v2.2.0`; 66.9 MB int8 embedding, 4.6 MB tokenizer, and 13.0 MB head | Good after the English path. It shares the same Java graph and expands language coverage to 101 languages. | Same gates as Gist English, plus multilingual evidence and memory/latency limits. |
| 5 | Title | `v0.1.0`; `model.safetensors`, 286,449,872 bytes, SHA-256 `c592e3f003f68d998d1a955af8f4d485bec4a937b8318fd3ff5092782525f245` | Valuable but substantial. It is a 352M-parameter `GraniteMoeHybridForCausalLM` checkpoint with affine 6-bit, group-size-64 weights. Models needs the Granite 4 hybrid attention/recurrent/MoE graph and MLX quantized tensor decoding. Java comes first; llama.cpp may be a pinned oracle, never a linked runtime. | License permission, architecture work, exact-logit tests, fixed-template quality tests, and Java profiling required before considering a Rust kernel. |
| 6 | Redact | `v0.4.0`; `redact.tflite`, 24,529,472 bytes, SHA-256 `ee36727f07e3237569e71427bfe661463a82e526f7e97e92b0fa583cad16ed27` | Promising product role, but the current artifact is an int8 TFLite token classifier. Existing BERT primitives may be reusable only after the exact graph is established. Prefer owner-supplied Safetensors or an explicitly permitted weight export over implementing a general TFLite runtime. | License permission, PII/NER API, offset-safe tokenizer, deterministic recognizers, per-language recall/precision, and negative-text gates required. |
| 7 | Clear | `v0.3.0`; `clear-studio.onnx`, 24,801,006 bytes, SHA-256 `4176990296374e972f0c347ddcc7988d216829cafc58e4d54a98dccd715107d9` | Feasible but not small work. The graph has 1,908 nodes and 26 operator types, including convolution, transposed convolution, instance normalization, einsum, padding, and STFT-domain tensor manipulation. Implement only its pinned Java operator subset, not ONNX Runtime. | License permission, audio-enhancement API, Java DSP front end, waveform equivalence, streaming, real-time factor, and peak-memory gates required. |
| 8 | Emo | `v0.7.0`; `emo.tflite`, 10,222,736 bytes, SHA-256 `2c382e8da94bab64172c0694b0350abb96829bf89eb623008ed0085128621de2` | Small and useful, but current weights are packaged as a TFLite graph. A direct Java graph needs documented tensor semantics or explicit permission to export the older Safetensors form. | License permission, classification API, tokenizer parity, multilingual top-k evidence, and graph audit required. |
| 9 | Uhm | revision `612592c10ad7b2a51f3237725448a1aad212480b`; 47 MB FP16 ONNX | Moderate to difficult. It is a frame-level audio classifier over 16 kHz input and likely reuses future speech-encoder/DSP work. A Models-owned Java ONNX subset is acceptable; ONNX Runtime is not. | License permission, audio classification API, frontend equivalence, temporal reconciliation, accuracy, and real-time gates required. |
| 10 | Ear | `v0.1.0`; `ear.tflite`, 23,062,016 bytes, SHA-256 `71cc15c3f24abbea2dcdf2892370d85806891ad7822cd3107abc0629f10bc955` | Moderate to difficult. It retains the language-identification portion of Whisper Tiny and requires a Java 16 kHz/80-bin log-mel frontend plus the exact encoder/head graph. | License permission, reusable audio frontend, TFLite graph translation or owner-supplied weights, and held-out 99-language gates required. |
| 11 | Align | `v1.0.0`; two compiled Core ML stages of about 0.3 MB each | Narrow Apple integration rather than a portable ModelJar. Input includes Apple SpeechAnalyzer word timings, making the system contract dependent on an OS recognizer even though the stages are in-process. | Defer unless the catalog adds an explicitly Apple-only postprocessor role and the owner approves independent loading. |
| 12 | Voz | `v0.1.0`; compiled Core ML encoder/decoder/mel bundle, about 477 MB of weights | Apple-only ASR bundle with no portable upstream graph. Reusing Core ML in-process is not the same as a Java implementation, and reconstructing the graph is not permitted. | Defer; request a portable source checkpoint and explicit runtime permission. |

## Rejected or unavailable intake

- **Clips `v0.1.0`:** the provider documents that its two roughly 283 MB TFLite exports do not yet
  work with its own SDK and reports an unresolved roughly 2.1 GB peak-RSS result. Do not spend a
  qualification slot until the upstream artifact is corrected.
- **Schemer, Moderator, Toxic, Eye, Face, and Who:** closed beta or internal. There is no public,
  stable artifact to qualify.
- **Any SDK-backed shortcut:** importing `ai.desertant:*`, LiteRT, ONNX Runtime, MLX, or a local
  server would violate the Models runtime boundary and is not a fallback plan.

## First experiments after license confirmation

1. Add a small, architecture-neutral classification result/API in Models with probabilities,
   labels, model diagnostics, and explicit artifact provenance.
2. Implement Tongue independently in Java from its documented artifact layout and algorithm;
   compare exact bucket IDs, logits, ranking, and reliability decisions against pinned upstream
   fixtures.
3. Implement Shapes from the Safetensors artifact and public tensor contract; require exact class
   probabilities before testing its geometric rejection layer.
4. Use those two deliberately small paths to establish ModelJars qualification schemas for
   classification artifacts before attempting Gist, Redact, or audio.
5. In parallel, request portable source weights and written ModelJars permission from Desert Ant
   Labs. A technically passing result remains non-publishable until that answer is retained.

