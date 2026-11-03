#!/bin/bash
# Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): the three crystal gates (CrystalDepositionIslandTest, CrystalIslandRuntimeTest,
# CrystalFlushBudgetTest) on form (b) alone and on HEAD, after the (b+) run (out/gates-bplus.log). Leaves src/ at HEAD.
W="D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f"
RUN="$W/tmp/dry-ice-symptoms/run"
F="src/main/java/com/wormzjl/icheme/science/fluid/thermo/FluidThermodynamics.java"
cd "$W" || exit 1
gates() { # gates <name>
  local name="$1" log="$RUN/out/gates-$name.log"
  echo "start $(date +%T)" > "$log"; git diff --stat -- src/ >> "$log"
  JAVA_OPTS=-Xshare:off ./gradlew --no-configuration-cache test --tests '*CrystalDepositionIslandTest' --tests '*CrystalIslandRuntimeTest' --tests '*CrystalFlushBudgetTest' --offline --console=plain >> "$log" 2>&1
  echo "exit $? at $(date +%T)" >> "$log"
  mkdir -p "$RUN/out/gates-$name-xml"; cp build/test-results/test/TEST-*Crystal*.xml "$RUN/out/gates-$name-xml/"
}
git checkout -- "$F" && git apply "$RUN/proto-b.patch" && gates b
git checkout -- "$F" && gates head
git status --short > "$RUN/out/gates-status.txt"
