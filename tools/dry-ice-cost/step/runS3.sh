#!/bin/bash
# Scratch (step): attribution of the effect on 683, 855, 907: (b+) alone and late closure alone. Same one form, no new one.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/step"
source ./env.sh
LOG="$STEP/out/runS3.log"
run() { local classes="$1" seed="$2" name="$3"; shift 3; export CLASSES="$RUN/$classes"
  echo "$(date +%T) start $name (others before: $(others))" >> "$LOG"
  sprobe "$seed" "$name" 999999 999999 "$@"
  echo "$(date +%T) end $name: $(grep -E '^END' "$STEP/out/$name.log" | cut -c1-90) | others after: $(others)" >> "$LOG"; }
for seed in 683 855 907; do
  run classes-late2 "$seed" "bp-$seed"
  run classes-late "$seed" "late-$seed" -Dstep.late=true
done
echo "$(date +%T) runS3 DONE" >> "$LOG"
