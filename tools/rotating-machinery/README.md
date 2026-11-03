# rotating-machinery

Purpose: the measurement tooling of batch [`documentation/2026-11-03-rotating-machinery/`](../../documentation/2026-11-03-rotating-machinery/README.md)
(plan section 4.5): the quick check of the machine-law forms F1 and F2, and of a centrifugal variant of the hold-fuzz
harness's in-line movers.

| Path | What | How to run |
|---|---|---|
| `hold-fuzz-cold/`, `hold-fuzz-bundled/` | Copies of the merge lane's harnesses (`tools/unified-thermo-port-forms/hold-fuzz-*`) with a switch (and until 2026-11-04 a second, `-PholdFuzzCornerLaw=true` for form F2, now the code's only law). `-PholdFuzzMachines=true` places each in-line mover as a machine on its curve at full speed, rated from its settings on the same draws (`HoldFuzzTest.machine`): a pump's shutoff rise is its limit on the reference water; a compressor's shutoff head is the polytropic head of its ratio limit on nitrogen at 298.15 K. The world labels keep the drawn settings. | through `quick-check.sh` |
| `quick-check.sh` | 200 seeds of cold random, bundled random and bundled defaults, 8 workers, solver counters, one Gradle invocation at a time; classifies each report with `tools/phase-ports-probes/hold-fuzz/classify.sh` and warns when test or client JVMs run | `bash tools/rotating-machinery/quick-check.sh <label> [-PholdFuzzMachines=true]`; reports in `tmp/rotating-machinery/quick/<label>/`. A replay alone: `ONLY=hold-fuzz-cold-random SEEDS=6 bash tools/rotating-machinery/quick-check.sh <label> <flags>` |
| `compare.js` | Held seeds by class against a baseline campaign on the same seeds (new and gone), refused registrations, then each run's solver counters | `node tools/rotating-machinery/compare.js <baseline dir> 200 <run dir>...` |

Baseline: the 0.19.2 final lane's campaign (1000 seeds per distribution), from the main checkout's
`tools/dry-ice-cost/logs/final-lane-logs-2026-11-03.tar.gz` (`tar --force-local -xzf`), whose seeds 1 to 200 the quick
check compares against. Main 0.19.3 changed no solver code since.

## History

- 2026-11-04: created for W2's quick check on commit `218f9c5ff` (F1, F2 and the centrifugal variant Q3).
- 2026-11-04: `ONLY` (one harness and distribution, a replay alone); the F2b quick check on `ffae9fc4d` and the replay alone of
  Q3 cold seed 6.
