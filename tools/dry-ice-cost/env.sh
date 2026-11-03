# Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): Gradle-free build and run of DryIceProbe. Source this file in Git Bash.
# After the brief's hint (focused-raman's tmp/ph-false-root/env.ps1): the JDK and the jars from the Gradle cache.
JDK="C:/Program Files/Java/jdk-21.0.11/bin"
W="D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f"
RUN="$W/tmp/dry-ice-symptoms/run"
C="$(cygpath -m "$USERPROFILE")/.gradle/caches/modules-2/files-2.1"
LIBS="$W/build/moddev/artifacts/neoforge-21.1.219.jar;$(find "$C/com.google.code.gson/gson/2.10.1" -name 'gson-2.10.1.jar' | head -1)"
for a in org.ejml/ejml-core org.ejml/ejml-ddense org.ejml/ejml-dsparse org.jetbrains/annotations org.junit.jupiter/junit-jupiter-api org.opentest4j/opentest4j org.apiguardian/apiguardian-api org.junit.platform/junit-platform-commons org.slf4j/slf4j-api; do
  LIBS="$LIBS;$(find "$C/$a" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' | sort -V | tail -1)"
done
# Only science/** and runtime/** are compiled from source (junctions under srcpath/, testpath/; -Xprefer:source); the mod's
# registry, world and client classes come from the stale build/classes, which the solver never runs. The stub IChemE is
# compiled first into classes/, which precedes build/classes on the classpath. HoldFuzzTest.java is not in a package
# folder, so it is named on the command line.
SRC="$RUN/srcpath;$RUN/testpath;$RUN/src;$RUN/stub"
BUILT="$W/build/classes/java/main;$W/build/classes/java/test"
# The junctions are made for a build and removed after it (cmd rmdir removes a junction without touching its target): a
# junction left in tmp/ would let a recursive delete of tmp/ reach src/.
junctions() {
  local pair link target
  for pair in "srcpath/com/wormzjl/icheme/science:src/main/java/com/wormzjl/icheme/science" "srcpath/com/wormzjl/icheme/runtime:src/main/java/com/wormzjl/icheme/runtime" "testpath/com/wormzjl/icheme/science:src/test/java/com/wormzjl/icheme/science" "testpath/com/wormzjl/icheme/fluid:src/test/java/com/wormzjl/icheme/fluid"; do
    link="$RUN/${pair%%:*}"; target="$W/${pair##*:}"; mkdir -p "$(dirname "$link")"
    if [ "$1" = "remove" ]; then [ -e "$link" ] && cmd //c rmdir "$(cygpath -w "$link")"; else [ -e "$link" ] || powershell -NoProfile -Command "New-Item -ItemType Junction -Path '$(cygpath -w "$link")' -Target '$(cygpath -w "$target")' | Out-Null"; fi
  done
}
# CLASSES selects the output folder (default classes/), so a second build of patched sources can live beside the first.
CLASSES="${CLASSES:-$RUN/classes}"
build() {
  junctions make; trap 'junctions remove' RETURN
  rm -rf "$CLASSES"; mkdir -p "$CLASSES"
  # One invocation: the stub, the scratch sources and the closure all come off the sourcepath (stub/ and src/ are roots).
  "$JDK/javac.exe" -J-Duser.language=en -encoding UTF-8 -nowarn -proc:none -Xprefer:source -d "$CLASSES" -sourcepath "$SRC" -cp "$BUILT;$LIBS" "$RUN/stub/com/wormzjl/icheme/IChemE.java" $(find "$RUN/src" -name '*.java') "$W/tools/unified-thermo-port-forms/hold-fuzz-cold/src/HoldFuzzTest.java" 2>&1 | grep -vE "^Note:" | head -40
  echo "classes: $(find "$CLASSES" -name '*.class' | wc -l) in $CLASSES"
}
# probe <seed> <name> [java options...]: writes out/<name>-jobs.tsv, out/<name>-states.txt, out/<name>.jfr, out/<name>.log; JFR off with NOJFR=1
probe() {
  local seed="$1" name="$2"; shift 2
  local jfr=(); [ -n "$NOJFR" ] || jfr=(-XX:FlightRecorderOptions=stackdepth=256 "-XX:StartFlightRecording=filename=$RUN/out/$name.jfr,settings=profile,dumponexit=true")
  "$JDK/java.exe" -Xshare:off -Xmx8g -Dfile.encoding=UTF-8 -DholdFuzz.cold=true -DholdFuzz.slices=120 "$@" "${jfr[@]}" \
    -cp "$CLASSES;$W/src/main/resources;$W/src/test/resources;$BUILT;$LIBS" com.wormzjl.icheme.runtime.fluid.DryIceProbe "$seed" "$RUN/out/$name" > "$RUN/out/$name.log" 2>&1
}
others() { powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar|DryIceProbe' }).Count" | tr -d '\r'; }
