#!/usr/bin/env bash
# The merge lane (AGENTS.md, "Gradle, measurement and verification"), one Gradle invocation at a time.
#   head: the tests that the branch's changes can reach (affected.mjs) without those of left-out.txt, chain-100 exact,
#         the fluid GameTests and the campaign's watch seeds, each only when what it loads changed since it last ran on
#         this line of commits;
#   base: test slowGates whole (printed lines) and the campaign with the ported harness, on main in a scratch worktree.
# Usage: bash tools/merge-lane/lane.sh <base|head> [--all] [steps...]
#   steps: test regression gametest campaign (default: all for head, test campaign for base).
#   --all, base: every step whole, every test, and the campaign's 3 x 1000 seeds, whatever changed. Only on the
#         owner's word: they need LANE_FULL=owner.
#   LANE_SEEDS=<seeds> runs the campaign on those seeds (1-1000 for the full one) whether it is due or not.
# The lane measures no time and prints none (owner, 2026-11-16: "timing belongs to benchmark runs").
# Every test run records what each test JVM loads (record.init.gradle) and adds the load sets of the classes that
# passed to the load map, which the next lane selects from. A test class with no load set runs.
# Logs and reports go to tmp/merge-lane/<side>/, or $LANE_OUT/<side> (relative to the worktree root); $LANE_RUN is a
# fresh GameTest run id.
set -u
WT=$(git rev-parse --show-toplevel)
side=${1:?usage: lane.sh <base|head> [--all] [steps]}; shift
force=false; [ "${1:-}" = --all ] && { force=true; shift; }
case $side in base) steps=${*:-test campaign}; force=true;; head) steps=${*:-test regression gametest campaign};; *) exit 2;; esac
if $force && [ "${LANE_FULL:-}" != owner ]; then
  echo "lane.sh: --all and base run every test and all 3000 campaign seeds. AGENTS.md: only when the owner asks."
  echo "         On the owner's word: LANE_FULL=owner bash tools/merge-lane/lane.sh $side ..."; exit 2; fi
REL=${LANE_OUT:-tmp/merge-lane}/$side; OUT=$WT/$REL; mkdir -p "$OUT"
LANE=$WT/tools/merge-lane
FORMS=$WT/tools/unified-thermo-port-forms
CLASSIFY=$WT/tools/phase-ports-probes/hold-fuzz/classify.sh
SEL=$OUT/selection.txt; PICKS=$OUT/picks.txt
# Test JVMs side by side: Gradle workers (all test JVMs together) and forks per test task. 8 workers, the machine's
# cores: with more, wall-deadline holds appeared in tests that have none (batch 2026-11-16-final-lane-cost). 8 forks, so
# that the last task running can use every worker. LANE_GROUP: the test classes that share one JVM.
FORKS=${LANE_FORKS:-8}; WORKERS=${LANE_WORKERS:-8}; GROUP=${LANE_GROUP:-8}
cd "$WT" || exit 2
rm -f "$SEL" "$PICKS"
if [ -n "${LANE_SINCE:-}" ]; then git merge-base --is-ancestor "${LANE_SINCE%%..*}" HEAD 2>/dev/null || { echo "LANE_SINCE=$LANE_SINCE does not start at a commit before HEAD"; exit 2; }; fi
# As build.gradle finds cargo: on the PATH or in ~/.cargo/bin. Without it nativeSmoke cannot build the library.
have_cargo(){ command -v cargo > /dev/null 2>&1 || [ -f "$HOME/.cargo/bin/cargo.exe" ] || [ -f "$HOME/.cargo/bin/cargo" ]; }
export JAVA_OPTS=-Xshare:off
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count"; }
n=$(busy | tr -d '\r'); [ "${n:-0}" = 0 ] || echo "WARNING: $n test or client JVMs are running"
gradle(){ local log=$1; shift; ./gradlew "$@" --offline --console=plain > "$log" 2>&1; local s=$?; echo "exit=$s" >> "$log"; echo "exit=$s"; return $s; }
# The seeds of one campaign run: the watch list (campaign-seeds.txt), $LANE_SEEDS, or all 1000 with --all.
seeds_of(){ if [ -n "${LANE_SEEDS:-}" ]; then echo "$LANE_SEEDS"; elif $force; then echo 1-1000;
  else grep "^$1 " "$LANE/campaign-seeds.txt" | cut -d' ' -f2- | tr -d ' \r'; fi; }
fuzz(){ local label=$1 harness=$2 dists=$3 extra=$4 status=0
  for dist in $dists; do d=false; [ $dist = defaults ] && d=true
    local seeds; seeds=$(seeds_of "${label#campaign-}-$dist")
    rm -f "$OUT/$label-$dist-report.txt" "$OUT/$label-$dist-report.txt.classes.txt"
    [ -z "$seeds" ] && { echo "== $label $dist: no watch seed (campaign-seeds.txt), not run"; continue; }
    gradle "$OUT/$label-$dist.gradle.txt" --no-configuration-cache --init-script "$harness/hold-fuzz.init.gradle" test --tests '*HoldFuzzTest' \
      -PholdFuzzSeeds=$seeds -PholdFuzzThreads=8 -PholdFuzzCounters=true -PholdFuzzDefaults=$d -PholdFuzzOut="$OUT/$label-$dist-report.txt" $extra || status=1
    bash "$CLASSIFY" "$OUT/$label-$dist-report.txt" > /dev/null; echo "== $label $dist"
    grep -E '^SUMMARY' "$OUT/$label-$dist-report.txt" | sed -E 's/ (wallMs|seedMsSum)=[0-9]+//g'; done
  return $status; }
