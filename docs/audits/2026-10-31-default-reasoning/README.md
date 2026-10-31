# Default demo reasoning display — 2026-10-31

All **80 published chat/tool artifacts ran inference**: 79 qualified chat models with three prompts
and Needle 2 with one constrained tool request, for 238 final selected cases. The artifact list and
explicit exclusions are in [coverage.json](coverage.json). The 37 published non-chat interfaces
(embedding, reranking, speech and typed decision) do not expose chat completions; 346 unpublished
candidates were not tested. Composite demo compilation was also checked, separately from live weights.

## Result

- **Zero explicit reasoning-marker leaks** in the final display of the 238 selected cases.
- **74 models** produced visible, normally terminated responses for every tested prompt.
- **Five Nexus variants** still hit the token limit: Finance Q4_K_M and Q5_K_S, Legal Q4_K_M and
  Q5_K_M, and Medical Q4_K_M. Their repetitive/unmarked commentary is not a display-filter pass.
- **Qwen3.5 0.8B Q5_K_M** spent all 512 tokens reasoning on the missing-context question. Its
  unfinished trace stays hidden, and the demo reports that no final answer was produced.
- **Flint passed all three prompts:** `Paris`, `42`, `UNKNOWN`, with reasoning hidden by default.
- HuatuoGPT's Markdown reasoning sections, DeepSeek R1's missing opening delimiter, Gemma 4's
  direct answers, and Needle 2's tool output are covered by the implementation and regressions.

These are display results, **not an accuracy qualification or an all-models pass**. Manual review
also found unmarked step-by-step prose in Qwen Math, wrong arithmetic in MobileMoE (`50`), EuroLLM
and SmolLM2 (`32`), fabricated museum prices in two Llama 3.2 1B variants, and literal end-of-turn
text in Mistral/Mathstral. Per-model review notes and every raw/visible response are retained in
[responses.json](responses.json). Ordinary explanatory prose cannot safely be removed by guessing
where an unmarked thought process ends. Three prompts cannot establish behavior on all inputs.

## Changes and validation

Generated chat, tool and Qwen chat/tools composite demos separate known reasoning protocols from
answer display. Raw output is an explicit `-Dmodeljars.demo.showReasoning=true` opt-in; tool parsing
still receives the original protocol. Only the actual Gemma 4 prompt marker triggers its channel-buffering path. DeepSeek R1 buffers its
ambiguous prefix. Ordinary supported chat output streams normally.

The default chat output allowance is 512 tokens, with a configurable 2,048-token single-turn context.
The larger output allowance resolved the tested 64-token budget failures except the Qwen3.5 variant
noted above. Ornith 9B and Qwen3.5 4B loaded successfully with the same 8 GB heap after applying the
smaller context; both answered all three prompts. Missing/truncated answers are reported explicitly.
Generated-token timing still includes reasoning; first-visible-answer latency is reported separately.
No speedup on the user's i9-14900HX laptop has been measured.

Validation includes the full CLI unit suite, generated Java compilation and parser tests for all
79 qualified chat templates, split/nested/truncated delimiters, Unicode, literal tags, explicit
raw opt-in, composite compilation, and a JBang build of the standalone Flint example.

## Evidence and reproducibility

[Audit tools](../../../tools/reasoning-audit/README.md) document compilation, live inference and
replay. Both Linux campaigns used released ModelJars 0.1.71 / Models 0.5.8, Temurin 25.0.3+9,
8 GB heap and eight native workers on disposable m6a.4xlarge hosts. The initial run used the old
64-token chat budget and runtime-default context; the 19-model retry used 512/2048 and included
Flint and DeepSeek regression checks. Initial failures remain in the response history.

The final parser was replayed character by character over retained raw completions. JSON records
explicitly label this as display replay, not a second model inference, and preserve original
inference timings and original visible output. SHA-256 values identify the generated demo, catalog,
model artifacts and retained replay reports. The first setup attempt failed alias resolution before
inference and is excluded from model results.

Raw campaign logs and manifests are retained at:

- `s3://models-qual-077051030817/results/reasoning-default-20261031T024452Z/`
- `s3://models-qual-077051030817/results/reasoning-retry-20261031T030235Z/`

MobileMoE required the repository's existing Hugging Face credential and ran in the
[manual authenticated audit](https://github.com/ModelJars/modeljars/actions/runs/41163082310), whose
`mobilemoe-reasoning-display` artifact retains its JSON and logs. The credential was not copied to
local storage or the cloud workers. All four inference workers, plus both failed-setup workers,
terminated; their instance block-device mappings are empty.
