# unified-thermo-port-forms

Purpose: the scratch tooling of the unified thermo port's design-review forms (head, A, A + D33, form 4), the gates'
rule-off proofs, the fixes committed after the form choice (D32, the typed holds, F4), and the follow-ups after the
report: F2 (the owner's fix A) and the D23-1 gate; M4c: E8 and D33's gap (item 1, the freezing-point rule, stopped at its
survey and was superseded by the range-extrapolation plan). Batch
`documentation/2026-09-24-coolprop-low-temperature/` (review `UNIFIED_THERMO_PORT_REVIEW.md`, report
`M4B_REVIEW_FORMS.md`), branch `claude/unified-thermo-port`, 2026-10-16. Nothing here was committed or detached from a
tracked path: every gate is a tracked test (`PrecipitatedSolidsTest`, `LoneBoundaryJoinTest`, `NetworkPhaseEngineTest`,
`WaterIntoCryogenicMethaneTest`, `SlurryTankPumpTest`, `LiquidCo2LetDownTest`). Commits: 58d9dac4 and 2ab43f23 (form 4),
15220a07 (D32), 81129851 (typed holds), 60b62694 (F4), ed2cdff4 (F2, fix A), c63e9a8b (the D23-1 gate), b46b4a81 (E8),
1b75ce08 (D33's gap); gates b6fb08f8.

| Path | What | How to run |
|---|---|---|
| `scripts/export.sh` | Exports a commit's sources with `git archive` to `/tmp/forms-<NAME>` (read only on the worktree), applies `PATCH=` patches, `PROBE=1` adds the probe | `NAME=x PATCH=/tmp/p.patch bash scripts/export.sh <commit>` |
| `scripts/index-compile.sh`, `index-run.sh`, `M3Run.java` | The javac lane outside Gradle (from M3's): compiles an export against `build/moddev`'s classpath with the cold harness and `EXTRA_TESTS`, runs JUnit selectors at `-Xmx6g` | `bash scripts/index-compile.sh x`, then `bash scripts/index-run.sh x pkg.Class#method` |
| `scripts/run-seed.sh` | One cold-fuzz seed (120 slices, counters) on the lane; waits while a dev client is live | `bash scripts/run-seed.sh x 48 label` (LOGS default `/tmp/forms-logs`) |
| `harness/HoldFuzzTest.java` | The cold hold-fuzz harness the lane compiles (M3's, with the counters) | through `index-compile.sh` |
| `hold-fuzz-cold/`, `hold-fuzz-bundled/` | The Gradle hold-fuzz harnesses of the quick checks (init script + `src/`) | `HARNESS=$PWD/hold-fuzz-cold DISTS=random EXTRA=-PholdFuzzCold=true OUT=build/forms-logs bash ../unified-thermo-port-m3/fuzz.sh <label>`; bundled: `HARNESS=$PWD/hold-fuzz-bundled` |
| `probe/FormsProbe.java`, `apply-probe.pl` | How a vessel's own split ends when its closure stops short (vapour share at start, last iterate and after the fold, residual, the closure's direction), printed at exit as `FPROBE` | `PROBE=1` in `export.sh` |
| `probe/apply-dump.pl` | Prints a stalled own split's point as Java literals (gate (a)'s and form 4 (ii)'s rigs were read from it) | `perl probe/apply-dump.pl /tmp/forms-x` |
| `probe/apply-trace.pl` | The untyped TP refusal (E9) by call path, phaseCheck's refused flashes, D33's dry-donor gas draws (seeds 55, 101) | `perl probe/apply-trace.pl /tmp/forms-x` |
| `probe/apply-e8.pl` | Form 4's E8 trace: the reconstruction's "does not meet full equation closure" by path, regime and residual (seed 48) | `perl probe/apply-e8.pl /tmp/forms-x` |
| `probe/E8ProbeTest.java` | M4c: seed 48's E8 tank stepped alone under a feed's rise and a step size; found the E8 gate's rig (100 Pa, 1e-6 s) | copy into an export's test tree, `EXTRA_TESTS`, run the class (header) |
| `probe/apply-ruleoff-criterion.pl` | The review's rule-off probe 3 with form 4 (ii)'s criterion evaluated on every stalled split | `perl probe/apply-ruleoff-criterion.pl /tmp/forms-x` |
| `rule-off/apply.pl` | Switches one rule off in an export for its gate's proof: d26, f4ii, f4i (also the D23-1 gate), d33g, d33r, e9, water, f4after, f4always, f2seed, f2inlet, e8, d33i (header lists the gate of each; d33r and f2inlet match b46b4a81, before D33's gap moved both readings to the interval start) | `perl rule-off/apply.pl /tmp/forms-x <rule>`, compile with the gate in `EXTRA_TESTS`, run the one gate |
| `scripts/test-moves.js`, `scripts/f2-affected-classes.txt` | F2's affected-class check: the 51 classes (pump, inlet, solids transport, mint, M3's) run in one lane JVM on the head and on F2, compared test by test | compile both exports with the classes in `EXTRA_TESTS`; `JAVA_OPTS=-Dcreatecheme.fluid.scheduler.verify=true bash scripts/index-run.sh <name> <classes...>` (the Gradle test task sets that property); `node scripts/test-moves.js <head log> <F2 log> [show]` |
| `scripts/m4c-affected.sh`, `scripts/m4c-affected-classes.txt` | M4c's affected classes: F2's 51 plus 18 closure, fold, rest and gate classes, on the head 1b15fd43 and the final 1b75ce08, one lane JVM each | `bash scripts/m4c-affected.sh` (logs to `/tmp/m4c-logs/m4c`), then `node scripts/test-moves.js` |
| `staging/0001-*.patch`, `0002-*.patch` | Form 4 (i) and (ii) as staged against c9e7c128 before the owner's go (committed as 58d9dac4, 2ab43f23) | `git apply` on an export of c9e7c128 |
| `patches/d32-wip.patch`, `typed-hold-wip.patch` | Work-in-progress diffs of D32 and the `VesselCrystals.frozen` hold before their commits | reference only |
| `logs/forms-logs.tar.gz` | `build-forms-logs/`, the worktree's build/forms-logs (04-07 quick checks of A and A + D33, 21-22 form 4, 31 the full `test` and its results, 32 printed lines against M4's 05, 41 the bundled quick check of F2 at c63e9a8b); `tmp-forms-logs/`, the lane's /tmp/forms-logs (01-03 seeds 48 and 183 under A, 08-09 traces of seeds 55 and 101, 10-11 dumps, 17 the rule-off criterion, 23-24 seeds 48 (E8) and 183 under form 4, `gates/12-33` every gate and rule-off run; `f2/` F2 and D23-1: 01-14 the gates on the final code and with f2seed, f2inlet and f4i off, 15-18 the affected classes on the head and on F2, 17-18 with the Gradle property); `cmp/` (below); `m4c/` (M4c: 00 the E8 probe on the head, 01-02 the E8 gate with e8 off and on, 03-04 gate (c) on D33's final and with d33i off, 06 and 10 the affected classes on the head and the final, 07 the final before two tests were restated, 08-09 the restatement's trials, 11 FluidCheckpointFormatTest with the Gradle property) | `tar --force-local -xzf logs/forms-logs.tar.gz` |

