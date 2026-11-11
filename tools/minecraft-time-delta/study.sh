#!/usr/bin/env bash
# One study run of single seeds (TimeScaleStudyTest): step trace, stalls and hot frames per seed.
#   bash tools/minecraft-time-delta/study.sh <label> <bundled|cold> <seeds> [-PprobeTimeScale=72 -PprobeWallNanos=5000000000 ...]
# Needs patches/probe-time-scale.patch applied. Output: tmp/time-scale/study-<label>.txt (and .gradle.txt).
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
label=$1; dist=$2; seeds=$3; shift 3
OUT=$WT/tmp/time-scale; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
case $dist in cold) harness=$FORMS/hold-fuzz-cold; extra="-PholdFuzzCold=true";; bundled) harness=$FORMS/hold-fuzz-bundled; extra="";; *) exit 2;; esac
export JAVA_OPTS=-Xshare:off
start=$(date +%s)
./gradlew --offline --console=plain --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" \
  --init-script "$WT/tools/minecraft-time-delta/probe.init.gradle" test --tests '*TimeScaleStudyTest' \
  -PholdFuzzDefaults=false -PholdFuzzVerbose=false -PstudySeeds=$seeds -PstudyOut="$OUT/study-$label.txt" $extra "$@" > "$OUT/study-$label.gradle.txt" 2>&1
echo "== study $label exit=$? seconds=$(( $(date +%s) - start ))"
grep -E '^(==== STUDY|HELD|EXCEPTION)' "$OUT/study-$label.txt" | cut -c1-330
