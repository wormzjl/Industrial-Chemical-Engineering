#!/usr/bin/env bash
# Final lane of the heat batch merge (AGENTS.md), alone, one Gradle invocation at a time:
#   base: main 0.16.2 in a scratch worktree: test slowGates (printed lines) and the campaign with the ported harness;
#   head: this branch: test slowGates, chain-100 exact, fluid GameTests on a fresh run id, the campaign.
# Usage: bash lane.sh <base|head> [steps...]   steps: test regression gametest campaign (default: all for head,
# test campaign for base). Logs and reports in tmp/merge-lane/<side>/.
set -u
HEAD_WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/research-folder-reorganize-b8443b
BASE_WT=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/heat-merge-baseline-scratch
side=${1:?usage: lane.sh <base|head> [steps]}; shift
case $side in base) WT=$BASE_WT; steps=${*:-test campaign};; head) WT=$HEAD_WT; steps=${*:-test regression gametest campaign};; *) exit 2;; esac
OUT=$HEAD_WT/tmp/merge-lane/$side; mkdir -p "$OUT"
FORMS=$HEAD_WT/tools/unified-thermo-port-forms
CLASSIFY=$HEAD_WT/tools/phase-ports-probes/hold-fuzz/classify.sh
PRINTED=$HEAD_WT/tools/floating-roof-tanks/final-lane/printed.sh
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
for s in $steps; do
  echo "== $side $s start $(date '+%H:%M:%S') at $(git rev-parse --short HEAD)"
  case $s in
    test) gradle "$OUT/gates.gradle.txt" test slowGates --continue --rerun-tasks; rm -rf "$OUT/results"; mkdir -p "$OUT/results"
          cp build/test-results/test/*.xml build/test-results/slowGates/*.xml "$OUT/results/"
          grep -hoE '<testsuite [^>]*>' "$OUT"/results/*.xml | grep -oE '(tests|failures|errors|skipped)="[0-9]+"' | awk -F'"' '{s[$1]+=$2} END{for(k in s) printf "%s%d ", k, s[k]; print ""}'
          bash "$PRINTED" "$OUT/results" "$OUT/printed.txt"; grep -E '^(LiquidJunctionTransientTest|MixedGasJunctionTransientTest) ' "$OUT/printed.txt" > "$OUT/junction.txt"; wc -l < "$OUT/junction.txt";;
    regression) gradle "$OUT/chain-100.gradle.txt" --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact; grep -iE "max|differ|exact|BUILD" "$OUT/chain-100.gradle.txt" | tail -5;;
    gametest) gradle "$OUT/gametests.gradle.txt" --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=heat-merge-final; grep -E "required tests|failed" "$OUT/gametests.gradle.txt" | tail -3;;
    campaign) fuzz campaign-cold "$FORMS/hold-fuzz-cold" random -PholdFuzzCold=true; fuzz campaign-bundled "$FORMS/hold-fuzz-bundled" "random defaults" "";;
    *) echo "unknown step $s"; exit 2;;
  esac
done
echo "== $side lane done $(date '+%H:%M:%S')"
