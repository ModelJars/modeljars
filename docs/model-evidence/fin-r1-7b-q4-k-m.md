# Fin-R1 7B Q4_K_M Acceptance Evidence

Status: catalog marker accepted; pure-Java runtime acceptance pending

## Artifact

- Upstream model: `SUFE-AIFLM-Lab/Fin-R1`
- Upstream revision: `026768c4a015b591b54b240743edeac1de0970fa`
- GGUF source: `bartowski/SUFE-AIFLM-Lab_Fin-R1-GGUF`
- GGUF revision: `61b412864cc5df96e415808920451a9856d7b078`
- File: `SUFE-AIFLM-Lab_Fin-R1-Q4_K_M.gguf`
- Size: `4,683,073,600` bytes
- SHA-256: `d50f16c5149b4dc103c68e249a136ab7c82f7569a7df707a2d6150bff5994c33`
- License declaration: Apache-2.0

The file was downloaded from the revision-pinned URL and its local byte count
and SHA-256 were verified on July 11, 2026. The conversion metadata identifies
the official model as its base. The upstream English README links to
Apache-2.0, but the repository has neither structured Hugging Face license
metadata nor a standalone `LICENSE` file. The marker therefore carries the
`license-card-only` provenance flag.

## GGUF Contract

Reference runtime: llama.cpp b9960, commit `a935fbffe`

- GGUF version: 3
- Architecture: `qwen2`
- Metadata entries: 28
- Tensor count: 339
- Tensor types: 141 F32, 169 Q4_K, 29 Q6_K
- Blocks: 28
- Training context: 32,768
- Embedding width: 3,584
- Feed-forward width: 18,944
- Attention heads: 28
- Key/value heads: 4
- RoPE base: 1,000,000
- RMS normalization epsilon: 0.000001
- Tokenizer: GPT-2 BPE with `qwen2` pre-tokenizer
- Vocabulary size: 152,064
- Add BOS: false
- Chat template: ChatML

## Deterministic Oracle

The oracle applies the embedded ChatML template. The system message is:

```text
You are a helpful AI Assistant that provides well-reasoned and detailed responses. You first think about the reasoning process as an internal monologue and then provide the user with the answer. Respond in the following format: <think>
...
</think>
<answer>
...
</answer>
```

The user message is:

```text
If a $100 investment gains 10%, what is its value?
```

Rendered prompt token IDs without an added BOS token:

```text
[151644, 8948, 198, 2610, 525, 264, 10950, 15235, 21388, 429, 5707, 1632, 5504, 1497, 291, 323, 11682, 14507, 13, 1446, 1156, 1744, 911, 279, 32711, 1882, 438, 458, 5306, 1615, 76728, 323, 1221, 3410, 279, 1196, 448, 279, 4226, 13, 39533, 304, 279, 2701, 3561, 25, 366, 26865, 397, 9338, 522, 26865, 397, 27, 9217, 397, 9338, 522, 9217, 29, 151645, 198, 151644, 872, 198, 2679, 264, 400, 16, 15, 15, 9162, 19619, 220, 16, 15, 13384, 1128, 374, 1181, 897, 30, 151645, 198, 151644, 77091, 198]
```

Twelve-token greedy continuation at temperature 0:

```text
[13708, 766, 397, 32313, 11, 1077, 594, 21403, 419, 3491, 3019, 553]
```

Decoded continuation:

```text
<think>
Okay, let's tackle this problem step by
```

## Behavioral Findings

A raw completion prompt returned an incorrect `$100` value. Fin-R1 must be
invoked through its ChatML contract. With ChatML, the model correctly computes
the `$10` gain and `$110` final value, but it can continue its internal
reasoning beyond 400 tokens and did not honor short-response instructions in
the reference runs. Callers must enforce generation limits and must not treat
a truncated response without the final answer section as a completed answer.

The model card says its advice and analysis are reference material rather than
a replacement for professional financial judgment. Catalog inclusion is not
an endorsement of its financial output.

## Runtime Gate

The ModelJars marker intentionally advertises `pure-java=false`. Enabling it
requires a mandatory models-library job that downloads this exact artifact,
verifies size and SHA-256, asserts the GGUF contract above, applies ChatML,
matches the rendered prompt and greedy token IDs, and retains the financial
advice and license-provenance warnings. The test must not skip when the
artifact is absent.
