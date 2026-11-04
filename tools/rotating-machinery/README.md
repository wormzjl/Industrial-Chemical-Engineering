# rotating-machinery

Purpose: the measurement and investigation tooling of batch [`documentation/2026-11-03-rotating-machinery/`](../../documentation/2026-11-03-rotating-machinery/README.md)
(plan section 4.5): the quick check of the machine-law forms and of a centrifugal variant of the hold-fuzz harness's
in-line movers, the plan's worked numbers, and the temporary traces and counterfactual probes the W2 hold reviews used.
Nothing here runs in the mod or in a gate.

Batch: `2026-11-03-rotating-machinery` (W2, W10a to W10b campaigns). Status: in use since 2026-11-04; the campaign
harnesses stay for later machine-law work.

## How to run

| Path | What | How to run |
|---|---|---|
| `hold-fuzz-cold/`, `hold-fuzz-bundled/` | Copies of the merge lane's harnesses (`tools/unified-thermo-port-forms/hold-fuzz-*`) with a switch (and until 2026-11-04 a second, `-PholdFuzzCornerLaw=true` for form F2, now the code's only law). `-PholdFuzzMachines=true` places each in-line mover as a machine on its curve at full speed, rated from its settings on the same draws (`HoldFuzzTest.machine`): a pump's shutoff rise is its limit on the reference water; a compressor's shutoff head is the polytropic head of its ratio limit on nitrogen at 298.15 K. The world labels keep the drawn settings. | through `quick-check.sh` |
| `quick-check.sh` | 200 seeds of cold random, bundled random and bundled defaults, 8 workers, solver counters, one Gradle invocation at a time; classifies each report with `tools/phase-ports-probes/hold-fuzz/classify.sh` and warns when test or client JVMs run | `bash tools/rotating-machinery/quick-check.sh <label> [-PholdFuzzMachines=true]`; reports in `tmp/rotating-machinery/quick/<label>/`. A replay alone: `ONLY=hold-fuzz-cold-random SEEDS=6 bash tools/rotating-machinery/quick-check.sh <label> <flags>`; a full campaign: `SEEDS=1-1000` |
| `compare.js` | Held seeds by class against a baseline campaign on the same seeds (new and gone), refused registrations, then each run's solver counters | `node tools/rotating-machinery/compare.js <baseline dir> 200 <run dir>...` |
| `wait-free.sh` | Waits until no test, dev-client or campaign JVM runs on the machine, then returns (AGENTS.md: no suite beside a dev client) | `bash tools/rotating-machinery/wait-free.sh && <run>` |
| `worked/examples.js` | The plan's worked examples: pump operating point and loss heating, compressor head, reciprocating volumetric efficiency, turbine steam rate, shaft sizes, energy per tick (`ROTATING_MACHINERY_PLAN.md` section 1) | `node tools/rotating-machinery/worked/examples.js` |
| `worked/curves.js`, `worked/curves2.js` | The normalized laws of plan section 3 (pump, centrifugal compressor with surge, positive-displacement corner, turbine drop); `curves2.js` the revised pump curve with its linear term, the damped speed step of a power-limited driver | `node tools/rotating-machinery/worked/curves.js` |
| `worked/two-phase-laws.js` | The worked numbers of `TWO_PHASE_MACHINES_RESEARCH_REVIEW.md`: Turpin gas onset and head share, NPSH3, cavitation intensity and wear life, wet-gas fractions | `node tools/rotating-machinery/worked/two-phase-laws.js` |
| `probes/trace-fail.js`, `probes/trace-gate.js` | Temporary traces of `PassiveStepSolver`: a failed Newton's worst row, edges and nodes; an equation-gate refusal's four worst rows at the reconstructed point. Used on the F2e campaign's seeds 896 and 952 (`ea840a2d6`, `W2_CAMPAIGN_REVIEW.md`) | see below |
| `probes/trace-d14.js`, `probes/probe-d14-b.js` | D14's suction-floor trace and its counterfactual B (close a pump whose suction line delivers nothing at the floor), on `ffde40d7f` (`D14_HOLDS_REVIEW.md`, seeds 896 and 181) | see below |
| `probes/trace-d15.js`, `probes/probe-d15-p.js`, `probes/probe-d15-c.js` | D15's active-set cycle trace and its counterfactuals P (the vapour-lock test at no flow on the start states) and C (bubble pressure at the liquid's enthalpy), on `93159d42d` (`D15_HOLDS_REVIEW.md`, seeds 575 and 181) | see below |
| `probes/f2c.patch` | Form F2c (the corner as a flow, `u = 1 − v^64`), measured and not taken (`W2_CORNER_REVIEW.md`) | `git apply -3 tools/rotating-machinery/probes/f2c.patch` on `8c396c1e5` (its blob ids are those of the pre-rebase copy `7c7c0132e`) |
| `probes/corner-law-test.init.gradle` | Init script that ran the unit tests with the set-flow movers on the corner law (`icheme.fluid.cornerLaw`, form F2b) before F2b became the only law at `8c396c1e5`; only for commits `7e66ba393` to `3eb801811` | `./gradlew test --tests <class> --init-script tools/rotating-machinery/probes/corner-law-test.init.gradle` |

The traces and probes edit `src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java` in place, line-ending
aware, and fail when an anchor does not match once. They were never committed. On a scratch worktree at the commit named
above: `node tools/rotating-machinery/probes/trace-d15.js src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java`,
then a probe the same way (each probe goes after its trace), then a replay alone with `quick-check.sh` (`ONLY=... SEEDS=...`);
restore with `git checkout -- <file>`.

Baseline: the 0.19.2 final lane's campaign (1000 seeds per distribution), from the main checkout's
`tools/dry-ice-cost/logs/final-lane-logs-2026-11-03.tar.gz` (`tar --force-local -xzf`), whose seeds 1 to 200 the quick
check compares against. Main 0.19.3 changed no solver code since.

## Entry points

- `quick-check.sh <label> [Gradle flags]` with the environment `ONLY`, `SEEDS`; `compare.js <baseline> <seeds> <runs...>`.
- `hold-fuzz-*/src/HoldFuzzTest.java` (`HoldFuzzTest.machine`, `World.describe`), applied by `hold-fuzz-*/hold-fuzz.init.gradle`.
- `worked/*.js`, `probes/*.js` (each takes the solver source path), `wait-free.sh`.

## History

- 2026-11-04: created for W2's quick check on commit `7e66ba393` (F1, F2 and the centrifugal variant Q3).
- 2026-11-04: `ONLY` (one harness and distribution, a replay alone); the F2b quick check on `3eb801811` and the replay alone of
  Q3 cold seed 6.
- 2026-11-04: the full campaign of `ea840a2d6` (F2e with A1 and A2, `SEEDS=1-1000`, 6 min) and the verbose replays of seeds
  896 and 952; `HoldFuzzTest.World.describe` (bundled copy) prints a held island a later event merged away as gone instead
  of throwing.
- 2026-11-04 to 2026-11-05: the D13 to D17, W2-turbine, W3, W5, W10a and W10b full campaigns ran through `quick-check.sh`
  (reports in the session's `tmp/rotating-machinery/quick/`).
- 2026-11-05 (tooling cleanup): the worked numbers (`worked/`), the traces and probes of the W2 hold reviews, F2c's patch,
  the corner-law init script (`probes/`) and `wait-free.sh` moved here from the session's `tmp/rotating-machinery/`.
