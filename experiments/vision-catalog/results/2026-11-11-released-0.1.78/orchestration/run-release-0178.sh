#!/usr/bin/env bash
set -uo pipefail
output=/home/ubuntu/qual/released-consumers-0178-20261111
mkdir "$output.supervisor-started" || exit 2
date -u +%FT%TZ
timeout --signal=TERM --kill-after=30s 3600 \
  /home/ubuntu/qual/venv/bin/python /home/ubuntu/qual/released-tools-36ad63c-v2/run-release.py \
  --campaign /home/ubuntu/qual/released-0.5.19-214d53f-20261110/campaign \
  --payload /home/ubuntu/qual/release-probes-0178-9235ebf \
  --output "$output" \
  --gradle-wrapper /home/ubuntu/qual/released-tools-36ad63c-v2/gradlew \
  --java /usr/lib/jvm/temurin-25-jdk-amd64/bin/java \
  --modeljars-version 0.1.78 --models-version 0.5.19 --vectors-version 0.1.34
status=$?
printf '%s\n' "$status" > "$output.exit-code"
date -u +%FT%TZ
exit "$status"
