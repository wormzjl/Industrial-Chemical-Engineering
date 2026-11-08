#!/usr/bin/env bash
# Disassembles every compiled class (code, private members, constants; no line or local-variable tables) into one
# sorted text file per source set, to prove that a layout-only change leaves the bytecode unchanged: dump before,
# apply the change, compile, dump again, and compare the two folders with `cmp`.
# Usage, from the repository root after a compile: tools/simulator-sizing/consolidation/javap-dump.sh tmp/<name>/<out>
# JAVAP defaults to the JDK 21 the review used.
set -euo pipefail
out="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
mkdir -p "$out"
javap="${JAVAP:-/c/Program Files/Java/jdk-21.0.11/bin/javap}"
for set in main test fluidGameTest; do
  dir="build/classes/java/$set"
  : > "$out/$set.txt"
  ( cd "$dir" && find . -name '*.class' | sort ) > "$out/$set.list"
  # Batches keep the command line short on Windows.
  split -l 150 "$out/$set.list" "$out/$set.part."
  for part in "$out/$set.part."*; do
    ( cd "$dir" && "$javap" -c -p -constants $(cat "$part") ) >> "$out/$set.txt"
    rm "$part"
  done
  echo "$set: $(wc -l < "$out/$set.list") classes, $(wc -l < "$out/$set.txt") lines"
done
