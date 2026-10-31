#!/usr/bin/env bash
set -euo pipefail
INPUT=${1:?manifest.json}
WORK=/work/reasoning
OUT=$WORK/results
mkdir -p "$OUT" "$WORK/jdk"
DEST=$(jq -er .resultsUri "$INPUT")
export AWS_DEFAULT_REGION=us-east-1
exec > >(tee -a "$OUT/worker.log" /dev/console) 2>&1
upload() { aws s3 cp "$OUT/" "$DEST/" --recursive --only-show-errors; }
cleanup() { rc=$?; trap - EXIT; printf '%s\n' "$rc" >"$OUT/worker.exit-code"; upload || true; shutdown -h now; exit "$rc"; }
trap cleanup EXIT
(while sleep 30; do upload || true; done) &
cp "$INPUT" "$OUT/manifest.json"
{ uname -a; lscpu; free -b; } >"$OUT/host.txt"
aws s3 cp "$(jq -er .payloadUri "$INPUT")" "$WORK/payload.tar.gz" --only-show-errors
printf '%s  %s\n' "$(jq -er .payloadSha256 "$INPUT")" "$WORK/payload.tar.gz" | sha256sum -c -
tar -xzf "$WORK/payload.tar.gz" -C "$WORK"
curl -fL --retry 3 --max-time 900 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz' -o "$WORK/jdk.tar.gz"
printf '%s  %s\n' 69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f "$WORK/jdk.tar.gz" | sha256sum -c -
tar -xzf "$WORK/jdk.tar.gz" -C "$WORK/jdk" --strip-components=1
JAVA=$WORK/jdk/bin/java
"$JAVA" -version >"$OUT/java-version.txt" 2>&1
sha256sum "$WORK"/lib/*.jar >"$OUT/runtime-jars.sha256"
SHARD=$(jq -er .shard "$INPUT")
HEAP=$(jq -r '.heap // "8g"' "$INPUT")
MAX_TOKENS=$(jq -r '.maxTokens // 64' "$INPUT")
CONTEXT=$(jq -r '.contextLength // 0' "$INPUT")
JVM_ARGS=("-Xmx$HEAP" "-Dmodeljars.demo.maxTokens=$MAX_TOKENS")
if (( CONTEXT > 0 )); then JVM_ARGS+=("-Dmodels.purejava.maxContextLength=$CONTEXT"); fi
cp "$WORK/shard-$SHARD.txt" "$OUT/planned-models.txt"
while IFS= read -r id; do
  [[ "$id" =~ ^[a-z0-9_]+$ ]] || exit 2
  CACHE=$WORK/cache/$id
  mkdir -p "$CACHE"
  date -u +%FT%TZ >"$OUT/$id.started"
  rc=0
  timeout --signal=TERM --kill-after=30 1200 "$JAVA" "${JVM_ARGS[@]}" --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
    -Dmodels.native.kernels.threads=8 -Dmodeljars.cache="$CACHE" -cp "$WORK/classes:$WORK/lib/*" \
    ReasoningCatalogProbe "$id" "$OUT/$id.json" >"$OUT/$id.log" 2>&1 || rc=$?
  printf '%s\n' "$rc" >"$OUT/$id.exit-code"
  date -u +%FT%TZ >"$OUT/$id.finished"
  upload
  # Only the per-job cache allocated above is removed; artifacts are verified afresh for every job.
  rm -rf -- "$CACHE"
done <"$WORK/shard-$SHARD.txt"
printf '%s\n' complete >"$OUT/shard.complete"
