# EuroLLM 1.7B Instruct Q4_K_M Acceptance Evidence

Status: catalog marker accepted; pure-Java runtime acceptance pending

## Artifact

- Upstream model: `utter-project/EuroLLM-1.7B-Instruct`
- Upstream revision: `a25c7fa65fc2a644e6270b8940dbe295b51da681`
- GGUF source: `mradermacher/EuroLLM-1.7B-Instruct-GGUF`
- GGUF revision: `2951f08f66429c934c8b01a94347161362430808`
- File: `EuroLLM-1.7B-Instruct.Q4_K_M.gguf`
- Size: `1,045,157,088` bytes
- SHA-256: `1cade17f491ea46a686dbee51fbd52442e0f001f102380c3b9d66b4a77f84093`
- License: Apache-2.0

The file was downloaded from the revision-pinned URL and its local byte count
and SHA-256 were verified on July 11, 2026. Both repositories provide
structured lineage and Apache-2.0 metadata. The conversion identifies
`utter-project/EuroLLM-1.7B-Instruct` as its quantized base.

The GGUF retains the internal display name `EuroLLM 1B Annealed Fw` and an
older Unbabel base-model namespace from the checkpoint's training metadata.
Its conversion card and GGUF source URL point to the official utter-project
instruct checkpoint. Runtime acceptance must use the immutable lineage and
tensor contract, not the inherited display name.

## GGUF Contract

Reference runtime: llama.cpp b9960, commit `a935fbffe`

- GGUF version: 3
- Architecture: `llama`
- Metadata entries: 44
- Tensor count: 219
- Tensor types: 49 F32, 145 Q4_K, 25 Q6_K
- Blocks: 24
- Training context: 8,192
- Embedding width: 2,048
- Feed-forward width: 5,632
- Attention heads: 16
- Key/value heads: 8
- RoPE dimensions: 128
- RoPE base: 10,000
- RMS normalization epsilon: 0.00001
- Tokenizer: SentencePiece
- Vocabulary size: 128,000
- Add space prefix: true
- Add BOS: true
- Add EOS: false
- Chat template: ChatML with an empty system turn

## Deterministic Oracle

The oracle applies the embedded ChatML template to this user message:

```text
Translate the following English source text to Portuguese:
English: The sky is blue.
Portuguese:
```

Rendered prompt:

```text
<|im_start|>system
<|im_end|>
<|im_start|>user
Translate the following English source text to Portuguese:
English: The sky is blue.
Portuguese:<|im_end|>
<|im_start|>assistant
```

Rendered prompt token IDs, including the configured BOS token:

```text
[1, 3, 2205, 271, 4, 119715, 271, 3, 15236, 271, 31702, 31817, 557, 5302, 6771, 7684, 6001, 591, 53439, 119782, 271, 31601, 119782, 806, 14930, 656, 15388, 119735, 271, 23392, 19269, 1046, 119782, 4, 119715, 271, 3, 58406, 271]
```

Greedy continuation at temperature 0, including the end-of-generation token:

```text
[697, 83672, 775, 35784, 119735, 4]
```

Decoded continuation:

```text
O céu é azul.<|im_end|>
```

## Limitations

The upstream model card says the instruct model was not aligned to human
preferences and may produce hallucinated, harmful, or false output. The marker
therefore includes `unaligned-output-warning`. Catalog inclusion is not an
endorsement of generated or translated content.

## Runtime Gate

The ModelJars marker intentionally advertises `pure-java=false`. Enabling it
requires a mandatory models-library job that downloads this exact artifact,
verifies size and SHA-256, asserts the GGUF contract above, reproduces the
SentencePiece and ChatML token IDs, and matches the greedy multilingual
continuation. The test must not skip when the artifact is absent.
