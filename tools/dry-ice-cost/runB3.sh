#!/bin/bash
# Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): measurement B, third runner. The machine is not free (another worktree's
# hold-fuzz Gradle lane and a dev client), under which a 2 s budget holds jobs the alone run finishes (base-673 of runB2:
# 9 holds, a different path). Every run here uses the raised budget (no hold), so baseline and prototype follow their
# deterministic paths and are compared by checkpoints, counters and CPU time; wall is recorded but not counted.
source "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/env.sh"
LOG="$RUN/out/runB3.log"
RAISED=(-DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000)
run() { # run <classes folder under run/> <seed> <name>
  local classes="$1" seed="$2" name="$3"
  export CLASSES="$RUN/$classes" NOJFR=1
  echo "$(date +%T) start $name (classes $classes, others before: $(others))" >> "$LOG"
  probe "$seed" "$name" "${RAISED[@]}"
  echo "$(date +%T) end $name: $(grep -E '^END' "$RUN/out/$name.log") | others after: $(others)" >> "$LOG"
}
echo "$(date +%T) runB3 starts (raised budget)" >> "$LOG"
run classes 673 r-base-673
run classes-proto 673 r-proto-673
run classes 183 r-base-183
run classes-proto 183 r-proto-183
run classes 768 r-base-768
run classes-proto 768 r-proto-768
run classes-proto2 768 r-proto2-768
run classes 651 r-base-651
run classes-proto 651 r-proto-651
echo "$(date +%T) runB3 DONE" >> "$LOG"
