#!/usr/bin/env bash
# The merge lane (AGENTS.md, "Gradle, measurement and verification"), alone, one Gradle invocation at a time.
#   base: test slowGates (printed lines) and the campaign with the ported harness, on main in a scratch worktree;
#   head: test slowGates, chain-100 exact, the fluid GameTests on a fresh run id and the campaign, on the branch.
# Usage: bash tools/merge-lane/lane.sh <base|head> [--all] [steps...]
#   steps: test regression gametest campaign (default: all for head, test campaign for base).
#   --all: run the campaign even when none of its inputs changed.
# Gradle skips a test task whose inputs did not change, so a lane never passes --rerun-tasks: columnGates and equipmentGates run only when
# something it loads changed, and a result that stands is restored into build/test-results.
# Logs and reports go to tmp/merge-lane/<side>/, or $LANE_OUT/<side>; $LANE_RUN is a fresh GameTest run id.
set -u
WT=$(git rev-parse --show-toplevel)
side=${1:?usage: lane.sh <base|head> [--all] [steps]}; shift
force=false; [ "${1:-}" = --all ] && { force=true; shift; }
case $side in base) steps=${*:-test campaign};; head) steps=${*:-test regression gametest campaign};; *) exit 2;; esac
OUT=$WT/${LANE_OUT:-tmp/merge-lane}/$side; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
PRINTED=$WT/tools/floating-roof-tanks/final-lane/printed.sh
cd "$WT" || exit 2
export JAVA_OPTS=-Xshare:off
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count"; }
gradle(){ local log=$1; shift; local n; n=$(busy | tr -d '\r'); [ "${n:-0}" = 0 ] || echo "WARNING: $n test or client JVMs running at $(date '+%H:%M:%S')";
  local start=$(date +%s); ./gradlew "$@" --offline --console=plain > "$log" 2>&1; local s=$?; echo "exit=$s seconds=$(( $(date +%s) - start ))" >> "$log"; echo "exit=$s seconds=$(( $(date +%s) - start ))"; return $s; }
fuzz(){ local label=$1 harness=$2 dists=$3 extra=$4
  for dist in $dists; do d=false; [ $dist = defaults ] && d=true
    gradle "$OUT/$label-$dist.gradle.txt" --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
      -PholdFuzzSeeds=1-1000 -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=$d -PholdFuzzOut="$OUT/$label-$dist-report.txt" $extra
    bash "$CLASSIFY" "$OUT/$label-$dist-report.txt" > /dev/null; echo "== $label $dist"; grep -E '^SUMMARY' "$OUT/$label-$dist-report.txt"; done; }
# Whether anything the campaign loads changed since main (tools/merge-lane/campaign-inputs.txt).
campaign_due(){ $force && return 0; [ "$side" = head ] || return 0
  local files; files=$(git diff --name-only "$(git merge-base HEAD main)" HEAD | tr -d '\r')
  [ -n "$files" ] && printf '%s\n' "$files" | grep -q -E -f <(tr -d '\r' < "$WT/tools/merge-lane/campaign-inputs.txt"); }
for s in $steps; do
  echo "== $side $s start $(date '+%H:%M:%S') at $(git rev-parse --short HEAD)"
  case $s in
    test) gradle "$OUT/gates.gradle.txt" test slowGates --continue; rm -rf "$OUT/results"; mkdir -p "$OUT/results"
          cp build/test-results/test/*.xml build/test-results/holdGates/*.xml build/test-results/columnGates/*.xml build/test-results/equipmentGates/*.xml "$OUT/results/"
          grep -E '^> Task :(test|holdGates|columnGates|equipmentGates)( |$)' "$OUT/gates.gradle.txt"
          grep -hoE '<testsuite [^>]*>' "$OUT"/results/*.xml | grep -oE '(tests|failures|errors|skipped)="[0-9]+"' | awk -F'"' '{s[$1]+=$2} END{for(k in s) printf "%s%d ", k, s[k]; print ""}'
          bash "$PRINTED" "$OUT/results" "$OUT/printed.txt"; grep -E '^(LiquidJunctionTransientTest|MixedGasJunctionTransientTest) ' "$OUT/printed.txt" > "$OUT/junction.txt"; wc -l < "$OUT/junction.txt";;
    regression) gradle "$OUT/chain-100.gradle.txt" --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact; grep -iE "max|differ|exact|BUILD" "$OUT/chain-100.gradle.txt" | tail -5;;
    gametest) gradle "$OUT/gametests.gradle.txt" --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=${LANE_RUN:-lane-$(date +%m%d-%H%M)}; grep -E "required tests|failed" "$OUT/gametests.gradle.txt" | tail -3;;
    campaign) if campaign_due; then fuzz campaign-cold "$FORMS/hold-fuzz-cold" random -PholdFuzzCold=true; fuzz campaign-bundled "$FORMS/hold-fuzz-bundled" "random defaults" ""
              else echo "== campaign skipped: nothing it loads changed since main (--all runs it)"; fi;;
    *) echo "unknown step $s"; exit 2;;
  esac
done
echo "== $side lane done $(date '+%H:%M:%S')"
