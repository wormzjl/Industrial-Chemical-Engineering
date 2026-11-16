#!/usr/bin/env bash
# The merge lane (AGENTS.md, "Gradle, measurement and verification"), alone, one Gradle invocation at a time.
#   head: the tests that the branch's changes can reach (affected.mjs), chain-100 exact and the fluid GameTests when
#         what they load changed, and the campaign when one of its inputs changed;
#   base: test slowGates whole (printed lines) and the campaign with the ported harness, on main in a scratch worktree.
# Usage: bash tools/merge-lane/lane.sh <base|head> [--all] [steps...]
#   steps: test regression gametest campaign (default: all for head, test campaign for base).
#   --all: run every step whole, whatever changed.
# Every test run records what each test class loads (record.init.gradle: one JVM per class) and adds the load sets of
# the classes that passed to the load map, which the next lane selects from. A test class with no load set runs.
# Logs and reports go to tmp/merge-lane/<side>/, or $LANE_OUT/<side> (relative to the worktree root); $LANE_RUN is a
# fresh GameTest run id.
# $LANE_SINCE=<commit> is the selection base of a second lane in a batch: the tests, chain-100, the GameTests and the
# campaign are chosen by what changed since that commit (the earlier lane's), not since main.
set -u
WT=$(git rev-parse --show-toplevel)
side=${1:?usage: lane.sh <base|head> [--all] [steps]}; shift
force=false; [ "${1:-}" = --all ] && { force=true; shift; }
case $side in base) steps=${*:-test campaign}; force=true;; head) steps=${*:-test regression gametest campaign};; *) exit 2;; esac
REL=${LANE_OUT:-tmp/merge-lane}/$side; OUT=$WT/$REL; mkdir -p "$OUT"
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
PRINTED=$WT/tools/floating-roof-tanks/final-lane/printed.sh
LANE=$WT/tools/merge-lane
SEL=$OUT/selection.txt
# Test JVMs side by side: forks per test task, and Gradle workers (all forks together) at most the 8 cores.
FORKS=${LANE_FORKS:-4}; WORKERS=${LANE_WORKERS:-8}
cd "$WT" || exit 2
SINCE=${LANE_SINCE:-}
if [ -n "$SINCE" ]; then git merge-base --is-ancestor "$SINCE" HEAD 2>/dev/null || { echo "LANE_SINCE=$SINCE is not a commit before HEAD"; exit 2; }; fi
# Every lane run selects afresh: a selection file left by an earlier run is never reused (give a base with $LANE_SINCE).
rm -f "$SEL"
# As build.gradle finds cargo: on the PATH or in ~/.cargo/bin. Without it nativeSmoke cannot build the library.
have_cargo(){ command -v cargo > /dev/null 2>&1 || [ -f "$HOME/.cargo/bin/cargo.exe" ] || [ -f "$HOME/.cargo/bin/cargo" ]; }
export JAVA_OPTS=-Xshare:off
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count"; }
gradle(){ local log=$1; shift; local n; n=$(busy | tr -d '\r'); [ "${n:-0}" = 0 ] || echo "WARNING: $n test or client JVMs running at $(date '+%H:%M:%S')";
  local start=$(date +%s); ./gradlew "$@" --offline --console=plain > "$log" 2>&1; local s=$?; echo "exit=$s seconds=$(( $(date +%s) - start ))" >> "$log"; echo "exit=$s seconds=$(( $(date +%s) - start ))"; return $s; }
fuzz(){ local label=$1 harness=$2 dists=$3 extra=$4
  for dist in $dists; do d=false; [ $dist = defaults ] && d=true
    gradle "$OUT/$label-$dist.gradle.txt" --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
      -PholdFuzzSeeds=1-1000 -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=$d -PholdFuzzOut="$OUT/$label-$dist-report.txt" $extra
    bash "$CLASSIFY" "$OUT/$label-$dist-report.txt" > /dev/null; echo "== $label $dist"; grep -E '^SUMMARY' "$OUT/$label-$dist-report.txt"; done; }
# Whether anything the campaign loads changed since main, or since $LANE_SINCE (tools/merge-lane/campaign-inputs.txt).
campaign_due(){ $force && return 0
  local files; files=$(git diff --name-only "${SINCE:-$(git merge-base HEAD main)}" HEAD | tr -d '\r')
  [ -n "$files" ] && printf '%s\n' "$files" | grep -q -E -f <(tr -d '\r' < "$LANE/campaign-inputs.txt"); }
