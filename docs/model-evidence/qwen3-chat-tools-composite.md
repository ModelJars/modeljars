# Withdrawn Qwen3 chat/tools routing experiment

The previously published `org.modeljars.composite:qwen3-chat-tools` artifact depends on two exact
qualified markers:

- Qwen3 0.6B Q4_0 for ordinary chat and tool-result narration;
- Qwen3 1.7B Q8_0 for tool selection and argument generation.

The recipe shares canonical semantic history, not KV-cache tensors. The chat member receives prose history
with tool results expressed as ordinary user messages. The tool member receives only the current
tool-selection turn. It routes automatically from the presence of declared tools and returns tool
results to chat for narration; explicit `chat` and `tool-use` tasks remain available as overrides.
Each physical runtime retains its own exact prompt-prefix and KV lineage.

Three fresh control JVMs and three fresh composite JVMs ran the same six-turn protocol in
counterbalanced order on a dedicated eight-vCPU AMD EPYC-Milan host with Temurin 25.0.4.1. All 36
turns passed. The single Qwen3 1.7B control had a 53.166-second median; the composite had a
35.151-second median, improving end-to-end latency by 33.88% against the predeclared 5% gate.

Median peak RSS increased from 2,404,032 KiB to 3,270,444 KiB, or 36.04%, because both model files
remain resident. This is useful routing evidence, but it does not qualify a hybrid model: it neither
shares nor translates cache state between the members.

The catalog evidence URL named Models revision `e4d130dd8c5986e6cef6d7ff5cb7d3533a5ceb6b`, but the
report was first committed later. Because the public record was not retrievable at the pinned
revision and the implementation did not satisfy shared-state composition, the entry is withdrawn
from the qualified catalog. The artifact remains historical; it is not advertised as a current
qualified virtual model.
