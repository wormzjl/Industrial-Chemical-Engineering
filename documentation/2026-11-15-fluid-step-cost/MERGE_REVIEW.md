# Fluid step cost: merge review (final lane)

## Summary

The final lane ran once on `claude/fluid-step-jacobian` at `4ee2cb0f8` (tree clean), 2026-11-15. It ran as `lane.sh head test regression gametest`, then `lane.sh head campaign` after a stop on the moved printed lines. Output: `tmp/merge-lane/head/`. Baseline: the unit 6 lane, `tmp/merge-lane-unit6/head` (code of 0.31.0); for the junction lines and `MIXED_GAS_COST`, the unit 5 lane, because unit 6 did not run them. Counters only, no times.

- **test:** 2,956 tests, 0 failures, 0 errors. The selection took 237 of 464 `test` classes, all 10 `holdGates` and 1 `columnGates` class. The `test` filters were over 8,000 characters, so the lane ran `test` whole (its own rule).
- **columnGates:** "No tests found", exit 1. This is a lane-tool defect: the selection names `NativeColumnSolverTest`, which `build.gradle` excludes from `columnGates` (it runs in `nativeSmoke`). The baseline lane failed the same way. The batch changes nothing under `src/main/rust`. Not fixed here (finding 1).
- **regression:** chain-100 exact, every deviation 0. No re-record.
- **gametest:** 98 of 98.
- **Junction lines:** counters identical on 33 of 33. Floats move at round-off: `worstMoles` at 1e-16, `junctionShare` by 2e-14 relative.
- **`MIXED_GAS_COST`:** 313 Newton solves before and after. Allocated memory 318.7 to 125.1 MB (S2, S2b).
- **`VIRTUAL_HOLDUP_DIGEST`:** the two mixed-gas lines change (0.1 s `0a41112f…` to `373fc192…`; 5 s `a87ce950…` to `c32fbc96…`). The four liquid lines are unchanged.
- **No asserted reference moved; nothing was re-recorded.** Conservation is unchanged: every ledger and conservation assertion passed.
- **Printed lines:** most moved lines move by 1e-16 to 1e-10 relative. Five move further (table below). Baseline lanes 5 and 6 agree bit for bit on all five, so the batch moves them (S4: the closure reaches its root within `CLOSURE = 1e-11` by another path).
- **Campaign** (factor 4, 1000 seeds, 8 threads):

| run | clean, base → head (lane) | after replays | new holds | fixed holds | class moved |
|---|---|---|---|---|---|
| cold random | 992 → 991 | 993 | 6 F, 826 F | 743 E5 | 0 |
| bundled random | 991 → 993 | 994 | 707 F | 12 F, 59 F, 118 F | 0 |
| bundled defaults | 1000 → 1000 | 1000 | none | none | 0 |

