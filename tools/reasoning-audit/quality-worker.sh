#!/usr/bin/env bash
# Dedicated disposable EC2 worker only; never invoke on a developer machine.
set -euo pipefail
INPUT=${1:?manifest}
WORK=/work/quality
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
cp "$WORK/protocol.json" "$WORK/classpath.json" "$OUT/"
curl -fL --retry 3 --max-time 900 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz' -o "$WORK/jdk.tar.gz"
printf '%s  %s\n' 69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f "$WORK/jdk.tar.gz" | sha256sum -c -
tar -xzf "$WORK/jdk.tar.gz" -C "$WORK/jdk" --strip-components=1
export OLLAMA_MODELS=/work/ollama
mkdir -p "$OLLAMA_MODELS"
curl -fL --retry 3 --max-time 600 https://ollama.com/install.sh -o "$WORK/install-ollama.sh"
OLLAMA_VERSION=0.40.13 sh "$WORK/install-ollama.sh" >"$OUT/ollama-install.log" 2>&1
runuser -u root -- ollama serve >"$OUT/ollama-server.log" 2>&1 &
for n in $(seq 1 60); do curl -fsS http://127.0.0.1:11434/api/version >"$OUT/ollama-version.json" && break; sleep 2; done
jq -e '.version == "0.40.13"' "$OUT/ollama-version.json"
SHARD=$(jq -er .shard "$INPUT")
cp "$WORK/shard-$SHARD.txt" "$OUT/planned-models.txt"
while IFS= read -r id; do
  [[ "$id" =~ ^[a-z0-9_]+$ ]] || exit 2
  CACHE=$WORK/cache/$id
  mkdir -p "$CACHE"
  rc=0
  timeout --signal=TERM --kill-after=30 2400 "$WORK/jdk/bin/java" -Xmx16g --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
    -Dmodels.native.kernels.threads=8 -Dmodels.purejava.maxContextLength=2048 -Dmodeljars.cache="$CACHE" -cp "$WORK/classes:$WORK/lib/*" \
    QualityCatalogProbe "$WORK/jobs/$id.json" "$OUT/$id.json" >"$OUT/$id.log" 2>&1 || rc=$?
  printf '%s\n' "$rc" >"$OUT/$id.exit-code"
  upload
  if [[ "$rc" == 0 ]]; then
    rc=0
    timeout --signal=TERM --kill-after=30 2400 python3 "$WORK/quality-reference.py" "$OUT/$id.json" "$CACHE" >"$OUT/$id.reference.log" 2>&1 || rc=$?
    printf '%s\n' "$rc" >"$OUT/$id.reference.exit-code"
    upload
    # Fail promptly if the independent arm cannot run; do not produce a candidate-only campaign.
    [[ "$rc" == 0 ]] || exit "$rc"
  else
    exit "$rc"
  fi
  rm -rf -- "$CACHE"
done <"$WORK/shard-$SHARD.txt"
printf '%s\n' complete >"$OUT/shard.complete"
