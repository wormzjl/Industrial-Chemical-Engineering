#!/usr/bin/env bash
# bash tools/fluid-step-cost/bench/s3.sh: S3's three timed runs of the mixed400 fixture, one after the other, each alone:
# 2 workers (the bench's count), 1 worker (saturated), 0 (automatic: up to 12 on this machine, as a world's default).
# Log: tmp/pattern-census/s3.txt.
set -u
log=tmp/pattern-census/s3.txt
: > "$log"
for pair in m400w2:2 m400w1:1 m400w0:0; do
  tag="${pair%%:*}"; workers="${pair##*:}"
  echo "== $tag $(date +%H:%M:%S)" >> "$log"
  bash tools/fluid-step-cost/bench/s3-run.sh "$tag" mixed400 "$workers" >> "$log" 2>&1
  echo "   exit $? $(date +%H:%M:%S)" >> "$log"
  cat "tmp/pattern-census/bench-$tag/machine.txt" >> "$log" 2>&1
  grep -E "BUILD|FAILED|OutOfMemory|Exception" "tmp/pattern-census/bench-$tag/console.txt" | head -8 >> "$log"
done
echo "== done $(date +%H:%M:%S)" >> "$log"
