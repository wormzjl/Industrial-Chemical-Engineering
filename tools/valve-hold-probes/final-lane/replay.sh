#!/usr/bin/env bash
# Replays alone, batched, the seeds of class F not in the base's class F. Notes other test workers before and after.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
cd "$WT" || exit 2
OUT=$WT/tmp/merge-lane/head; FORMS=$WT/tools/unified-thermo-port-forms
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar' }).Count" | tr -d '\r'; }
one(){ label=$1; seeds=$2; harness=$3; shift 3
  echo "== $label $seeds start $(date '+%H:%M:%S') other JVMs: $(others)"
  ./gradlew --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=$seeds -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=false "$@" -PholdFuzzOut="$OUT/replay-$label-report.txt" --offline --console=plain > "$OUT/replay-$label.gradle.txt" 2>&1
  echo "$label exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
  grep -E '^SUMMARY' "$OUT/replay-$label-report.txt" | cut -c1-120
  grep -E '^SEED' "$OUT/replay-$label-report.txt" | sed 's/ islands=.*//' | cut -c1-110
}
one cold 6,118,183,555,673,768,994 "$FORMS/hold-fuzz-cold" -PholdFuzzCold=true
one bundled 241,707 "$FORMS/hold-fuzz-bundled"
