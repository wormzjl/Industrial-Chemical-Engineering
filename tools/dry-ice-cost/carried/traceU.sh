#!/bin/bash
# Scratch: instrumented build of the worktree with the thread's hooks, then one traced job. traceT.sh <classes> <seed> <name> <job> [java options]
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run"
source ./step/env.sh
K="$1"; S="$2"; N="$3"; J="$4"; shift 4
if [ "${REBUILD:-1}" = 1 ]; then
  eval "$(declare -f stepbuild | sed 's#\$STEP/hook.pl#$RUN/carried/hook-u.pl#')"
  stepbuild "$RUN/$K" 2>&1 | tail -3
fi
export CLASSES="$RUN/$K" NOJFR=1
sprobe "$S" "$N" "$J" "$J" "$@"
tail -3 "$STEP/out/$N.log" | cut -c1-200
