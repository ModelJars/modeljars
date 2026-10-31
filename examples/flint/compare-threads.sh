#!/usr/bin/env bash
# Run on the affected machine; each arm loads the same pinned model and retains its full log.
set -euo pipefail
DEMO=${1:-ggml-org-flint-chat-demo.java}
RESULTS=${2:-flint-thread-comparison-$(date -u +%Y%m%dT%H%M%SZ)}
mkdir "$RESULTS"
{ uname -a; lscpu; free -h; java -version; } >"$RESULTS/host.txt" 2>&1
sha256sum "$DEMO" >"$RESULTS/demo.sha256"
# Repeated prompts are explicitly labelled: compare cold and warm measurements separately.
for threads in 4 8 16 32; do
  echo "Measuring $threads native workers; log: $RESULTS/threads-$threads.log"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dmodels.native.kernels.threads=$threads -Dmodeljars.demo.runs=3 -Dmodeljars.demo.diagnostics=true" \
    jbang "$DEMO" 'What is the capital of France? Reply with only the city name.' \
    2>&1 | tee "$RESULTS/threads-$threads.log"
done
