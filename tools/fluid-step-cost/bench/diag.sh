#!/usr/bin/env bash
# bash tools/fluid-step-cost/bench/diag.sh <tag>: the bench's diagnostics run alone (solver counters; no time is read from it),
# for the code at HEAD. Output: tmp/pattern-census/bench-<tag>/diag/report.json, console in bench-<tag>/diag-console.txt.
set -u
tag="$1"
out="tmp/pattern-census/bench-$tag"
mkdir -p "$out/diag"
./gradlew --console=plain fluidServerBenchmark "-PfluidBenchmarkRunId=fcost-$tag-diag" -PfluidBenchmarkDiagnostics=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100 > "$out/diag-console.txt" 2>&1
echo "diag exit $? at $(git rev-parse --short=9 HEAD) $(date +%H:%M:%S)"
cp "build/reports/fluid/M9/fcost-$tag-diag/report.json" "$out/diag/" 2>&1
