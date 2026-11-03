# Scratch (tmp/dry-ice-symptoms/run/step, 2026-11-02): the instrumented build beside env.sh. Source in Git Bash.
source "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f/tmp/dry-ice-symptoms/run/env.sh"
STEP="$RUN/step"
RAISED=(-DholdFuzz.wallNanos=600000000000 -DholdFuzz.softNanos=300000000000)
# stepbuild <classes folder> [patched FluidThermodynamics.java]: the instrumented sources overlaid on the probe's scratch sources
stepbuild() {
  local classes="$1"; local ft="$2"; local ov="$STEP/ov-$(basename "$classes")"
  rm -rf "$ov"; mkdir -p "$ov"; cp -r "$STEP/extra/." "$ov/"
  perl "$STEP/hook.pl" "$W" "$ov" $ft || return 1
  junctions make; trap 'junctions remove' RETURN
  rm -rf "$classes"; mkdir -p "$classes"
  local list; list="$(find "$RUN/src" -name '*.java' ! -name 'DryIceProbe.java') $(find "$ov" -name '*.java')"
  "$JDK/javac.exe" -J-Duser.language=en -encoding UTF-8 -nowarn -proc:none -Xprefer:source -d "$classes" -sourcepath "$SRC" -cp "$BUILT;$LIBS" "$RUN/stub/com/wormzjl/icheme/IChemE.java" $list "$W/tools/unified-thermo-port-forms/hold-fuzz-cold/src/HoldFuzzTest.java" 2>&1 | grep -vE "^Note:" | head -40
  echo "classes: $(find "$classes" -name '*.class' | wc -l) in $classes"
}
# sprobe <seed> <name> <jobs spec> <stop after job> [java options]: JFR off, writes step/out/<name>-*
sprobe() {
  local seed="$1" name="$2" jobs="$3" stop="$4"; shift 4
  "$JDK/java.exe" -Xshare:off -Xmx8g -Dfile.encoding=UTF-8 -DholdFuzz.cold=true -DholdFuzz.slices=120 "${RAISED[@]}" -Dstep.jobs="$jobs" -Dstep.out="$STEP/out/$name-trace.txt" -Dstep.stopAfter="$stop" "$@" \
    -cp "$CLASSES;$W/src/main/resources;$W/src/test/resources;$BUILT;$LIBS" com.wormzjl.icheme.runtime.fluid.DryIceProbe "$seed" "$STEP/out/$name" > "$STEP/out/$name.log" 2>&1
}
