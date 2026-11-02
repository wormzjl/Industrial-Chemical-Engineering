#!/bin/sh
# Replays cold seed 183 alone on head and on main, one after the other, and notes other test workers.
H=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/gates-head-scratch
B=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/gates-base-scratch
F=$H/tools/unified-thermo-port-forms/hold-fuzz-cold/hold-fuzz.init.gradle
export JAVA_OPTS=-Xshare:off
others(){ powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain' }).Count" | tr -d '\r'; }
one(){ side=$1; wt=$2; echo "== $side start $(date '+%H:%M:%S') other test workers: $(others)"
  cd "$wt" || exit 1
  ./gradlew --no-configuration-cache --init-script "$F" test --tests '*HoldFuzzTest' -PholdFuzzSeeds=183 -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=false -PholdFuzzCold=true -PholdFuzzOut=$H/tmp/merge-lane/$side/replay-183-report.txt --offline --console=plain > $H/tmp/merge-lane/$side/replay-183.gradle.txt 2>&1
  echo "$side exit=$? end $(date '+%H:%M:%S') other test workers: $(others)"
  grep -E "^SEED" $H/tmp/merge-lane/$side/replay-183-report.txt | sed 's/ islands=.*//' | cut -c1-110
  grep -E "^COUNTERS" $H/tmp/merge-lane/$side/replay-183-report.txt | cut -c1-210
  grep -E "HELD tick" $H/tmp/merge-lane/$side/replay-183-report.txt | cut -c1-90; }
one head "$H"
one base "$B"
