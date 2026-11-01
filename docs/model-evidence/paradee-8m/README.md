# Paradee-8M — intake triage, 2026-11-01

**Nothing here is a quality or speed measurement of Paradee.** This is the artifact triage that
decides whether the runtime can serve the model and whether a gate that exists can score it.
`npm run catalog:triage` reads GGUF headers; Paradee publishes no GGUF, so `triage.py` does the
equivalent for an ONNX graph: it pins the revision, re-derives every digest from the downloaded
bytes, and inventories the graph.

`TRIAGE-v1.2.md` and `TRIAGE-v1.1.md` are generated, not written:

```
uv run --with onnx docs/model-evidence/paradee-8m/triage.py --version 1.2 > TRIAGE-v1.2.md
uv run --with onnx docs/model-evidence/paradee-8m/triage.py --version 1.1 > TRIAGE-v1.1.md
```

**The pinned candidate is v1.1**, which is the version the owner asked for:
`sahilmahendrakar/Paradee-8M-v1.1` at `330617ad1f097b4f6b02c103e54b39006c090b45`. **v1.2** at
`ed2934d4df8a4fd891e29584c5e1104649409765` was triaged beside it and is *recommended* — four
voices instead of one for 681 more bytes — but that is a decision for the owner and it has not been
taken. The v1.2 inventory below is why the recommendation exists; every blocker is identical in
both, so the choice changes digests and nothing else.

## What the v1.2 triage establishes (v1.1 differs only in the `voice` input and the voice tables)

| fact | value | consequence |
| --- | --- | --- |
| formats published | ONNX fp32 (34.8 MB) + ONNX int8 (8.46 MB) + two `.pt` halves | **no GGUF, no safetensors**: nothing the catalog loader reads today |
| `model_type` / `version` | `paradee` / `1.2` | a new architecture, not a variant of one we serve |
| graph inputs | `input_ids` int64 `[1, tokens]`, `speed` float32 `[1]`, **`voice` int64 `[1]`** | phoneme ids, not text; the voice input is the only graph change from v1.1 |
| voices | `af_heart`, `bf_isabella`, `am_puck`, `bm_fable` (config order = voice index) | the British pair needs misaki's **British** dictionary, the American pair its American one |
| sample rate | 24000 | Soprano is 32000 |
| parameters | 7,867,227 initializer elements (int8 graph); 8,581,449 (fp32) | the card's 7.88M, confirmed from the bytes; v1.2 adds 112 elements over v1.1, the per-voice tables |
| unserved operators | `InstanceNormalization` 22, `ConvTranspose` 7, `LSTM` 6, `Resize` 3 | zero occurrences of any of them in `models-audio` or `backend-java` |
| nondeterministic operators | `RandomNormalLike` 1 | the graph draws its own noise, so two runs of the same input differ |
| graph size | 2,117 nodes / 53 operators (fp32); 2,535 / 56 (int8, adds `DequantizeLinear` 105) | the whole operator set an executor has to cover |

## What the publisher's own pipeline does (read from `paradee/tts.py` @ `aae3cc46`)

- Splits text into sentences at `(?<=[.!?…])\s+|\n+`, phonemizes each with
  `misaki.en.G2P(trf=False, british=<voice starts with "b">, fallback=espeak.EspeakFallback(british), unk="")`,
  keeps only characters in `config.json["vocab"]`, truncates to 510 phonemes, wraps in pad id `0`.
- Feeds `speed` as float32 and `voice` as the index into the config's `voices` list.
- Runs onnxruntime on `CPUExecutionProvider` with `intra_op_num_threads = threads` and
  `inter_op_num_threads = 1`, where `threads` **defaults to 1** (`tts.py:23`), and concatenates
  per-sentence waveforms. So the publisher's 62x figure is a one-thread figure by construction.
- The package depends on `misaki>=0.9.4` (0.9.4 on PyPI is Apache-2.0) and on `espeakng-loader`
  for the out-of-vocabulary fallback. `espeakng-loader` declares **no license** on PyPI and exists
  to load eSpeak NG, which is **GPL-3.0** upstream (`github.com/espeak-ng/espeak-ng`), so that path
  cannot be packaged into a runtime component under rule 6 of the working agreement. misaki's
  English path (`misaki/en.py`, 738 lines at `fba1236`, which is also its head) is a lexicon
  (`us_gold.json` 3,000,469 B, `us_silver.json` 3,099,517 B, `gb_gold.json` 2,838,552 B,
  `gb_silver.json` 3,663,898 B — 12.6 MB of JSON), stem rules for -s/-ed/-ing, number and currency
  expansion through `num2words`, stress rules, and part-of-speech-dependent special cases that need
  spaCy.

## Publisher-reported figures — read, never measured here

| voice | UTMOS (Kokoro) | WER (Kokoro) |
| --- | ---: | ---: |
| `af_heart` | 4.40 (4.52) | 5.8% (5.7%) |
| `bf_isabella` | 4.06 (4.41) | 5.7% (5.6%) |
| `am_puck` | 4.32 (4.47) | 5.4% (6.3%) |
| `bm_fable` | 4.18 (4.32) | 5.7% (5.7%) |

Int8 ONNX, "the same 200 held-out sentences" (WikiText-103, unpublished), Whisper base as the
judge. Speed: "the same as v1.1", which the v1.1 card put at 62.1x real time on one CPU thread of
an Apple M4 Pro; the paper says 25.0x for v1.0 and the repository says 18x. Three self-reported
speeds for one family, spanning 3.4x. Paper: <https://huggingface.co/papers/2610.06817>. Code:
<https://github.com/sahilmahendrakar/paradee> (Apache-2.0, 175 stars, head `aae3cc46`, pushed
2026-10-28).

## Mirrors are not the source

`suryatmodulus/Paradee-8M-v1.1`, `FluidInference/paradee-8m-coreml`, `dr33m/paradee-8m-executorch`,
`danyalmalik/Paradee-8M-ONNX-WebGPU`, `TechnoBaptist/Paradee-8M-v1.0` and `coder543/paradee-8m-glade`
carry the weights. `sahilmahendrakar` is the author of the paper and the code, so that namespace
is the one pinned here.
