# Companion code quality check

MFCQI 0.7.0 analyzers with JavaParser modern syntax enabled; zero parse failures across the three source modules.
This is method-level diagnosis, not a corrected aggregate CLI score. Compact record constructors are not reported by this analyzer.

| Method | Before | After |
| --- | --- | --- |
| SpringIntegration.java: kind | 23 | 5 |
| SpringIntegration.java: unavailableReason | — | 3 |
| SpringIntegration.java: toolKind | — | 2 |
| ModelVisionQualificationRegistry.java: entry | 3 | 0 |
| ModelVisionQualificationRegistry.java: files | — | 1 |
| ModelVisionQualificationRegistry.java: datasets | — | 1 |
| ModelVisionQualificationRegistry.java: performance | — | 1 |
| DemoScriptGenerator.java: typeFor | 13 | 12 |
| ModelJarVisionRuntime.java: answer | 12 | 12 |

The Spring routing responsibilities are separated, the qualification parser reads each evidence cohort in its own helper,
and a duplicated unreachable decision-demo check is removed. No inference kernels or timer boundaries changed.
The registry still has a high-coupling heuristic finding because it assembles typed artifact, dataset, and performance records.

Raw before/after TSV files and source/analyzer hashes are in this directory. Before is the first companion commit;
the after source is bound by per-file hashes in `provenance.json`.
