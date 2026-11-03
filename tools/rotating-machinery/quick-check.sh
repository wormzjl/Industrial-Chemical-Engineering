#!/usr/bin/env bash
# The batch's quick check (AGENTS.md, "Gradle, measurement and verification": 200 seeds, every distribution, 8 workers):
# cold random, bundled random and bundled defaults on this folder's harness copies, one Gradle invocation at a time.
# Usage: bash tools/rotating-machinery/quick-check.sh <label> [extra -P flags...]
#   F1 (the head as committed): no flags.   F2: -PholdFuzzCornerLaw=true.   Q3 (the centrifugal variant): -PholdFuzzMachines=true.
# Reports go to tmp/rotating-machinery/quick/<label>/, or $QUICK_OUT/<label>; SEEDS overrides the 1-200 range; ONLY runs one
# harness and distribution, as a replay alone (ONLY=hold-fuzz-cold-random SEEDS=6).
set -u
WT=$(git rev-parse --show-toplevel)
label=${1:?usage: quick-check.sh <label> [extra -P flags]}; shift
HERE=$WT/tools/rotating-machinery
OUT=$WT/${QUICK_OUT:-tmp/rotating-machinery/quick}/$label; mkdir -p "$OUT"
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
cd "$WT" || exit 2
export JAVA_OPTS=-Xshare:off
# Test and client JVMs already running (lane.sh's check): a campaign never overlaps another campaign or a suite.
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count"; }
run(){ local harness=$1 dist=$2; shift 2; local d=false; [ "$dist" = defaults ] && d=true
  [ -z "${ONLY:-}" ] || [ "$ONLY" = "$harness-$dist" ] || return 0
  local n; n=$(busy | tr -d '\r'); [ "${n:-0}" = 0 ] || echo "WARNING: $n test or client JVMs running at $(date '+%H:%M:%S')"
  local start; start=$(date +%s)
  ./gradlew --offline --console=plain --no-configuration-cache --init-script "$HERE/$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
    -PholdFuzzSeeds=${SEEDS:-1-200} -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=$d -PholdFuzzOut="$OUT/$harness-$dist-report.txt" "$@" \
    > "$OUT/$harness-$dist.gradle.txt" 2>&1
  echo "== $label $harness $dist exit=$? seconds=$(( $(date +%s) - start ))"
  bash "$CLASSIFY" "$OUT/$harness-$dist-report.txt" > /dev/null; grep -E '^SUMMARY' "$OUT/$harness-$dist-report.txt"; }
echo "== $label start $(date '+%H:%M:%S') at $(git rev-parse --short HEAD) flags: $*"
run hold-fuzz-cold random -PholdFuzzCold=true "$@"
run hold-fuzz-bundled random "$@"
run hold-fuzz-bundled defaults "$@"
echo "== $label done $(date '+%H:%M:%S')"
