# Default-answer display audit

This audit exercises the display helper emitted by `modeljars demo` against released SDK runtimes.
It is not a model accuracy or throughput qualification. The catalog snapshot covers 79 qualified
chat artifacts and the Needle 2 tool artifact. Embedding, reranking, speech and typed-decision
interfaces do not return chat reasoning; unpublished candidates are recorded separately.

`ReasoningCatalogProbe` resolves catalog aliases to marker coordinates, renders each runtime's
qualified prompt, and retains raw and displayed output, stop reason, usage, artifact SHA and
backend diagnostics. It asks three fixed chat questions or one constrained Needle 2 tool request.
Runtime errors and missing final answers are retained as failures. Synthetic questions contain no
user data. The probe never executes a generated tool call.

Compile the checked-in generated Flint example and probe against the released SDK 0.1.71,
modeljars-catalog 0.1.71 and their runtime dependencies (including Jackson). The example supplies
the exact `ModelJarsDemo.AnswerStream` class used by the probe:

```sh
javac --add-modules jdk.incubator.vector -cp 'lib/*' -d classes \
  examples/flint/ggml-org-flint-chat-demo.java tools/reasoning-audit/ReasoningCatalogProbe.java \
  tools/reasoning-audit/ReasoningReplay.java
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Xmx8g -Dmodels.native.kernels.threads=8 -Dmodels.purejava.maxContextLength=2048 \
  -Dmodeljars.demo.maxTokens=512 -cp 'classes:lib/*' \
  ReasoningCatalogProbe ggml_org_flint_gguf_q4_k_m flint.json
```

`worker.sh` is for dedicated disposable Linux workers only: it uploads evidence, removes only its
own per-job model cache, then shuts the machine down. Its manifest supplies a SHA-verified payload,
shard, result URI and optional heap/context/output budget. Use an independent instance lifetime
limit and terminate-on-shutdown. Do not run it on a developer laptop.

`ReasoningReplay INPUT OUTPUT` replays retained raw completions character by character through a
new display helper. Reports explicitly distinguish replay from live inference; original timings
remain inference timings. `summarize.py RESULTS TARGETS OUTPUT` includes every planned model and
does not count missing, failed, truncated or empty responses as passes.

Automated checks detect explicit reasoning delimiters. Review the visible responses too: unmarked
step-by-step prose, hallucinations, refusal, repetition and instruction-following failures cannot
be repaired by safely removing a known protocol section. A marker-free answer is not an accuracy
pass, and three prompts cannot prove absence of reasoning leakage on every possible request.
