#!/usr/bin/env bash
# The traced seeds at the normal budget, alone, one worker: the commit before the fix round, then head, in one sitting.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
SC=$WT/.claude/worktrees/lane-cost-scratch
OUT=$WT/tmp/merge-lane/cost; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar' }).Count" | tr -d '\r'; }
cd "$SC" || exit 2
for c in c34d7bf2e 321b8d1d3; do
  git checkout -q --detach "$c" || exit 2
  waited=0; quiet=0
  while [ "$quiet" -lt 2 ]; do
    if [ "$(others)" = 0 ]; then quiet=$((quiet+1)); else quiet=0; fi
    [ "$quiet" -ge 2 ] && break
    [ "$waited" -ge 1500 ] && { echo "BUSY after $waited s: $c not run"; exit 3; }
    sleep 20; waited=$((waited+20))
  done
  echo "== $c start $(date '+%H:%M:%S') after $waited s"
  ./gradlew --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=673,183 -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzCold=true -PholdFuzzOut="$OUT/pair-$c-report.txt" --offline --console=plain > "$OUT/pair-$c.gradle.txt" 2>&1
  echo "$c exit=$? end $(date '+%H:%M:%S') other JVMs: $(others)"
  grep -E '^SEED|COUNTERS_SEED|HELD' "$OUT/pair-$c-report.txt" | sed 's/ islands=.*//' | cut -c1-100 | head -14
done
