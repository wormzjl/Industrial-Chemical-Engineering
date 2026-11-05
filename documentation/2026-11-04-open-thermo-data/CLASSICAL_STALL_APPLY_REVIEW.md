# E30 applied: one water model (a); the stall fix as form H (2026-11-05)

Owner decision E30, "Yes, both": (a) lands alone, F1b returns to design as F1c. O10's record: [CLASSICAL_STALL_REVIEW.md](CLASSICAL_STALL_REVIEW.md); phase 2's stop: the session worktree's `tmp/o10-fix/stop.md`.

## Part 1: (a), the column takes the network's water

- Expected: for the same T, P and amounts, the column and the simulator price water alike; the gap left is what their hydrocarbon and light-end models differ by.
- Code: V3 steam is the network's second-virial gas at its Dalton partial pressure (a steam feed alone at its stage's pressure); liquid and free water are IF97 Region 1 at the node's pressure on the network's datum (`V3WaterProperties.liquidEnthalpyOffset`, bit for bit). Evaluator, Jacobian coupling, duty ledger, auditor and heat bounds price water this way. The fluid network is unchanged.
- New wet formulation revisions: `v3-wet-mesh-r32-steam` and `v3-wet-mesh-r33-steam` (heat), replacing r30/r31. Column seeds are state and rebuild. The bundled sidecar still lists r30/r31, so it binds no wet column until the retrain (E26) labels on r32/r33; dry formulations did not move.
- Gap, simulator minus V3, sheet `alone` (kW; V3's own closure 0.0):

| Case | gap (was) | free water | overhead (light ends) | steam 2.5 vs ~2.7 bar | hydrocarbons, net |
| --- | --- | --- | --- | --- | --- |
| medium, feed at tray | 12.8 (57.5) | 0.0 | 7.5 | 3.9 | 1.4 |
| medium, 1 MPa let-down | 14.2 (59.1) | 0.0 | 10.7 | 3.6 | -0.1 |
| Tia Juana, feed at tray | 10.9 (56.2) | 0.0 | 5.9 | 4.0 | 1.0 |
| Tia Juana, 1 MPa let-down | 13.5 (58.9) | 0.0 | 9.7 | 3.8 | 0.0 |

- Ratio to the condenser duty 2.6e-4 to 5.3e-4: the 2e-3 rule passes with the let-down. The steam row is the block's supply at 2.5 bar against V3's price at its injection node's 2.7 bar, a model-basis difference left as it is.
- What moved in V3: the condenser removes 63 to 67 kW more = free water 93 to 95 - steam 49 + 18 to 22 kW of product re-split (medium at tray: bottoms 28 g/s lighter and 57 mK cooler, distillate +16 g/s, draws +4 to +7 g/s). The real-gas steam absorbs heat as it expands to its partial pressure at the sump and gives it back up the column, so a little heat moves from the sump to the upper trays. Presets: distillate +2 to +11 g/s, condenser +34 to +78 kW.
- The full-budget steam resume stays: with (a) alone `O10FixResumeProbe` resumes once in 3 of 5 cases (steam x0.5, x1.5, top 200 kPa; all 5 converge). It was unneeded only with F1b.
- Pins moved, each by (a):
  - `V3PumparoundSteamCalculatorTest`: sump steam 2,699,311 -> 2,650,036 W, now written as the virial steam at the sump's pressure.
  - `V3SteamFeedContractTest`: the steam product and the arriving steam priced as the evaluator does (8,349 -> 7,824 W); the manufactured state is no longer a root (the steam expands from its injection pressure), so the energy rows and the column closure are checked unchanged by a 10 K warmer drum instead of zero.
  - `RegroupedCrudeTest`: draw-volume targets re-captured, moves up to 3.9e-4.
  - `FlowsheetColumnTest`: furnace ask 98.10 -> 98.08 MW.
  - `V3WaterPhaseQualificationTest`: `tjl20-wet-boundary.json` re-made by `tools/open-thermo-data/WetBoundaryTeacherProbe.java`: condenser outlet 333.9526 -> 333.8533 K, tray 1 at its dew point, released as WET_EQUILIBRIUM.
  - `MaterialCatalogTest` x2: the binding checks use the methane CDU without its steam; the sidecar is unchanged.
- Tests (lane 2, `--tests`): columnGates `science.column.v3.*Test` 523, test 95 (water, steam, network thermo, MaterialCatalogTest), holdGates 23 (FlowsheetColumnTest, ColumnEjectorTest, UtilityRegulationTest), equipmentGates 34: all pass after the V3SteamFeedContractTest edit (9 of 9).
- Network bit-identical: chain-100 exact 0 deviation. 200-seed quick check (4 threads): cold random 197 clean (E1 85, E6 101, F 118), bundled random and bundled defaults 200/200; every seed line equals O11's, and the bundled counters too. The cold total differs only by seed 118, held at a wall deadline: without it, HEAD and (a) give identical counters (199 seeds). Commit `3867e710d`; the column chapter of the code guide changes at the merge.

## Part 2: form H, the stall fix (landed `28d3bd420`)

- Expected: a point the frozen support cannot move does not stall a solve that would converge, and a trace whose root lies below the 1e-10 floor stays free to go there.
- Code (before H): on a cold medium-grade solve a point that feeds live phases sank (PC8 vapour on tray 26 to ln -572 at 633 K, PC8 major on trays 0-25); the refresh then pruned 52 live phases and the solve stalled far from a root. Medium grid 5/10.
- Fix: every trial step holds a cut-path point just under the floor, one whose drop would leave live phases of its component without a path from the feed, by the support's own rule (`V3TruncationSupport.Reachability`). Traces stay free. Held steps that do not halve the residual over the 12-iteration stall window end `STALLED_SUPPORT_FLOOR`, and the refresh takes the solve on the full budget, not counted as a stall.

| Form | Medium grid | Polls | Why stopped |
| --- | --- | --- | --- |
| HEAD | 5/10 | 6,223k | the defect |
| F1b, hold every point | 10/10 | 5,572k | holding traces stalls 43 cold solves; five column tests fail |
| F1c, released by the stall stop | 10/10 | 53,285k | about 10x the polls; six column tests fail |
| G1, hold at the finite-difference limit | 5/10 | 16,231k | no gain |
| G2, release on a singular LU | 8/10 | 3,708k | loses 633 K, which HEAD converges |
| G2b, drop the dead coordinate | 7/10 | 7,783k | loses 633 K: the dead coordinate is a cut path |
| H, hold cut paths only | 8/10 | 5,898k | landed; loses no point HEAD converges; Tia Juana 10/10 at 5,385k (5,804k) |

- Roots equal HEAD to 4 decimals; light, heavy and dilbit polls equal. Tests (lane 2, `--tests`): columnGates 523 (74 classes, plus the 4 H passed before), holdGates 8 classes 63, quick suite 46 classes 535. All pass after two older defects the runs found, each failing identically without H: the sample sizing pins, stale since E30 (a) `3867e710d` (pass at its parent; `5499de72a`: NAPHTHA drop 0.174 to 0.175 bar/100 m, H-101 73.00 to 73.03 MW, E-101 surface 573 to 572 and 579.1 to 578.5 m²), and `crude_unit` lacking CarbonDioxide since O11 `19a544b82` (`50449db3d`, the component list only; every sample pin holds).
- Pin: `FlowsheetColumnTest.aFurnaceOnADutyClosesColdAsFromAFirstEstimate`. The cold first pass (feed 568 K, 21 MW short of the closed loop's 609 K) is a sound column (15/15 audit checks, at least 178 mol/s on every tray), so FAILED was a solver limit. The cold run now closes STEADY, with products within twice the sheet's closure tolerances of the estimated run's. The `loop.first_estimate` hint moved to `FlowsheetSolverTest` (a stub that refuses the empty first-pass tear).
- The owner chose "Yes, land H" (2026-11-05). The steam resume is still reached, once, on the half-steam wet preset (`V3StageContinuationTest`), and stays. Open: 636 and 644 K (whole trace tails sink with nothing live beyond them; the state left does not close at 1e-4 to 1e-3; warm starts unaffected). Code guide: column-v3 chapter at merge. Tools: `tools/open-thermo-data/o10/`.
