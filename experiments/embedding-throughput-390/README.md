# Released embedding consumer repeat for Models #390

This standalone application compiles against published ModelJars artifacts. It calls
`ModelJars.openEmbeddingRuntime` with the default backend selection and a verified offline cache.
It does not inject kernels, substitute backend JARs, or depend on a sibling source checkout.
Run it only on an explicitly provisioned Linux benchmark worker; local compilation is safe.

Build each consumer independently, retaining its lockfile and distribution:

```sh
./gradlew -p experiments/embedding-throughput-390 installDist --write-locks -PsdkVersion=0.1.76
# After the new SDK is actually available on Maven Central, repeat with its published version.
```

Run `ReleasedEmbeddingConsumer MANIFEST CASE CACHE OUTPUT install` once per model to populate the
cache through the public installer. `measure` then requires the verified cached artifact. Each
report includes the exact artifact and classpath hashes, manifest hash, JVM arguments, all warmup
and measured samples, output vector, and a hash of its big-endian float bits. Load time includes
cache verification after the artifact has already been read for hashing; it is not cold disk I/O.

`workloads.json` freezes the inputs from Models commit
`f0e76530493d5664d9fa8b4d3484d3c1177de455`. The six latency cases retain their original tokenizer
IDs, exact source-report hashes, five warmups and eleven measured repetitions. Eight oracle cases
per artifact retain the pinned llama.cpp vectors and reference/probe hashes. Oracle timing is
diagnostic only and must not enter latency comparisons. The executable enforces the existing
catalog cosine floor of 0.999 and separately reports memory's proposed 0.99999 threshold.

For each latency case, run old/new/old in fresh JVMs on the same idle dedicated worker with the
same JVM/thread settings. Retain every command, log and report, compare the gain to baseline drift,
and compare actual output vectors across releases. Report failures and inconclusive differences.
Do not infer bulk-ingestion throughput, retrieval accuracy, an SLA, or cold-load performance from
these synthetic single-input measurements. Provisioning, deadlines, costs and cleanup receipts
belong in the experiment's control record before a worker is created.
