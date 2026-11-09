# Warm restart of carry-over columns (E32)

Status: built 2026-11-08 by an Opus-high subagent and tested on lane 2 the same day. It missed its own target and failed two hold-gate classes, and the owner reverted it the same day ("B, revert E32", E33). It was never committed; the code stays in `tmp/warm-carryover/warm.patch` and `new-V3WarmCarryOverTest.patch`.

## 1. Expected behaviour

- A running column re-solves a small drift (feed 2 K warmer) from its last state in under a second, and lands on the answer a cold solve gives, within the closure.
- A flowsheet's answer is a function of its inputs, not of the order its passes ran in.

## 2. What E32 does

- One predicate, `V3NeuralSeed.warmCorrectable`: a dry seed, or one wet only on a carry-over tray 1. `ColumnSolve.run` keeps such a seed, and `warmStartPass` solves the seed's own wet set (no fixed-water prepass), publishing only on the requested geometry.

## 3. Measured (lane 2, 4 workers, beside the retrain's 16-thread relabel)

- `V3WarmCarryOverTest` passes: the literature preset at the prescribed drop re-solves warm onto the cold answer within 1e-6.
- `ColumnSolveTest` still goes cold. A probe (prints removed) shows:
  - the seed is admitted: tray 1 wet, F_1 = 20.3 mol/s, 8 m column;
  - the warm pass fails MAX_ITERATIONS after 8 steps (scaled residual 0.11 to 7.6e-5), with F_1 driven to 0 and tray 1's energy row 5.6 kW open.
- Cause: the seed is the state published on the 8 m tray-hydraulics profile, but the warm pass solves on the nominal profile.
  - F_1 is a small difference of large flows (S - W_1), so it moves far with tray 1's pressure. The E31 fixture measured 14.05 mol/s at 8 m against 1 mol/s at the prescribed drop.
  - The mechanism applies to every carry-over column with a diameter.
  - A failed warm pass then runs the strict cold chain (`SolvePolicy.OFF`), not the seedless fast solve it replaced.
- `FlowsheetColumnTest.aFurnaceOnADutyClosesColdAsFromAFirstEstimate`: both runs close, on different states (OFFGAS 2.57 kg/s cold, 2.03 kg/s from the estimate). It passed on E31.
  - `ColumnUnit.solve` always passes its seed. Before E32 a carry-over seed was refused and re-solved cold, so the column's answer was a function of its inputs.
  - With E32, a warm restart from the cold run's first pass (feed 568 K, 41 K short) stays on another column solution. The sheet has two steady states, and the route decides which one shows.
- `SimulatorRunTest`, 2 methods: bit-identical runs differ in RESIDUE's third component, 1.0023897e-8 against 1.0023899e-8. This is likely the same path dependence; the learned start's wall-clock budget under contention may add to it. Not replayed alone.
- The other 19 quick-suite classes (305 tests in all, 1 failure) and the other 6 hold-gate classes pass.

## 4. Options (decided: 1, E33)

1. **B, revert E32 (recommended).** Carry-over columns re-solve cold: about 3.6 s heavy crude and 11 s dilbit, on the engine worker. Answers stay path-independent, and E31's code passed every hold gate. `ColumnSolveTest` asserts the cold route for its carry-over column; a dry variant keeps the warm-route assertion.
2. **A2, a profile-aware warm route.** The seed carries its published node pressures (process-local state, no save impact), and the warm pass solves on that profile, so F_1 starts at its root. This fixes the running column's case. More warm restarts would then succeed, so the furnace sheet keeps two steady states. It needs a rule for which one the game shows: a new subagent and a wider test round.
3. **A3, A2 for the running column only.** `ColumnUnit` drops carry-over seeds, so flowsheets stay path-independent. A running column near two steady states then shows a history-dependent answer (hysteresis).

Landed (E33): the E32 code is discarded. `ColumnSolveTest` passes 8 of 8; its new dry variant (top cooler halved, 8 m) re-solves warm. `ColumnRunTest` passes 32 of 32 on E31's code.

Physics note: two closed states of one sheet are plausible. A furnace on a fixed duty with a feed-effluent exchanger is a thermal loop, and the energy-specified column with carry-over adds its own nonlinearity. Which state is stable was not measured.

Code guide: no change unless an option lands.
