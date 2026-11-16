#!/usr/bin/env bash
# Captures src/test/resources/column/invalid-binary-roots.json on the medium crude (InvalidBinaryRootCapture.java, batch
# 2026-11-16-cdu17-fixture-removal). From the worktree root, after ./gradlew --offline testClasses --max-workers 4:
#   bash tools/vacuum-column-test/capture-roots.sh tmp/<name>
# Writes tmp/<name>/invalid-binary-roots.json; copy it to src/test/resources/column/ when the five points are captured.
set -e
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."
OUT="${1:?an output folder under tmp/}"
C="$(cygpath -m ~)/.gradle/caches/modules-2/files-2.1"
CP="build/classes/java/main;build/classes/java/test;build/resources/main;build/resources/test"
CP="$CP;$C/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
CP="$CP;$C/org.ejml/ejml-core/0.44.0/2450d402658e7907afa5fcf6c10b1ca2d9f30174/ejml-core-0.44.0.jar"
CP="$CP;$C/org.ejml/ejml-ddense/0.44.0/f0590aa927aaa74ce1e73ff34a8d6262e00de270/ejml-ddense-0.44.0.jar"
CP="$CP;$C/org.ejml/ejml-dsparse/0.44.0/6213b72811a9e41d8fdd94e4a578fa7002ae1add/ejml-dsparse-0.44.0.jar"
mkdir -p "$OUT/classes"
JUNIT=$(find "$(cygpath -u "$C")/org.junit.jupiter/junit-jupiter-api" -name '*.jar' | head -1)
OPENTEST=$(find "$(cygpath -u "$C")/org.opentest4j" -name '*.jar' | head -1)
CP="$CP;$(cygpath -m "$JUNIT");$(cygpath -m "$OPENTEST")"
javac -nowarn -cp "$CP" -d "$OUT/classes" tools/vacuum-column-test/InvalidBinaryRootCapture.java
java -cp "$OUT/classes;$CP" com.wormzjl.icheme.science.column.v3.InvalidBinaryRootCapture "$OUT/invalid-binary-roots.json"