# The selection, once per lane run (the steps of one run share it): what each test task runs and whether chain-100 and
# the GameTests are due.
select_tests(){ [ -s "$SEL" ] && return 0
  if $force; then printf '%s\n' 'TASK test ALL' 'TASK holdGates ALL' 'TASK columnGates ALL' 'TASK equipmentGates ALL' 'TASK nativeSmoke ALL' \
      'STEP regression due --all' 'STEP gametest due --all' > "$SEL"; return 0; fi
  gradle "$OUT/classes.gradle.txt" testClasses compileFluidGameTestJava || { echo "compile failed: $OUT/classes.gradle.txt"; exit 2; }
  node "$LANE/affected.mjs" select ${SINCE:+--since "$SINCE"} > "$SEL" || { echo "selection failed"; exit 2; }
  grep -E '^(FULL|TASK|STEP|NOTE)' "$SEL" | cut -c1-160; }
step_due(){ select_tests > /dev/null; grep -q "^STEP $1 due" "$SEL"; }
record(){ rm -rf "$OUT/loads-$1"; echo "--init-script $LANE/record.init.gradle -PlaneRecord=$REL/loads-$1"; }
save(){ node "$LANE/affected.mjs" save "$OUT/loads-$1" "${@:2}"; }
for s in $steps; do
  echo "== $side $s start $(date '+%H:%M:%S') at $(git rev-parse --short HEAD)"
  case $s in
    test) select_tests; args=(); ran=()
          # Each task's filters are read from the selection file by record.init.gradle (-PlaneSelection): a command line
          # of --tests filters is cut off at about 8,000 characters on Windows.
          for t in test holdGates columnGates equipmentGates nativeSmoke; do
            kind=$(grep "^TASK $t " "$SEL" | tr -d '\r' | cut -d' ' -f3)
            case $kind in NONE|'') continue;; esac
            if [ $t = nativeSmoke ] && ! have_cargo; then echo "   nativeSmoke: no cargo found, the native tests do not run"; continue; fi
            args+=("$t"); ran+=("$t")
          done
          rm -rf "$OUT/results"; mkdir -p "$OUT/results"
          if [ ${#ran[@]} = 0 ]; then echo "== test skipped: no test class loads anything that changed"; continue; fi
          gradle "$OUT/gates.gradle.txt" "${args[@]}" --continue -PtestForks=$FORKS --max-workers=$WORKERS -PlaneSelection=$REL/selection.txt $(record test)
          for t in "${ran[@]}"; do cp build/test-results/$t/*.xml "$OUT/results/"; done
          grep -E '^> Task :(test|holdGates|columnGates|equipmentGates|nativeSmoke)( |$)' "$OUT/gates.gradle.txt"
          grep -hoE '<testsuite [^>]*>' "$OUT"/results/*.xml | grep -oE '(tests|failures|errors|skipped)="[0-9]+"' | awk -F'"' '{s[$1]+=$2} END{for(k in s) printf "%s%d ", k, s[k]; print ""}'
          bash "$PRINTED" "$OUT/results" "$OUT/printed.txt"; grep -E '^(LiquidJunctionTransientTest|MixedGasJunctionTransientTest) ' "$OUT/printed.txt" > "$OUT/junction.txt"
          [ -s "$OUT/junction.txt" ] && wc -l < "$OUT/junction.txt" || { rm -f "$OUT/junction.txt"; echo "   junction tests not selected: the baseline's lines stand"; }
          save test "$OUT/results";;
    regression) if step_due regression; then
          gradle "$OUT/chain-100.gradle.txt" --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact $(record regression); grep -iE "max|differ|exact|BUILD" "$OUT/chain-100.gradle.txt" | tail -5
          save regression build/test-results/fluidSolverRegression
          else echo "== regression skipped: $(grep '^STEP regression' "$SEL" | cut -d' ' -f4-)"; fi;;
    gametest) if step_due gametest; then
          gradle "$OUT/gametests.gradle.txt" --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=${LANE_RUN:-lane-$(date +%m%d-%H%M)}; grep -E "required tests|failed" "$OUT/gametests.gradle.txt" | tail -3
          else echo "== gametest skipped: $(grep '^STEP gametest' "$SEL" | cut -d' ' -f4-)"; fi;;
    campaign) if campaign_due; then fuzz campaign-cold "$FORMS/hold-fuzz-cold" random -PholdFuzzCold=true; fuzz campaign-bundled "$FORMS/hold-fuzz-bundled" "random defaults" ""
              else echo "== campaign skipped: nothing it loads changed since main (--all runs it)"; fi;;
    *) echo "unknown step $s"; exit 2;;
  esac
done
echo "== $side lane done $(date '+%H:%M:%S')"
