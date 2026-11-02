#!/bin/bash
# Scratch (step): forms F and G with (b+), all nine worlds, and (b+) alone where no run exists. Serial, raised budget, no JFR.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/step"
source ./env.sh
LOG="$STEP/out/runS4.log"
export CLASSES="$RUN/classes-forms"
J=$(find "$C" -name 'junit-jupiter-api-*.jar' | grep -v sources | head -1); O=$(find "$C" -name 'opentest4j-*.jar' | grep -v sources | head -1)
run() { local seed="$1" name="$2"; shift 2
  echo "$(date +%T) start $name (others before: $(others))" >> "$LOG"
  sprobe "$seed" "$name" 999999 999999 "$@"
  echo "$(date +%T) end $name: $(grep -E '^END' "$STEP/out/$name.log" | cut -c1-90) | others after: $(others)" >> "$LOG"; }
gate() { local name="$1" class="$2"; shift 2
  echo "$(date +%T) start test $name" >> "$LOG"
  "$JDK/java.exe" -Xshare:off -Xmx3g -Dfile.encoding=UTF-8 "$@" -cp "runner;$CLASSES;$W/src/main/resources;$W/src/test/resources;$BUILT;$LIBS;$J;$O" RunClosure "$class" > "$STEP/out/$name.txt" 2>&1
  echo "$(date +%T) end test $name" >> "$LOG"; }
echo "$(date +%T) runS4 starts" >> "$LOG"
run 855 off-855
for s in 183 244 475 673; do run $s bp-$s; done
for s in 183 244 475 651 673 683 768 855 907; do run $s f-$s -Dstep.tolerance=0.01; done
for s in 183 244 475 651 673 683 768 855 907; do run $s g-$s -Dstep.lateBound=true; done
P=com.wormzjl.icheme.science.fluid.network
gate f-closure $P.SolidClosureFeasibilityTest -Dstep.tolerance=0.01
gate f-crystal $P.CrystalDepositionIslandTest -Dstep.tolerance=0.01
gate g-closure $P.SolidClosureFeasibilityTest -Dstep.lateBound=true
echo "$(date +%T) runS4 DONE" >> "$LOG"
