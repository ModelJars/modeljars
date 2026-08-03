# Model candidate intake: coding and voice

Date: 2026-08-03

This intake records the exact artifacts selected for future qualification. All
three entries are internal candidates only. They do not appear on the public
site, are not published as marker JARs, and cannot be opened by Models until an
exact artifact earns a passing entry in `catalog/qualifications.json`.

## Decisions

| Candidate | Decision | Pinned artifact | Current blocker |
|---|---|---|---|
| KAT-Coder V2.5 Dev | Accept as the next large coding candidate | bartowski Q4_K_M, revision `d8f684f08d2950ea9d2db6a35ef7dada0707858b`, SHA-256 `4221c26e5663502d1c96fc901c9967d0e70ce2dcfaa5a9fb9280a46bd19e3c07` | Models does not implement the Qwen3.5-MoE hybrid linear/full-attention decoder. |
| Qwen3-TTS 12Hz 0.6B CustomVoice | Accept as the first voice-runtime target | Qwen BF16 snapshot, revision `85e237c12c027371202489a0ec509ded67b5e4b5` | ModelJars installs one file and Models has no audio-generation API, speech codec, or Qwen3-TTS decoder. |
| dots.tts-mf | Accept as the second voice-runtime target | dots-studio BF16 snapshot, revision `25c53fb462e57087e52237daa5ea30df1c5cc328` | Requires a multi-file snapshot, reference audio input, continuous-latent mean-flow decoding, a speaker encoder, and a 48 kHz vocoder. |

## KAT-Coder V2.5 Dev

KAT-Coder is a worthwhile ModelJars candidate. The upstream checkpoint is
Apache-2.0, text-only, 35B total parameters with about 3B active parameters,
and aimed directly at agentic coding. The selected GGUF is a single 19.92 GiB
Q4_K_M artifact with immutable revision, byte size, and digest metadata.

It is not a near-term catalog qualification. Its `qwen3_5_moe` text model has
40 layers, 256 experts with 8 selected per token, and a three-linear/one-full
attention pattern. Supporting ordinary dense Qwen3 does not support this
architecture. Models must first add:

1. Qwen3.5 hybrid linear/full attention and its convolutional state;
2. MoE routing, shared experts, and expert memory planning;
3. the 248,320-token tokenizer and KAT chat/tool template;
4. exact-token oracles against llama.cpp for the pinned GGUF; and
5. a coding-agent workload that checks edits and tool calls, not just general
   RAG answers.

## First voice target: Qwen3-TTS 0.6B CustomVoice

The 0.6B CustomVoice checkpoint is the best first voice target because it is
smaller than the 1.7B family and can synthesize with built-in speakers without
requiring a user-provided reference clip. The pinned snapshot contains a
1,811,626,576-byte model checkpoint and a 682,293,092-byte 12 Hz speech-tokenizer
checkpoint, plus revision-pinned configuration and tokenizer sidecars.

The implementation sequence is:

1. add a multi-file artifact manifest and atomic snapshot installer;
2. add a `TextToSpeechModel` API returning sample rate plus PCM samples or a
   stream of audio frames;
3. load multi-file Safetensors without a Python dependency;
4. implement the Qwen3-TTS talker, code predictor, and 12 Hz speech decoder;
5. match a fixed upstream CustomVoice sample before performance work; and
6. qualify the 0.6B Base checkpoint for reference-audio cloning only after the
   built-in-voice path passes.

## Second voice target: dots.tts-mf

`dots.tts-mf` is preferable to `dots.tts-soar` as the first dots runtime target
because the mean-flow checkpoint is distilled for fewer sampling steps. It is
still a more complex Java implementation than Qwen3-TTS. The pinned snapshot
binds these weight files:

| Role | Bytes | SHA-256 |
|---|---:|---|
| Main TTS model | 4,398,915,254 | `a16d5798da197bf647fc01915236873e4672e975b0341360703ec49d002c4696` |
| Speaker encoder | 29,150,484 | `1cf3861c9dee79e4db34bd0b8a4155e68bed27a7c6274e168bb6ee4fed191c85` |
| 48 kHz vocoder | 723,585,584 | `c0e45c08f480df67ac4c354b465355fcc7e2f6c8765263b6dfeddd1f4671c93d` |

## Voice qualification gate

Voice models need a separate policy; the current guarded-RAG gate is not a
meaningful audio certification. A public voice marker must bind all files and
pass, on a controlled host:

1. snapshot revision, per-file SHA-256, total size, and license validation;
2. valid finite PCM at the declared sample rate, with duration and clipping
   bounds;
3. intelligibility measured by a pinned ASR model and normalized word error
   rate over English plus each claimed language;
4. speaker similarity for cloning models, using consented reference fixtures;
5. repeatability under a pinned seed and sampling profile;
6. time to first audio, real-time factor, peak RSS, and CPU utilization;
7. exact same-input comparisons with the upstream reference runtime; and
8. explicit voice-consent, impersonation, and production-use documentation.

The initial target is a deterministic Qwen3-TTS CustomVoice English sample.
The second target adds multilingual samples. Voice cloning remains a separate
capability and qualification rather than being implied by ordinary TTS.

## Sources

- <https://huggingface.co/Kwaipilot/KAT-Coder-V2.5-Dev>
- <https://huggingface.co/bartowski/Kwaipilot_KAT-Coder-V2.5-Dev-GGUF>
- <https://github.com/QwenLM/Qwen3-TTS>
- <https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice>
- <https://github.com/studio-dots-ai/dots.tts>
- <https://huggingface.co/dots-studio/dots.tts-mf>