- **Campaign replays:** the three new F holds (wall deadline) were replayed alone once on one thread. Cold 6 and 826 ran together, bundled 707 alone. All three are clean, at the baseline's end ticks (6: 14021, 826: 13577, 707: 13068).
- **Standing holds:** as in the baseline, bundled 47, 579 and 941 (F), 241 (E6), 145 and 431 (E9), and cold 431 (F). Bundled 47 was clean in the baseline's replay; by the rule it was not replayed again. Counting it as the baseline did gives bundled random 995. Refusals 0 on every run.
- **Campaign Newton counters:** solves cold 327,668 to 326,233, bundled 131,163 to 131,194, defaults 26,997 to 27,002. Iterations cold 1,989,344 to 2,022,572 (+1.7 %), bundled 635,214 to 637,072, defaults 65,447 to 65,433. The held and fixed seeds change how far their runs get, so these sums are not like for like.
- **Tool folder:** `tools/fluid-step-cost/` holds the census probe, the bench scripts and readers in `bench/`, and the observer hook as `pattern-observer.patch`. The hook left `src/main` in `cdb2833e1`.
- **Code guide:** updated, snapshot `cdb2833e1`.
- **Translation:** no translation commit; `en_us.json` did not change.
- **CHANGELOG:** the entry is under 0.31.1 (the owner's choice: "Merge as 0.31.1"); `mod_version` is 0.31.1.
- **Logs:** `tools/merge-lane/logs/fluid-step-cost-final-lane-logs-2026-11-15.tar.gz` in the main checkout (git-ignored).
- **The five moved lines:** accepted by the owner on 2026-11-15, in reply to "accept the moves as path splits at events": "it's fine".
- **Status:** every new campaign hold is a wall-deadline hold that was clean on its replay alone. Ready for the merge.

## The five printed lines that move beyond the Newton tolerance

| Line | Old (0.31.0) | New (`4ee2cb0f8`) | What the evidence shows, and what it does not |
|---|---|---|---|
| `BlanketDrainWorldTest` `BLANKET_DRAIN drain` path | tick 400: share 0.017761, P 92,178 Pa | tick 400: share 0.035331, P 84,690 Pa | A path split at one event. The two runs agree to 3e-15 on every printed line through drain tick 380. Between ticks 380 and 400, when the last liquid leaves the drum, they separate. The event itself is not traced. |
| `BlanketDrainWorldTest` `BLANKET_DRAIN_RESULT` and the drum at the end | low 0.0021144; drum 30.190 kg, 106,141 Pa; pad valve massTarget 1.05478 | low 0.0028874; drum 32.163 kg, 105,402 Pa; massTarget 1.04016 | The consequence of the split above. After it both runs hover at a share of 0.002 to 0.005: the same behaviour, with no steady state. Both end held=clean and pass every assertion. |
| `FluidPumpedFillLineTest` STEADY certificate | drift 1.452311e-8, largest flow 1.503723e-6 kg/s | drift 1.454248e-8, largest flow 1.503828e-6 kg/s | The drift moves by 2e-11 absolute, the closure's tolerance; the largest flow by 1e-10 kg/s absolute. The state is certified steady inside the certificate's tolerance. |
| `HoldClassWorldsTest` `STATIC_COLUMN_WORLD` | gas column 44.99748 Pa, drop line 1.070424e-6 kg/s | gas column 45.00464 Pa, drop line 1.067149e-6 kg/s | The drop line moves by 3e-9 kg/s absolute. The gas column (a difference of two pressures of 429 kPa) moves by 0.007 Pa, 1.7e-8 of the pressures. Only end states are printed: the cause is read from the code (a STEADY state settles inside the certificate's drift), not traced. |
| `HoldClassWorldsTest` `REFUTED_PROPOSAL` island 11, nodes 4 and 8 | node 4: 2,270,034 Pa, 403.2212 kg, solids 27.452 kg; node 8: 2,281,897 Pa | node 4: 2,364,113 Pa, 403.2874 kg, solids 27.323 kg; node 8: 2,375,978 Pa | Pressure +4.1 %, mass +1.6e-4. Both nodes are liquid-full (liquid 0.98 with crystals), so their pressure follows the held mass through the liquid's stiffness: 1.6e-4 times a bulk modulus of about 0.6 GPa is about 0.1 MPa. Only end states are printed: the path difference behind the mass is read from the code, not traced. |
The bound the brief named (the closure 1e-11, the step's Newton 1e-9) holds for every line that follows one solve. A line that follows a trajectory keeps the step's time-error control instead: an accepted attempt carries an error of about 1e-3 (chain-100's attempts). A line taken at a STEADY certificate keeps the certificate's drift tolerance. No run was made to trace a split. The owner accepted the moves on 2026-11-16 ("it's fine").

## Findings

| # | Finding | Proposal |
|---|---|---|
| 1 | `affected.mjs select` names `NativeColumnSolverTest` for `columnGates`, which excludes it, so the task fails with "No tests found" whenever that class is the only one selected (here and in the unit 6 lane). | Give the class to `nativeSmoke` in the selection, or leave out of a task the classes it excludes. A tools change, not made here. |
| 2 | Five printed lines move beyond the Newton tolerance (table above); no asserted reference moved. Accepted by the owner, 2026-11-15: "it's fine". |
| 3 | The lane's `test` filters pass 8,000 characters, so `test` runs whole (2,956 tests against the 237 classes selected). | None for this batch; the lane's existing rule. |
