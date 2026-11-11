#!/usr/bin/env bash
# The drum level loop (LevelLoopGameTests) at time factor <factor>, alone, on the review server:
#   bash tools/minecraft-time-delta/level-loop/run.sh <factor> [cycles]
# Sets fluid.timeScale in run/review-world/config/icheme-common.toml for the run and restores the file after; cycles (the
# drum's hold in control cycles, default 2) goes in as -Dicheme.levelLoop.cycles. Log: tmp/time-scale/level-loop-<factor>x<cycles>.txt.
WT=$(git rev-parse --show-toplevel); cd "$WT" || exit 2
factor=$1; cycles=${2:-2}
CONFIG=$WT/run/review-world/config/icheme-common.toml
OUT=$WT/tmp/time-scale; mkdir -p "$OUT"
[ -f "$CONFIG" ] || { echo "no $CONFIG: run the review server once first"; exit 2; }
cp "$CONFIG" "$OUT/icheme-common.toml.saved"
# A config written before the time factor has no key: it goes under [fluid].
if grep -q '^\s*timeScale\s*=' "$CONFIG"; then sed -i "s/^\(\s*timeScale\s*=\s*\).*/\1$factor/" "$CONFIG"
else sed -i "s/^\[fluid\]\r\?$/&\n\ttimeScale = $factor/" "$CONFIG"; fi
grep -q "^\s*timeScale\s*=\s*$factor\b" "$CONFIG" || { echo "could not set timeScale in $CONFIG"; cp "$OUT/icheme-common.toml.saved" "$CONFIG"; exit 2; }
log=$OUT/level-loop-${factor}x$cycles.txt
JAVA_TOOL_OPTIONS="-Dicheme.levelLoop.cycles=$cycles" ./gradlew --offline --console=plain --init-script tools/minecraft-time-delta/level-loop/level-loop.init.gradle \
  runReviewWorldServer -PreviewBatch=level-loop > "$log" 2>&1
status=$?
cp "$OUT/icheme-common.toml.saved" "$CONFIG"
echo "== level loop factor=$factor cycles=$cycles exit=$status"
grep -E "level_loop (start|fill|feed|hold|step|result|cycle)|required tests" "$log" | cut -c60-300
