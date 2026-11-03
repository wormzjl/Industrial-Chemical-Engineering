#!/bin/bash
# Scratch (step): the same one prototype form with (b+) on the convergence world and on group B, for the per-world effect.
# Raised budget, no JFR, one after another. Compare by checkpoints and CPU.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/step"
source ./env.sh
LOG="$STEP/out/runS2.log"
export CLASSES="$RUN/classes-late2"
echo "$(date +%T) runS2 starts" >> "$LOG"
for seed in 683 244 475 855 907; do
  echo "$(date +%T) start late2-$seed (others before: $(others))" >> "$LOG"
  sprobe "$seed" "late2-$seed" 999999 999999 -Dstep.late=true
  echo "$(date +%T) end late2-$seed: $(grep -E '^END' "$STEP/out/late2-$seed.log" | cut -c1-90) | others after: $(others)" >> "$LOG"
done
echo "$(date +%T) runS2 DONE" >> "$LOG"
