# A6 Fluid laws and step: report

Branch `claude/review-wave-a6-fluid-18ecaf`, four fix commits. Lane files only; no facts, state, shape or protocol change. Status: code written; tests not run yet (the lane was held for the bench thread).

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

## D-02, heat curves sampled before the Newton (commit e2bf2e92c)
- Each pass's `Equations` samples, when it is built and under the solver's checkpoint, the curves of both ends of every heat-exchanging connection, closed ones included (a Jacobian probe moves a closed flow off zero).
- `Equations.sideHeat` reads only those curves. The solver-level `sideHeat` is removed.
- A refused sample is kept as that end's refusal and is thrown where a residual draws from that end, as the inline sample did. A cancellation from the checkpoint itself ends the pass.
- I read `HeatCurve.sample` first: 17 TP flashes plus refinement. The shared cache keys a curve by the node's step-start state, so a curve is sampled once per end per step, not per pass. The extra cost is one sample per step for the end the flow does not leave.
- A lazy sample at the pass's start point would not cover a flow that reverses inside the Newton.
- I did not use a restart on a missing curve: the workspace carries the previous Jacobian, so a restart would change the Newton's path.
- Values: identical. The curves are the ones the residual sampled before (same start state, port, span and films).
- Counters `heatCurveSamples` and `heatCurvesInResidual` (residual flag in `Equations.residual`) guard the rule.
- Test: `PassiveStepSolverTest.aHeatSideWhoseFlowReversesReadsTheCurvesItsPassSampled` (new). The second step starts from the first step's forward flow, on swapped pressures. The test checks:
  - the flow is reversed and the side cools the water;
  - no curve was sampled in a residual.

## Tests (after "lane free")
`compileJava compileTestJava` once, then each class once, with `--max-workers 4`:
- `CoolingTowerTest`
- `ConservativeTransportTest`
- `PassiveStepSolverTest`

Results: not run yet.

## Expected effect on the parent's final lane
- Chain-100 exact and the 33 junction lines: I expect no movement. They have no cooling tower (F2, F20). D-02 keeps every value, and the junction lines read values, not curve counts.
- Campaign: values identical except for an emptying tower basin fed through a junction (F20), which I expect to be rare.
- Campaign counters: flash and state-call counts rise where islands have heat sides (D-02 samples the second end once per step).
- If a fluid job's checkpoint enforces a work budget, these extra flashes count toward it. A budget-class hold (C or F) on a seed with a heat side would then need a check against this. `MIXED_GAS_COST` moves only if its islands have heat sides.
