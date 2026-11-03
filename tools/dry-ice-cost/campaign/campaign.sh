#!/usr/bin/env bash
# The one full campaign of the dry ice form: cold random, bundled random, bundled defaults, 1000 seeds, 8 workers, alone on
# the machine (the recipe of tools/heat-exchanger-design/merge-lane/lane.sh). Run from the branch's worktree root.
# Usage: bash tools/dry-ice-cost/campaign/campaign.sh [out folder, default tmp/dry-ice-symptoms/campaign]
set -u
WT=$(pwd)
OUT=$WT/${1:-tmp/dry-ice-symptoms/campaign}; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
export JAVA_OPTS=-Xshare:off
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|DryIceProbe|net.neoforged.devlaunch|runClient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count"; }
fuzz(){ local label=$1 harness=$2 dists=$3 extra=$4
  for dist in $dists; do local d=false; [ "$dist" = defaults ] && d=true
    local log="$OUT/$label-$dist.gradle.txt" start; start=$(date +%s)
    echo "== $label $dist start $(date '+%H:%M:%S'), other test JVMs: $(busy | tr -d '\r')"
    ./gradlew --offline --console=plain --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
      -PholdFuzzSeeds=1-1000 -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=$d -PholdFuzzOut="$OUT/$label-$dist-report.txt" $extra > "$log" 2>&1
    echo "exit=$? seconds=$(( $(date +%s) - start )), other test JVMs: $(busy | tr -d '\r')"
    bash "$CLASSIFY" "$OUT/$label-$dist-report.txt" | cut -c1-120
    echo "refused seeds: $(grep -c -E 'refused=[1-9]' "$OUT/$label-$dist-report.txt")"
  done; }
echo "campaign at $(git rev-parse --short HEAD), dirty files: $(git status --short | wc -l)"
fuzz campaign-cold "$FORMS/hold-fuzz-cold" random -PholdFuzzCold=true
fuzz campaign-bundled "$FORMS/hold-fuzz-bundled" "random defaults" ""
echo "campaign done $(date '+%H:%M:%S')"
