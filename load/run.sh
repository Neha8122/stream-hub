#!/usr/bin/env bash
# Runs load/home.js against the kind cluster and records, every 10 s, how
# many pods each deployment is running, how busy they are, and restarts.
#   load/run.sh [rate] [out-dir]
set -euo pipefail
cd "$(dirname "$0")/.."
RATE="${1:-150}"
OUT="${2:-load/results}"
mkdir -p "$OUT"
NS="-n stream-hub"

(
  while true; do
    ready=$(kubectl $NS get deploy --no-headers | awk '{split($2,r,"/"); n=$1; sub(/-service$/,"",n); printf "%s=%s ", n, r[1]}')
    # CPU per deployment, summed over its pods, in millicores
    cpu=$(kubectl $NS top pods --no-headers 2>/dev/null | awk '{n=$1; sub(/-[a-z0-9]+-[a-z0-9]+$/, "", n); sub(/-service$/,"",n); c[n]+=$2} END {for (k in c) printf "%s=%dm ", k, c[k]}')
    restarts=$(kubectl $NS get pods --no-headers | awk '{s+=$4} END {print s+0}')
    echo "$(date +%T) pods: $ready| cpu: $cpu| restarts: $restarts"
    sleep 10
  done
) > "$OUT/timeline.txt" &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT

k6 run --quiet -e BASE=http://localhost:8280 -e RATE="$RATE" -e RAMP="${RAMP:-1m}" -e HOLD="${HOLD:-3m}" --summary-export "$OUT/summary.json" load/home.js \
  | tee "$OUT/k6.txt"
