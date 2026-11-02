#!/usr/bin/env bash
# The seeds clean on base and held on head, one worker, one after another, alone on the machine, with per-seed counters.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
cd "$WT" || exit 2
OUT=$WT/tmp/merge-lane/head; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar' }).Count" | tr -d '\r'; }
echo "== serial start $(date '+%H:%M:%S') other JVMs: $(others)"
./gradlew --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=183,555,673,768 -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzCold=true -PholdFuzzOut="$OUT/replay-serial-report.txt" --offline --console=plain > "$OUT/replay-serial.gradle.txt" 2>&1
echo "serial exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
grep -E '^SEED' "$OUT/replay-serial-report.txt" | sed 's/ islands=.*//' | cut -c1-110
