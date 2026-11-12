#!/usr/bin/env bash
# The fluid converter's GameTests (ConverterGameTests), alone, on the review server:
#   bash tools/converter-gametest/run.sh
# Log: tmp/fluid-conversion/converter-gametest.txt. Prints the run's converter_gametest lines and the GameTest summary.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
OUT=$WT/tmp/fluid-conversion; mkdir -p "$OUT"
log=$OUT/converter-gametest.txt
./gradlew --offline --console=plain --max-workers 4 --init-script tools/converter-gametest/converter.init.gradle runReviewWorldServer -PreviewBatch=converter > "$log" 2>&1
status=$?
echo "== converter gametest exit=$status"
grep -E "converter_gametest|required tests|failed|GameTestAssert|Expected:" "$log" | cut -c1-400 | tail -20
