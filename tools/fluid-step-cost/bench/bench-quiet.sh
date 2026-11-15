#!/usr/bin/env bash
# bash tools/fluid-step-cost/bench/bench-quiet.sh <tag>: the timed mixed100 run with JFR (no counters run), started only once the
# machine check reads OK three times 20 s apart (waits up to 20 minutes), with the check logged before, at +75 s, +100 s
# (inside the measured minute) and after. Output: tmp/pattern-census/bench-<tag>/timed/, the checks in bench-<tag>/machine.txt.
set -u
tag="$1"
out="tmp/pattern-census/bench-$tag"
mkdir -p "$out/timed"
check() { powershell -NoProfile -File tools/codebase-review-bench/check-machine.ps1 2>&1 | tail -1; }
: > "$out/machine.txt"
quiet=0
for i in $(seq 1 60); do
  line="$(check)"
  if [ "$line" = "OK" ]; then quiet=$((quiet+1)); else quiet=0; echo "wait $(date +%H:%M:%S): $line" >> "$out/machine.txt"; fi
  [ "$quiet" -ge 3 ] && break
  sleep 20
done
[ "$quiet" -ge 3 ] || { echo "never quiet" >> "$out/machine.txt"; exit 3; }
echo "before $(date +%H:%M:%S): OK x3, code $(git rev-parse --short=9 HEAD)" >> "$out/machine.txt"
( sleep 75; echo "+75s $(date +%H:%M:%S): $(check)" >> "$out/machine.txt"; sleep 20; echo "+100s $(date +%H:%M:%S): $(check)" >> "$out/machine.txt" ) &
./gradlew --console=plain fluidServerBenchmark "-PfluidBenchmarkRunId=fcost-$tag-timed" -PfluidStressProfile=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100 > "$out/console.txt" 2>&1
echo "timed exit $? $(date +%H:%M:%S)" >> "$out/machine.txt"
cp "build/reports/fluid/M9/fcost-$tag-timed/report.json" "build/reports/fluid/M9/fcost-$tag-timed/stress.jfr" "$out/timed/" 2>&1
wait
echo "bench server pid: $(grep -o '"processId" *: *[0-9]*' "$out/timed/report.json" | head -1)" >> "$out/machine.txt"
echo "after $(date +%H:%M:%S): $(check)" >> "$out/machine.txt"
