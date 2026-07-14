# HuatuoGPT-o1-7B Q4_K_M Acceptance Evidence

Status: catalog marker and pure-Java runtime accepted

## Artifact

- Upstream model: `FreedomIntelligence/HuatuoGPT-o1-7B`
- GGUF source: `bartowski/HuatuoGPT-o1-7B-GGUF`
- Revision: `5b481e71fa41e2ccffdd863dc01f27be48075bd1`
- File: `HuatuoGPT-o1-7B-Q4_K_M.gguf`
- Size: `4,683,074,720` bytes
- SHA-256: `4643521a184cb26df0f7c57da9aead0c632b286a9aff103c9f9dca4dc059abd7`
- License: Apache-2.0

The file was downloaded from the revision-pinned URL and its local byte count
and SHA-256 were verified on July 11, 2026.

## GGUF Contract

Reference runtime: llama.cpp b9960, commit `a935fbffe`

- GGUF version: 3
- Architecture: `qwen2`
- Metadata entries: 43
- Tensor count: 339
- Tensor types: 141 F32, 169 Q4_K, 29 Q6_K
- Blocks: 28
- Training context: 32,768
- Embedding width: 3,584
- Feed-forward width: 18,944
- Attention heads: 28
- Key/value heads: 4
- RoPE base: 1,000,000
- Tokenizer: GPT-2 BPE with `qwen2` pre-tokenizer
- Vocabulary size: 152,064
- Add BOS: false

## Deterministic Oracle

Raw completion prompt:

```text
Question: Which organ pumps blood through the human body?
Answer:
```

Prompt token IDs without an added BOS token:

```text
[14582, 25, 15920, 2872, 42775, 6543, 1526, 279, 3738, 2487, 5267, 16141, 25]
```

Four-token greedy continuation at temperature 0:

```text
[576, 4746, 374, 279]
```

Decoded continuation:

```text
 The heart is the
```

## Runtime Acceptance

The ModelJars marker advertises `pure-java=true` after the mandatory
`huatuoGptO17BSlowTest` in `integrallis/models` verified this exact artifact.
The task resolves and checksum-verifies the model through ModelJars, asserts
the GGUF and tokenizer contracts above, and matches the llama.cpp greedy token
IDs with the pure-Java backend. It fails when the artifact cannot be resolved
or installed and cannot report a missing model as a pass or skip.

The `medical-use-warning` feature remains part of the descriptor. Runtime
compatibility is not a clinical-safety or medical-validity claim.
