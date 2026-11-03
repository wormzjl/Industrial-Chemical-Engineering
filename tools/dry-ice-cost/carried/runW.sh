#!/bin/bash
# Scratch: the nine worlds, serial, raised budget, no JFR. runW.sh <classes folder name> <name prefix> [java options]
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run"
source ./env.sh
K="$1"; P="$2"; shift 2
export CLASSES="$RUN/$K" NOJFR=1
LOG="$RUN/carried/$P.log"
echo "$(date +%T) $P starts" >> "$LOG"
for s in ${SEEDS:-651 768 673 183 683 244 475 855 907}; do
  echo "$(date +%T) start $P-$s (others before: $(others))" >> "$LOG"
  probe $s $P-$s -DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000 "$@"
  echo "$(date +%T) end $P-$s: $(grep -E '^END' "$RUN/out/$P-$s.log" | cut -c1-110) | others after: $(others)" >> "$LOG"
done
echo "$(date +%T) $P DONE" >> "$LOG"
