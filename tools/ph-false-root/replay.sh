#!/usr/bin/env bash
# The one new budget-class hold of the final lane, alone on the machine, one worker, with its counters.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
OUT=$WT/tmp/ph-false-root/lane/head; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar|net.neoforged.devlaunch|runClient|clientRunProgramArgs' }).Count" | tr -d '\r'; }
echo "== replay start $(date '+%H:%M:%S') other JVMs: $(others)"
./gradlew --offline --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=118 -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzOut="$OUT/replay-118-report.txt" -PholdFuzzCold=true --console=plain > "$OUT/replay-118.gradle.txt" 2>&1
echo "replay exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
grep -E '^SEED|^  HELD|^SUMMARY' "$OUT/replay-118-report.txt" | sed 's/ islands=.*crystalKg/ crystalKg/' | cut -c1-140
