#!/bin/bash
# Scratch: the worlds on the tally classes. runT.sh <classes folder name> <prefix>
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run"
source ./env.sh
K="$1"; P="$2"
export CLASSES="$RUN/$K" NOJFR=1
LOG="$RUN/carried/$P.log"
for s in ${SEEDS:-651 768 673 183 683 244 475 855 907}; do
  probe $s $P-$s -DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000 -Dcarried.tally="$RUN/carried/$P-$s.txt"
  echo "$(date +%T) end $P-$s: $(grep -E '^END' "$RUN/out/$P-$s.log" | cut -c1-110)" >> "$LOG"
done
echo "$(date +%T) $P DONE" >> "$LOG"
