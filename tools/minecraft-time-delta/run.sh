#!/usr/bin/env bash
# The hold-fuzz quick check under the time-scale probe, one Gradle run per distribution, alone on the machine.
#   bash tools/minecraft-time-delta/run.sh <label> <seeds> "<dists>" [-PprobeTimeScale=72 -PprobeMaximumStep=360 ...]
#   dists: cold bundled defaults (any of them, in quotes)
# Needs patches/probe-time-scale.patch applied to the worktree. Reports: tmp/time-scale/<label>-<dist>-report.txt.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
label=$1; seeds=$2; dists=$3; shift 3
OUT=$WT/tmp/time-scale; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
export JAVA_OPTS=-Xshare:off
for dist in $dists; do
  case $dist in cold) harness=$FORMS/hold-fuzz-cold; extra="-PholdFuzzCold=true -PholdFuzzDefaults=false";;
    bundled) harness=$FORMS/hold-fuzz-bundled; extra="-PholdFuzzDefaults=false";;
    defaults) harness=$FORMS/hold-fuzz-bundled; extra="-PholdFuzzDefaults=true";; *) exit 2;; esac
  report=$OUT/$label-$dist-report.txt; log=$OUT/$label-$dist.gradle.txt; start=$(date +%s)
  ./gradlew --offline --console=plain --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" \
    --init-script "$WT/tools/minecraft-time-delta/probe.init.gradle" test --tests '*HoldFuzzTest' \
    -PholdFuzzSeeds=$seeds -PholdFuzzThreads=${THREADS:-8} -PholdFuzzCounters=true -PholdFuzzOut="$report" $extra "$@" > "$log" 2>&1
  echo "== $label $dist exit=$? seconds=$(( $(date +%s) - start ))"
  bash "$CLASSIFY" "$report" > /dev/null 2>&1
  grep -E '^(SUMMARY|CLASS)' "$report" | cut -c1-300
done
