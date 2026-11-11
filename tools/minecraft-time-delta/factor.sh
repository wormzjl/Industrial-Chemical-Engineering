#!/usr/bin/env bash
# The hold-fuzz quick check or campaign on the built time factor (W2's -PholdFuzzTimeScale), no probe, alone on the machine.
#   bash tools/minecraft-time-delta/factor.sh <label> <seeds> "<dists>" <factor> [-P...]
#   dists: cold bundled defaults (any of them, in quotes). Reports: tmp/time-scale/<label>-<dist>-report.txt, and for a
#   label starting "campaign" also the merge lane's names (campaign-cold-random-report.txt, ...) in tmp/time-scale/<label>/,
#   so tools/merge-lane/compare.mjs reads it against a lane folder.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
label=$1; seeds=$2; dists=$3; factor=$4; shift 4
OUT=$WT/tmp/time-scale; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
export JAVA_OPTS=-Xshare:off
for dist in $dists; do
  case $dist in cold) harness=$FORMS/hold-fuzz-cold; extra="-PholdFuzzCold=true -PholdFuzzDefaults=false"; lane=campaign-cold-random;;
    bundled) harness=$FORMS/hold-fuzz-bundled; extra="-PholdFuzzDefaults=false"; lane=campaign-bundled-random;;
    defaults) harness=$FORMS/hold-fuzz-bundled; extra="-PholdFuzzDefaults=true"; lane=campaign-bundled-defaults;; *) exit 2;; esac
  report=$OUT/$label-$dist-report.txt; log=$OUT/$label-$dist.gradle.txt; start=$(date +%s)
  ./gradlew --offline --console=plain --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
    -PholdFuzzSeeds=$seeds -PholdFuzzThreads=${THREADS:-8} -PholdFuzzCounters=true -PholdFuzzTimeScale=$factor -PholdFuzzOut="$report" $extra "$@" > "$log" 2>&1
  echo "== $label $dist factor=$factor exit=$? seconds=$(( $(date +%s) - start ))"
  bash "$CLASSIFY" "$report" > /dev/null 2>&1
  case $label in campaign*) mkdir -p "$OUT/$label"; cp "$report" "$OUT/$label/$lane-report.txt"; cp "$report.classes.txt" "$OUT/$label/$lane-report.txt.classes.txt" 2>/dev/null;; esac
  grep -E '^(SUMMARY|COUNTERS|CLASS)' "$report" | cut -c1-300
done
