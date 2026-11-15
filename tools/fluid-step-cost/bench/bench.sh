#!/usr/bin/env bash
# bash tools/fluid-step-cost/bench/bench.sh <tag>: one timed mixed100 run with JFR, then one with the solver counters, alone.
# Output: tmp/pattern-census/bench-<tag>/{timed,diag}/ (report.json, stress.jfr), console in bench-<tag>/console.txt.
set -u
tag="$1"
out="tmp/pattern-census/bench-$tag"
mkdir -p "$out/timed" "$out/diag"
{
  echo "== $tag at $(git rev-parse --short=9 HEAD), $(date +%H:%M:%S)"
  ./gradlew --console=plain fluidServerBenchmark "-PfluidBenchmarkRunId=fcost-$tag-timed" -PfluidStressProfile=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100
  echo "timed exit $? $(date +%H:%M:%S)"
  cp "build/reports/fluid/M9/fcost-$tag-timed/report.json" "build/reports/fluid/M9/fcost-$tag-timed/stress.jfr" "$out/timed/" 2>&1
  ./gradlew --console=plain fluidServerBenchmark "-PfluidBenchmarkRunId=fcost-$tag-diag" -PfluidBenchmarkDiagnostics=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100
  echo "diag exit $? $(date +%H:%M:%S)"
  cp "build/reports/fluid/M9/fcost-$tag-diag/report.json" "$out/diag/" 2>&1
  echo "== done $(date +%H:%M:%S)"
} > "$out/console.txt" 2>&1
