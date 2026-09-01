# Advanced inference pipeline access

`ModelJars.openRuntime(...)` resolves and verifies a qualified artifact, selects
its qualified backend and chat template, and returns one owning
`ModelJarRuntime`. The runtime provides both the simple text-generation view and
the complete Models inference pipeline.

```java
import static org.modeljars.catalog.Qwen3_0_6b_Q4_0.MODEL;

import com.integrallis.models.api.InferenceContextWindow;
import com.integrallis.models.api.ModelMetadata;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.Tokenizer;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.util.List;
import org.modeljars.ModelJars;

try (var runtime = ModelJars.openRuntime(MODEL)) {
  InferencePipeline pipeline = runtime.pipeline();
  ModelMetadata metadata = runtime.metadata();
  Tokenizer tokenizer = runtime.tokenizer();
  InferenceContextWindow context = runtime.contextWindow();

  ModelPrompt prompt = runtime.chatTemplate().render(
      List.of(ChatMessage.user("Name one JVM language.")));
  int[] tokens = pipeline.tokenize(prompt);

  pipeline.resetContext();
  float[] logits = pipeline.prefill(tokens, 0);
  int nextToken = argmax(logits);
  String tokenText = tokenizer.decode(nextToken);
  logits = pipeline.forward(nextToken, tokens.length);
}
```

The accessors have distinct roles:

- `runtime.chatTemplate()` is the template proven by the selected qualification.
- `runtime.metadata()` reports model architecture and its declared maximum
  context length.
- `runtime.tokenizer()` returns the loaded model's read-only tokenizer.
- `runtime.contextWindow()` reports the active backend capacity and, for a
  rewindable backend, its next token position.
- `runtime.pipeline()` supports structured tokenization, high-level generation,
  prefill, forward-pass logits, reset, checkpoint, and rewind.
- `runtime.model()` is the compatible `TextGenerationModel` view of the same
  pipeline.

`ModelPrompt` must remain structured through tokenization. Template-owned
control segments are recognized as special tokens, while user text that merely
spells a control token remains ordinary text. ModelJars 0.1.6 fixes the previous
managed-wrapper path that flattened a `ModelPrompt` before generation.

The runtime owns the pipeline and loaded backend. Close only the
`ModelJarRuntime`; its `close()` method releases all model resources exactly
once. Do not interleave direct backend access outside the pipeline. Low-level
pipeline operations invalidate the high-level prompt-prefix cache before they
change context state, so later calls to `generate(...)` cannot reuse a stale
prefix.

## Qualified embedding access

Embedding markers use the same install, digest verification, and ownership
contract. The qualification selects the backend, pooling, normalization, and
vector width; application code supplies only the marker and text:

```java
import static org.modeljars.catalog.Ggml_Org_Embeddinggemma_300m_Gguf_Q8_0.MODEL;

import org.modeljars.ModelJars;

try (var embeddings = ModelJars.openEmbedding(MODEL)) {
  float[] vector = embeddings.embed("Where is the maintenance schedule?");
}
```

Use `ModelJars.openEmbeddingRuntime(MODEL)` when provenance must remain beside
the loaded model. Its `descriptor()` identifies the exact marker and artifact,
while `qualification()` exposes the reference-equivalence evidence used to
select the execution policy. The returned runtime owns the embedding backend
and must be closed.

## Qualified reranking access

Reranker markers bind a cross-encoder to numerical agreement, exact ordering, and a controlled
latency envelope. Applications supply a query and candidate documents; scores are meaningful for
ordering documents within that query.

```java
import static org.modeljars.catalog.Cstr_Ms_Marco_Minilm_L6_V2_Gguf_Q4_K_Imatrix_G7c_F7.MODEL;

var candidates = List.of("Berlin has 3.5 million residents.", "Paris is in France.");
try (var reranker = ModelJars.openReranker(MODEL)) {
  var ranked = reranker.rerank("How many people live in Berlin?", candidates);
}
```

Use `openRerankingRuntime` to retain the descriptor and exact qualification evidence beside the
owned `RerankingModel`.

The Models documentation describes the complete contract in
[Inference Pipeline](https://integrallis.github.io/models/docs/models/current/inference-pipeline.html).