# The selection, once per lane: what each test task runs and whether chain-100, the GameTests and the campaign are due.
select_tests(){ [ -s "$SEL" ] && return 0
  if $force; then printf '%s\n' 'TASK test ALL' 'TASK holdGates ALL' 'TASK columnGates ALL' 'TASK equipmentGates ALL' \
      'STEP regression due --all' 'STEP gametest due --all' 'STEP campaign due --all' > "$SEL"; return 0; fi
  gradle "$OUT/classes.gradle.txt" testClasses compileFluidGameTestJava || { echo "compile failed: $OUT/classes.gradle.txt"; exit 2; }
  node "$LANE/affected.mjs" select ${LANE_SINCE:+--since $LANE_SINCE} > "$SEL" || { echo "selection failed"; exit 2; }
  grep -E '^(FULL|TASK|STEP|NOTE)' "$SEL" | cut -c1-200; }
step_due(){ select_tests > /dev/null; grep -q "^STEP $1 due" "$SEL"; }
why(){ grep "^STEP $1 " "$SEL" | cut -d' ' -f4-; }
record(){ rm -rf "$OUT/loads-$1"; echo "--init-script $LANE/record.init.gradle -PlaneRecord=$REL/loads-$1 -PlaneGroup=$GROUP"; }
save(){ node "$LANE/affected.mjs" save "$OUT/loads-$1" "${@:2}"; }
for s in $steps; do
  echo "== $side $s at $(git rev-parse --short HEAD)"
  case $s in
    test) select_tests; filters=()
          if $force; then ran=(test holdGates columnGates equipmentGates nativeSmoke)
          else grep '^PICK ' "$SEL" | cut -d' ' -f2- > "$PICKS"; ran=($(cut -d' ' -f1 "$PICKS" | sort -u)); filters=(-PlaneTests=$REL/picks.txt); fi
          if ! have_cargo; then for i in "${!ran[@]}"; do [ "${ran[$i]}" = nativeSmoke ] && { unset "ran[$i]"; echo "   nativeSmoke: no cargo found, the native tests do not run"; }; done; ran=("${ran[@]}"); fi
          rm -rf "$OUT/results"; mkdir -p "$OUT/results"
          if [ ${#ran[@]} = 0 ]; then echo "== test skipped: no test class loads anything that changed"; continue; fi
          echo "   tasks: ${ran[*]}"
          gradle "$OUT/gates.gradle.txt" "${ran[@]}" --continue -PtestForks=$FORKS --max-workers=$WORKERS "${filters[@]}" $(record test)
          for t in "${ran[@]}"; do ls build/test-results/$t/*.xml > /dev/null 2>&1 && cp build/test-results/$t/*.xml "$OUT/results/"; done
          grep -E '^> Task :\S+ FAILED' "$OUT/gates.gradle.txt"
          grep -hoE '<testsuite [^>]*>' "$OUT"/results/*.xml | grep -oE '(tests|failures|errors|skipped)="[0-9]+"' | awk -F'"' '{s[$1]+=$2} END{for(k in s) printf "%s%d ", k, s[k]; print ""}'
          awk '/<testcase /{n=$0; sub(/.*<testcase name="/,"",n); sub(/".*/,"",n)} /<(failure|error)[ >]/{f=FILENAME; sub(/.*TEST-/,"",f); sub(/\.xml$/,"",f); print "   FAILED " f " " n}' "$OUT"/results/*.xml | sort -u
          node "$LANE/printed.mjs" "$OUT/results" "$OUT/printed.txt"; grep -E '^(LiquidJunctionTransientTest|MixedGasJunctionTransientTest) ' "$OUT/printed.txt" > "$OUT/junction.txt"
          [ -s "$OUT/junction.txt" ] && wc -l < "$OUT/junction.txt" || { rm -f "$OUT/junction.txt"; echo "   junction tests not selected: the baseline's lines stand"; }
          save test "$OUT/results";;
    regression) if step_due regression; then
          gradle "$OUT/chain-100.gradle.txt" --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact $(record regression); grep -iE "max deviation|differ|BUILD" "$OUT/chain-100.gradle.txt" | sed -E 's/ in [0-9]+m? ?[0-9]*s$//' | tail -4
          save regression build/test-results/fluidSolverRegression
          else echo "== regression skipped: $(why regression)"; fi;;
    gametest) if step_due gametest; then
          gradle "$OUT/gametests.gradle.txt" --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=${LANE_RUN:-lane-$(date +%m%d-%H%M)} \
            && node "$LANE/affected.mjs" stamp gametest
          grep -E "required tests|failed" "$OUT/gametests.gradle.txt" | sed -E 's/^\[[0-9:]+\] //' | tail -3
          else echo "== gametest skipped: $(why gametest)"; fi;;
    campaign) if step_due campaign || [ -n "${LANE_SEEDS:-}" ]; then ok=true
          fuzz campaign-cold "$FORMS/hold-fuzz-cold" random -PholdFuzzCold=true || ok=false
          fuzz campaign-bundled "$FORMS/hold-fuzz-bundled" "random defaults" "" || ok=false
          grep -E '^NOTE the campaign' "$SEL"
          # Only a run of the watch list or more is the campaign's last run: a replay of a few seeds is not.
          if $ok && [ -z "${LANE_SEEDS:-}" -o "${LANE_SEEDS:-}" = 1-1000 ]; then node "$LANE/affected.mjs" stamp campaign "$OUT"; fi
          else echo "== campaign skipped: $(why campaign)"; fi;;
    *) echo "unknown step $s"; exit 2;;
  esac
done
echo "== $side lane done"