The full test's printed-line attribution (which commit moved which class) ran the moved classes on the lane at
60b62694, 2ab43f23, c9e7c128 and 40a44732 (`/tmp/forms-cmp/bisect.sh`, `cmp.js`, archived in the logs as `cmp/`).

## M4c (2026-10-16)

E8 (b46b4a81) and D33's gap (1b75ce08), each with its gate and rule-off proof (rules e8 and d33i, logs `m4c/01-04`); the
affected classes (`scripts/m4c-affected.sh`, logs `m4c/06`, `10`); the E8 probe (`probe/E8ProbeTest.java`). No quick check ran:
they run once after the range-extrapolation change. Report: `documentation/2026-09-24-coolprop-low-temperature/M4C_FREEZING_RULE_AND_FIXES.md`.

## Rename to IChemE (2026-10-21)

Batch `2026-10-21-rename-icheme`: the files this folder runs against the current tree use the package `com.wormzjl.icheme` and the namespace `icheme` (mod rename, `8033aed8`). Patches, diffs and probes tied to an older commit keep `createcheme`: they re-attach to that commit. The absolute folder path `D:/Minecraft/Modding/1.21/CreateChemE` is unchanged. A worktree copy of this folder (for example the lane worktree `coolprop-multiphase-thermo-37f6b0`) is refreshed from here before its next run.

`harness/HoldFuzzTest.java` (the M3 counters harness) does not compile against 0.10.x: its `Pump` and `Compressor` constructor calls predate the current records. The drift is older than the rename; `hold-fuzz-cold/` and `hold-fuzz-bundled/` compile on the d40 javac lane (main=0, test=0).

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: The design review's forms (head, A, A + D33, form 4) on the 200-seed cold and bundled quick checks, the gates' rule-off specs (`rule-off/apply.pl`, rules incl. `f2seed`/`f2inlet`), staged patches, the full test and bisect logs

Batch: 2026-09-24-coolprop-low-temperature

Status: Done 2026-10-16. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`; `rule-off/`

### History

- Done 2026-10-16 (form 4 chosen; `c63e9a8b` on `claude/unified-thermo-port`, merged 2026-10-19 in 0.9.0 (main `5b49f940`, not pushed); report `M4B_REVIEW_FORMS.md`)
- 2026-10-28 (batch 2026-10-27-heat-exchanger-design, merge lane): `hold-fuzz-cold` and `hold-fuzz-bundled` place a drawn pump or compressor in line on a straight pipe joined along its facing, and an upright one as that pipe without its mover. Since 0.16.2 the engine refuses portless movers, so the old harness had them refused and skipped. The draws are unchanged, so a seed keeps its layout; the new baseline is main 0.16.2 with this harness.
