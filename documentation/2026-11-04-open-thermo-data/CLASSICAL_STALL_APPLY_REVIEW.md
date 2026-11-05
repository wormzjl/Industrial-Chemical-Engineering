# E30 applied: one water model (a); the stall fix as F1c (2026-11-05)

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
- Network bit-identical: chain-100 exact 0 deviation. 200-seed quick check (4 threads): cold random 197 clean (E1 85, E6 101, F 118), bundled random and bundled defaults 200/200; every seed line equals O11's, and the bundled counters too. The cold total differs only by seed 118, held at a wall deadline: without it, HEAD and (a) give identical counters (199 seeds).
- Commit: `3867e710d`. Code guide: the column chapter's water pricing changes; recorded for the merge.
