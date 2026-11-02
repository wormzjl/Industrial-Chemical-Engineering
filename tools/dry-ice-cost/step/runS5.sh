#!/bin/bash
# Scratch (step): form F on today's thermo, no stand-in. Is 651's stall after a closure the stand-in's, and does the
# tolerance alone clear anything? Same form as runS4, no new one. Serial, raised budget, no JFR.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/step"
source ./env.sh
LOG="$STEP/out/runS5.log"
echo "$(date +%T) runS5 starts" >> "$LOG"
stepbuild "$RUN/classes-forms0" >> "$LOG" 2>&1
export CLASSES="$RUN/classes-forms0"
run() { local seed="$1" name="$2"; shift 2
  echo "$(date +%T) start $name (others before: $(others))" >> "$LOG"
  sprobe "$seed" "$name" 999999 999999 "$@"
  echo "$(date +%T) end $name: $(grep -E '^END' "$STEP/out/$name.log" | cut -c1-90) | others after: $(others)" >> "$LOG"; }
run 855 off0-855
for s in 651 855 768 683 183 673 475 907 244; do run $s f0-$s -Dstep.tolerance=0.01; done
echo "$(date +%T) runS5 DONE" >> "$LOG"
