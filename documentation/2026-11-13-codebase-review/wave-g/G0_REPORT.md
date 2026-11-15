# G0: the driver pays the fluid's shaft power (M-01, M-07)

Branch `claude/review-wave-a5-runtime-1a45f8`, merged with the review branch at a126465e0. Owner decisions A2 and B1, sent by the main thread. Two commits. **Tests pending: no Gradle run yet.**

## Premises found false (decision request answered A2, B1)
- **No booked transfer.** `ConservativeTransport` books a pump's, compressor's or machine's work only into the aggregate `pumpWork`; only a turbine gets a `SHAFT` `BoundaryTransfer`. A per-connection array would have needed `IslandCertificate.replay`, the checkpoint state format (50 → 51) and the other `Result` builders. Decision A2: none of that. The charge goes through the solve's own function at the booked average flow and head. The brief's "to the last bit" is withdrawn.
- **Booked work is not shaft work for liquids.** A liquid mover books the ideal `q·Δp` (simplification S1). Decision B1: liquids are charged over their efficiency; gases are charged their shaft power.
- **The `/ .65`.** Its history (97ae9c6d8, 2260953dd) gives no reason; the rating's doc reads "over 65 %". On a set-flow pump it is the pump efficiency, a real loss the solve does not book, so it is kept as `InlineMachines.SET_FLOW_PUMP_EFFICIENCY`. On a set-flow compressor it was a second efficiency, so it is removed.

## Commits
| Item | Commit | Change |
|---|---|---|
| M-01 | 4f7089eab | `PassiveStepSolver.suctionKappa` and `wetShare` are made reachable; the solve's `kappa` and `readSuctions` call them, so the solve's values are unchanged. New `InlineMachines.shaftPower` (machine: law's shaft power / wet share; set-flow pump: work / .65; other: its power). `ShaftTrains.booked` (Machine, Compressor) and `ElectricStores.booked` (in-line) charge it at the slice's average flow, the booked head and the suction's k and wet share. `ratedShaftPower` and `ColumnPower` use the same function on the design fluids (design figure: water, or dry air with k 1.4) |
| M-07 | 15d2f7f00 | `Duty` keeps the suction's k (lazy form kept); `requiredSpeed` and a frame's duty rise limit read it |

## Where a watt goes (for the CHANGELOG)
Electricity in = work to the fluid + three stated losses:
1. **A pump's hydraulic loss.** A machine's curve shaft power over its ideal work (η at the point, 0.7 at a typical rated point), and a set-flow pump's 1/0.65. The solve does not book it (S1: it would warm the liquid by about 0.15 K at the rated point).
2. **A centrifugal compressor's recycle work** below surge (the law's machine flow). It is charged, but the fluid does not get it as net flow.
3. **The motor's and the drive's efficiency** (`Motor.electricPower`, the in-line integral motor and VFD, `InlineMachines.electricPower`).

## Shifts (stated; no fluid result moves)
Electricity draws, store lives, generator loads, page power and Auto speed move, by mover:
- **Gas frame:** k = suction's instead of 1.4: about −12 % on propane at ratio 3 (k 1.13), −4 % on steam or methane. Endpoint flow → average flow (equal at a steady state).
- **Gas machine:** the same k change, and now over the wet share (+2.6 % to +11 % on a wet suction).
- **Liquid machine:** unchanged at a steady state (k 1.4 and wet 1 were already right); endpoint → average flow in a transient.
- **In-line set-flow compressor:** −35 % (its draw ×0.65); its rating and store size ×0.65.
- **Auto speed:** a pressure- or flow-mode compressor on a gas whose k is not 1.4 moves by a few percent (ethane at ratio 1.5: about 4 % of the rise).

The k and recycle percentages are from MACHINES_REVIEW M-01's formulas and are not re-measured here. No test expectation is known to move by reading; the run will show any, and each will be listed here, old and new.

## Tests
- New `ShaftPowerBookingTest`, on settled slices (a fixed suction into a fixed discharge):
  - a frame on propane: charge = booked, within 1e-9 (the same power function, summed over equal steps);
  - a centrifugal compressor on nitrogen past its rated flow: within 1e-5 (the Newton holds the law's row to 1e-6 of the rise);
  - a pump at its rated point: charge = booked / curve η, and η = 0.7;
  - a set-flow pump: charge = booked / .65;
  - M-07 on ethane (k 1.19) at ratio 1.5 (ratio 2 needs more than the 1.5 speed clamp on this law).
- To run once the lane is free: `ShaftPowerBookingTest`, `ShaftCouplingTest`, `TimeFactorElectricTest`, `HeatUnitPowerTest`, `PumpHealthTest`, `MachineMoverTest` and `CompressorTest` (both read the solve's kappa, refactored), `RotatingDriveReworkTest`, `ColumnRunTest` (ratedShaftPower), `ElectricStoreTest`. `ElectricGameTests` belong to the main thread.

## Run by the main thread (2026-11-13)

The thread could not be reached (the app paused cross-session messages), so the main thread test-merged this branch into the review branch and ran the named classes itself; all pass. Counts and the merge commit: the batch README history and `wave-b/FINAL_LANE_REPORT.md` (both final lanes of the review branch include this code). Any "tests pending" or "results pending" line above is superseded by this note.
