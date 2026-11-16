#!/usr/bin/env bash
# The P3 forms probe (batch 2026-11-16-supercritical-followups, P3_COLUMN_FEED_REVIEW.md) on one grid of
# COLUMN_FEED_REVIEW.md. From the worktree root, after ./gradlew --offline testClasses --max-workers 4:
#   bash tools/supercritical-probes/p3/run.sh coarse|dense|wet tmp/<name>
# (wet: P3WetProbe, the lane fixes' wet grid and dry two-liquid scan, LANE_FIXES_REVIEW.md)
# Compiles against the built classes (the probe reads package-private members: it compiles only against the commit it
# names, or one with the same members) and writes <name>/<grid>.tsv (summary, then the states) and <grid>.log.
set -e
# Git Bash would rewrite the ';' class path as a POSIX list.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../../.."
OUT="${2:?an output folder under tmp/}"
C="$(cygpath -m ~)/.gradle/caches/modules-2/files-2.1"
CP="build/classes/java/main;build/classes/java/test;build/resources/main;build/resources/test"
CP="$CP;$C/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
CP="$CP;$C/org.ejml/ejml-core/0.44.0/2450d402658e7907afa5fcf6c10b1ca2d9f30174/ejml-core-0.44.0.jar"
CP="$CP;$C/org.ejml/ejml-ddense/0.44.0/f0590aa927aaa74ce1e73ff34a8d6262e00de270/ejml-ddense-0.44.0.jar"
CP="$CP;$C/org.ejml/ejml-dsparse/0.44.0/6213b72811a9e41d8fdd94e4a578fa7002ae1add/ejml-dsparse-0.44.0.jar"
mkdir -p "$OUT/classes"
javac -nowarn -cp "$CP" -d "$OUT/classes" $(find tools/supercritical-probes/p3/src -name '*.java')
MAIN=P3FormsProbe
[ "$1" = wet ] && MAIN=P3WetProbe
java -cp "$OUT/classes;$CP" com.wormzjl.icheme.science.column.v3.thermo.$MAIN "$1" "$OUT/$1.tsv" > "$OUT/$1.log" 2>&1
echo "exit $?" >> "$OUT/$1.log"
