#!/usr/bin/env bash
# Control: main on the seeds held alone on head, one worker, the normal budget, alone on the machine. Says whether the machine or the code moved them.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
SC=$WT/.claude/worktrees/lane-cost-scratch
OUT=$WT/tmp/merge-lane/cost; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar' }).Count" | tr -d '\r'; }
cd "$SC" || exit 2
git checkout -q --detach 3ffeb0519 || exit 2
waited=0; quiet=0
while [ "$quiet" -lt 2 ]; do
  if [ "$(others)" = 0 ]; then quiet=$((quiet+1)); else quiet=0; fi
  [ "$quiet" -ge 2 ] && break
  [ "$waited" -ge 1500 ] && { echo "BUSY after $waited s: control not run"; exit 3; }
  sleep 20; waited=$((waited+20))
done
echo "== control start $(date '+%H:%M:%S') after $waited s"
./gradlew --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=183,555,673,768 -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzCold=true -PholdFuzzOut="$OUT/control-main-report.txt" --offline --console=plain > "$OUT/control-main.gradle.txt" 2>&1
echo "control exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
grep -E '^SEED|COUNTERS_SEED' "$OUT/control-main-report.txt" | sed 's/ islands=.*//' | cut -c1-120
