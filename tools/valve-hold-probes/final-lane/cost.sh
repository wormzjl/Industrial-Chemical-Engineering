#!/usr/bin/env bash
# Trace of the two smallest worlds newly held on the wall budget: their work with the budget raised, at the fix round's solver commits.
# Counters only: one worker and no deadline make them independent of other load. Waits while two Gradle runs are on the machine.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
SC=$WT/.claude/worktrees/lane-cost-scratch
OUT=$WT/tmp/merge-lane/cost; mkdir -p "$OUT"; H=$WT/tools/unified-thermo-port-forms/hold-fuzz-cold
export JAVA_OPTS=-Xshare:off JAVA_TOOL_OPTIONS="-DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000"
clients(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'gradle-wrapper.jar' }).Count" | tr -d '\r'; }
cd "$WT" || exit 2
[ -d "$SC" ] || git worktree add --detach "$SC" HEAD > /dev/null || exit 2
cd "$SC" || exit 2
for c in "$@"; do
  git checkout -q --detach "$c" || exit 2
  while [ "$(clients)" -ge 2 ]; do sleep 30; done
  echo "== $c start $(date '+%H:%M:%S')"
  ./gradlew --no-configuration-cache --init-script "$H/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=673,183 -PholdFuzzThreads=1 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzCold=true -PholdFuzzOut="$OUT/$c-report.txt" --offline --console=plain > "$OUT/$c.gradle.txt" 2>&1
  echo "$c exit=$? end $(date '+%H:%M:%S')"
  grep -E '^SEED|COUNTERS_SEED' "$OUT/$c-report.txt" | sed 's/ islands=.*//' | cut -c1-120
done
