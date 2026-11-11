#!/usr/bin/env bash
# The blocked-in drum relieving to a flare stack (ReliefFlareGameTests), alone, on the review server:
#   bash tools/psv-flare/run.sh
# Log: tmp/psv/psv-flare.txt. Prints the run's psv_flare lines and the GameTest summary.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
OUT=$WT/tmp/psv; mkdir -p "$OUT"
log=$OUT/psv-flare.txt
./gradlew --offline --console=plain --max-workers 4 --init-script tools/psv-flare/psv-flare.init.gradle runReviewWorldServer -PreviewBatch=psv-flare > "$log" 2>&1
status=$?
echo "== psv flare exit=$status"
grep -E "psv_flare|required tests|failed|GameTestAssert" "$log" | cut -c1-400 | tail -20
