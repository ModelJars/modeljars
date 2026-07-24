package org.modeljars;

/** Evidence-backed RAG usage level for an exact model artifact and execution path. */
public enum RagUseCaseTier {
  /** The artifact did not pass the production qualification policy. */
  UNQUALIFIED,

  /** The model can answer directly while preserving the benchmark's quality bar. */
  GENERATIVE_RAG,

  /** The model is production-usable with the Models grounding and extractive fallback policy. */
  GUARDED_RAG
}
