package org.modeljars;

import java.util.Objects;
import java.util.Optional;

/**
 * GGUF model dimensions used for discovery and deterministic resource estimates.
 *
 * @param parameterCount number of model parameters
 * @param contextLength maximum context length in tokens
 * @param embeddingLength hidden-state width
 * @param blockCount total transformer block count
 * @param attentionHeadCount query attention-head count
 * @param keyValueHeadCount key/value attention-head count
 * @param feedForwardLength feed-forward hidden-state width
 * @param expertCount mixture-of-experts expert count
 * @param expertUsedCount experts selected for each token
 * @param keyLength width of each key head
 * @param valueLength width of each value head
 * @param attentionBlockCount blocks that allocate KV-cache state
 */
public record ModelDimensions(
    Optional<Long> parameterCount,
    Optional<Integer> contextLength,
    Optional<Integer> embeddingLength,
    Optional<Integer> blockCount,
    Optional<Integer> attentionHeadCount,
    Optional<Integer> keyValueHeadCount,
    Optional<Integer> feedForwardLength,
    Optional<Integer> expertCount,
    Optional<Integer> expertUsedCount,
    Optional<Integer> keyLength,
    Optional<Integer> valueLength,
    Optional<Integer> attentionBlockCount) {
  /** Validates that every advertised dimension is positive. */
  public ModelDimensions {
    parameterCount = positiveLong(parameterCount, "parameterCount");
    contextLength = positiveInt(contextLength, "contextLength");
    embeddingLength = positiveInt(embeddingLength, "embeddingLength");
    blockCount = positiveInt(blockCount, "blockCount");
    attentionHeadCount = positiveInt(attentionHeadCount, "attentionHeadCount");
    keyValueHeadCount = positiveInt(keyValueHeadCount, "keyValueHeadCount");
    feedForwardLength = positiveInt(feedForwardLength, "feedForwardLength");
    expertCount = positiveInt(expertCount, "expertCount");
    expertUsedCount = positiveInt(expertUsedCount, "expertUsedCount");
    keyLength = positiveInt(keyLength, "keyLength");
    valueLength = positiveInt(valueLength, "valueLength");
    attentionBlockCount = positiveInt(attentionBlockCount, "attentionBlockCount");
  }

  /**
   * Creates dimensions for metadata that predates explicit key, value, and attention-block sizes.
   *
   * @param parameterCount number of model parameters
   * @param contextLength maximum context length in tokens
   * @param embeddingLength hidden-state width
   * @param blockCount total transformer block count
   * @param attentionHeadCount query attention-head count
   * @param keyValueHeadCount key/value attention-head count
   * @param feedForwardLength feed-forward hidden-state width
   * @param expertCount mixture-of-experts expert count
   * @param expertUsedCount experts selected for each token
   */
  public ModelDimensions(
      Optional<Long> parameterCount,
      Optional<Integer> contextLength,
      Optional<Integer> embeddingLength,
      Optional<Integer> blockCount,
      Optional<Integer> attentionHeadCount,
      Optional<Integer> keyValueHeadCount,
      Optional<Integer> feedForwardLength,
      Optional<Integer> expertCount,
      Optional<Integer> expertUsedCount) {
    this(
        parameterCount,
        contextLength,
        embeddingLength,
        blockCount,
        attentionHeadCount,
        keyValueHeadCount,
        feedForwardLength,
        expertCount,
        expertUsedCount,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Returns dimensions with every value unknown.
   *
   * @return empty model dimensions
   */
  public static ModelDimensions unknown() {
    return new ModelDimensions(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Estimates model-file bytes plus a conventional transformer KV cache.
   *
   * <p>The estimate is unavailable for architectures that do not advertise enough dimensions.
   *
   * @param contextTokens context-window size to estimate
   * @param precision precision of each KV-cache element
   * @param weightBytes model-weight storage in bytes
   * @return the lower-bound estimate, or empty when required dimensions are unavailable
   */
  public Optional<ModelMemoryEstimate> estimateMemory(
      int contextTokens, KvCachePrecision precision, long weightBytes) {
    if (contextTokens <= 0) {
      throw new IllegalArgumentException("contextTokens must be > 0");
    }
    if (weightBytes <= 0) {
      throw new IllegalArgumentException("weightBytes must be > 0");
    }
    Objects.requireNonNull(precision, "precision");
    contextLength.ifPresent(
        limit -> {
          if (contextTokens > limit) {
            throw new IllegalArgumentException(
                "contextTokens exceeds the advertised context length: " + limit);
          }
        });

    if (embeddingLength.isEmpty()
        || attentionBlockCount.isEmpty()
        || attentionHeadCount.isEmpty()) {
      return Optional.empty();
    }
    int embedding = embeddingLength.orElseThrow();
    int heads = attentionHeadCount.orElseThrow();
    int inferredHeadLength = embedding % heads == 0 ? embedding / heads : -1;
    if ((keyLength.isEmpty() || valueLength.isEmpty()) && inferredHeadLength < 0) {
      return Optional.empty();
    }
    int keyValueHeads = keyValueHeadCount.orElse(heads);
    int keyWidth = keyLength.orElse(inferredHeadLength);
    int valueWidth = valueLength.orElse(inferredHeadLength);

    long kvCacheBytes = contextTokens;
    kvCacheBytes = Math.multiplyExact(kvCacheBytes, attentionBlockCount.orElseThrow());
    kvCacheBytes = Math.multiplyExact(kvCacheBytes, keyValueHeads);
    kvCacheBytes = Math.multiplyExact(kvCacheBytes, Math.addExact(keyWidth, valueWidth));
    kvCacheBytes = Math.multiplyExact(kvCacheBytes, precision.bytesPerElement());

    return Optional.of(
        new ModelMemoryEstimate(
            contextTokens,
            weightBytes,
            kvCacheBytes,
            Math.addExact(weightBytes, kvCacheBytes),
            precision));
  }

  private static Optional<Integer> positiveInt(Optional<Integer> value, String name) {
    return Objects.requireNonNull(value, name)
        .map(
            number -> {
              if (number <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
              }
              return number;
            });
  }

  private static Optional<Long> positiveLong(Optional<Long> value, String name) {
    return Objects.requireNonNull(value, name)
        .map(
            number -> {
              if (number <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
              }
              return number;
            });
  }
}
