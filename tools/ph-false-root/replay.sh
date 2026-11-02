#!/usr/bin/env bash
# The new budget-class holds of a final lane, batched in one run, alone on the machine, one worker, with their counters.
# Usage: bash replay.sh <comma-separated seeds>. Waits up to 40 minutes for other test, client and probe JVMs to end, and
# prints how many ran beside it. Reports in tmp/ph-false-root/lane/head/, or $LANE_OUT/head.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
SEEDS=${1:?usage: replay.sh <seeds>}
OUT=$WT/${LANE_OUT:-tmp/ph-false-root/lane}/head; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar|net.neoforged.devlaunch|runClient|clientRunProgramArgs|com.wormzjl[.a-z]*[.][A-Za-z]*Probe' }).Count" | tr -d '\r'; }
for wait in $(seq 1 80); do [ "$(others)" = 0 ] && break; sleep 30; done
echo "== replay start $(date '+%H:%M:%S') seeds $SEEDS other JVMs: $(others)"
./gradlew --offline --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds="$SEEDS" -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzOut="$OUT/replay-report.txt" -PholdFuzzCold=true --console=plain > "$OUT/replay.gradle.txt" 2>&1
echo "replay exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
grep -E '^SEED|^  HELD|^SUMMARY' "$OUT/replay-report.txt" | sed 's/ islands=.*crystalKg/ crystalKg/' | cut -c1-140
