#!/usr/bin/env bash
# Replacement final lane: waits until no other Gradle or test JVM runs, then one step at a time; stops at the first failed step.
WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/column-operation-5c3005
export HEAD_WT=$WT RUNID=column-final-lane-1
cd "$WT" || exit 2
OUT=$WT/tmp/merge-lane/head; mkdir -p "$OUT"
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient|gradle-wrapper.jar' } | Measure-Object | Select-Object -ExpandProperty Count" | tr -d '\r'; }
waited=0; quiet=0
while [ "$quiet" -lt 2 ]; do
  n=$(busy); if [ "${n:-0}" = 0 ]; then quiet=$((quiet+1)); else quiet=0; fi
  [ "$quiet" -ge 2 ] && break
  [ "$waited" -ge 2400 ] && { echo "BUSY: other Gradle or test JVMs still run after $waited s"; exit 3; }
  sleep 20; waited=$((waited+20))
done
echo "== machine free after $waited s, $(date '+%H:%M:%S'), head $(git rev-parse --short HEAD)"
ok(){ tail -1 "$1" | grep -q '^exit=0' ; }
for s in test regression gametest campaign; do
  bash tools/valve-hold-probes/final-lane/lane.sh head $s
  case $s in
    test) ok "$OUT/gates.gradle.txt" || { echo "STOP: test failed"; exit 1; };;
    regression) ok "$OUT/chain-100.gradle.txt" || { echo "STOP: chain-100 failed"; exit 1; };;
    gametest) ok "$OUT/gametests.gradle.txt" || { echo "STOP: GameTests failed"; exit 1; };;
  esac
done
echo "== lane finished $(date '+%H:%M:%S')"
