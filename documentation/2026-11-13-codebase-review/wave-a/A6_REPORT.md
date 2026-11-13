# A6 Fluid laws and step: report

Branch `claude/review-wave-a6-fluid-18ecaf`, four fix commits. Lane files only; no facts, state, shape or protocol change. Status: code written, the named tests pass (2026-11-13).

## F2, the tower calibration (commit 4cfd22e36)
- `TowerTransfer`: the one static slot is now a map by model, inside a slot for the whole model (`model.whole()`). Each view (an island's compound list) is fitted once (`computeIfAbsent`). A new whole model (a reload, another time factor or package) starts its own map and releases the old fits. The fit itself is unchanged, and `Calibration` drops its unused `model` field.
- Values: identical (the fit reads the same model). Test: `CoolingTowerTest.towersOnTwoCompoundListsFitOnceEach` (new). Two views alternate three times with no refit, and a third list fits once.

## F20, the tower loss in `pinnedFlows` (commit 8dc56051d)
- Reachable. `PassiveStepSolver.emptyingGraph` solves an emptying vessel as a JUNCTION, and `Inventory.with` keeps its tower. `PassiveNetwork.phaseSelectable` (line 51) allows a SPRAY port on a junction that has a tower. The term was therefore kept, not deleted.
- Fix: the pinning reads the loss on the flows with the factors set so far. In flow order, a junction's spray inflows already carry their final factors (an upstream junction's factor is set). The loss depends only on the incoming spray flows (`TowerExchange.stream`), so this is the reading the booking makes at `reconstruct0` on the pinned flows.
- Not covered: in the no-outflow branch (`g_J`), the scaled inflows move the loss after it is read. The junction term books that difference exactly. I left it because the basin would be emptying with no outflow.
- Values move only for an emptying tower basin whose sprays come through a pinned junction. Its outflow factor moves by the loss difference at that junction's residual: before, the loss was read at the unpinned spray flow (in the test, 1.001 kg/s); now at the booked flow (1.0 kg/s).
- `pinnedFlows` is now package-private for the test. Test: `ConservativeTransportTest.aTowerBasinSolvedAsAJunctionPassesItsBookedSprayLessItsLossOnThatSpray` (new).

## F21, the `PhaseDraw` comment (commit 50a26c971)
- The comment now matches the code:
  - a LIQUID port draws both liquids at equal volume rates while both are held (`Segment.mixed`); otherwise it draws first the liquid that runs out first;
  - with one liquid held, the order is oil, water, gas;
  - the comment also names the boot and overflow orders.
- No test.
- Open: the `PassiveNetwork.PhasePort` comment (lines 14-16) still has the old "heavier liquid first" wording. It is outside this lane.

## D-02, heat curves sampled between evaluations (commits e2bf2e92c, then 62416ccaa after the parent's review)
- A pass is built with the curves the solver already keeps (`curveKept`: a curve kept for the stream and span, or an end that draws nothing). No sample at build.
- `Equations.sideHeat` never samples. An end without its curve is noted (`missingEdge`) and reads zero.
- `residual` and `differentiateEntries` are evaluations. One that met a missing end is discarded, whatever it returned or threw. That end is then sampled under the solver's checkpoint, and the evaluation is done again from the same x (at most two per heat side). A refusal is kept as the end's and thrown where an evaluation draws from it. A cancellation ends the pass. No exception carries the deferral.
- The reconstruction reads `bookedHeat`, which samples a missing end first, outside every evaluation. The solver-level `sideHeat` is removed.
- The start-flow donor ends are not guaranteed by the start balance or the seeds (`delivered` runs only on some branches). The first evaluation's deferral covers them, as the first residual did before. So nothing is sampled at build.
- Repeatability check (decode + assemble at one x), with what I found:
  - `cachedStates`, `cachedTransport` and `cachedPrepared` form a per-node cache keyed by x; a redo hits it.
  - `nodeAccumulate` resets every accumulator.
  - `junctionWeight` is set before it is read.
  - `sweepWall` and `builtWall` change only in `linearising`.
  - `allowedRestart` and `flashRestated` are read only in `buildInitial` and the pass loop.
  - The viscosity workspace is scratch.
  - The checkpoint is cancellation plus a wall deadline, with no counter.
  - `SolverDiagnostics` counters count the redo, but no decision reads them.
  - Nothing else is carried.
- Values: identical. The same curve enters the same evaluation at the same x. Each curve is sampled where it was first needed, as before, and no extra sample is made.
- Tests: `PassiveStepSolverTest`
  - The reversal case: the forward step samples one curve; the backward step reverses the flow, cools the water and samples no curve inside an evaluation.
  - New: `aHeatSideThatKeepsItsDirectionSamplesItsDonorsCurveAlone`: one sample per step, none for the junction end.

## Tests (after "lane free")
- First run, one `test` run with `--max-workers 4` and a `--tests` filter per class, on the eager D-02 form: `CoolingTowerTest` 25/25, `ConservativeTransportTest` 4/4, `PassiveStepSolverTest` 7/7. These stand for F2 and F20.
- After 62416ccaa: `PassiveStepSolverTest` 8/8, run once. 0 failed and 0 skipped throughout.
- No reference moved. Every existing assertion holds unchanged (calibration point 298.15 K, the marched-fill references, the ledgers).

## Expected effect on the parent's final lane
- Chain-100 exact and the 33 junction lines: I expect no movement. They have no cooling tower, and D-02 keeps every value and every sample.
- Campaign: values identical except for an emptying tower basin fed through a junction (F20), which I expect to be rare.
- Counters: an evaluation redone after a deferred sample adds to the residual and Jacobian counters only where a flow reaches a new heat-side end, the case that sampled inside the Newton before. Flash counts are unchanged.
