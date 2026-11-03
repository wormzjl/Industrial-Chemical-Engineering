#!/bin/bash
# Scratch (step): the prototype form on the five worlds and the classification runs of group B, one after another,
# every run at the raised budget, no JFR. Compare by checkpoints and CPU; wall only when others stays 0.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/step"
source ./env.sh
LOG="$STEP/out/runS1.log"
run() { # run <classes> <seed> <name> [java options]
  local classes="$1" seed="$2" name="$3"; shift 3
  export CLASSES="$RUN/$classes"
  echo "$(date +%T) start $name (classes $classes $*, others before: $(others))" >> "$LOG"
  sprobe "$seed" "$name" 999999 999999 "$@"
  echo "$(date +%T) end $name: $(grep -E '^END' "$STEP/out/$name.log" | cut -c1-90) | others after: $(others)" >> "$LOG"
}
echo "$(date +%T) runS1 starts" >> "$LOG"
run classes-late 651 late-651 -Dstep.late=true
run classes-late 768 late-768 -Dstep.late=true
run classes-late2 651 late2-651 -Dstep.late=true
run classes-late2 768 late2-768 -Dstep.late=true
run classes-late 673 late-673 -Dstep.late=true
run classes-late 183 late-183 -Dstep.late=true
run classes-late2 673 late2-673 -Dstep.late=true
run classes-late2 183 late2-183 -Dstep.late=true
run classes-late 244 base-244
run classes-late 475 base-475
run classes-late 855 base-855
run classes-late 907 base-907
echo "$(date +%T) runS1 DONE" >> "$LOG"
