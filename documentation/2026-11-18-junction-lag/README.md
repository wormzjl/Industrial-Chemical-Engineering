# Junction lag

The solver must deliver hot and cold fronts within 0.5 K on the first slice while retaining the D46 lifetime ledger and valid certificate replay. Owner, 2026-11-18: "the pipe does not have holdup, so the heated oil should reach the column instantly".

## Status

Updated 2026-11-19. The final 6,000-tick plant run had zero charge holds and positive feed in all 292 new bookings. The watch campaign recorded 34 first holds in 65 worlds. The [hold review](REMAINING_HOLDS_REVIEW.md) records the passing and open cases.

## Documents

- [JUNCTION_LAG_PLAN.md](JUNCTION_LAG_PLAN.md): the measured lag (about 70 s after the test world's fired coil), why the buffer was sized at the cap flow (D2, a TR-BDF2 bound, gone with D9), the fixed lifetime stock, the proposed thermal balance and the qualification criteria.

- [JUNCTION_LAG_PLAN_REVIEW.md](JUNCTION_LAG_PLAN_REVIEW.md) records the conservation, replay and measurement findings.

- [REMAINING_HOLDS_REVIEW.md](REMAINING_HOLDS_REVIEW.md) records the precision, pump startup, blanket-drain, valve regulation and saved-plant results.

- [JUNCTION_PERFORMANCE_REVIEW.md](JUNCTION_PERFORMANCE_REVIEW.md) compares saved-plant CPU and resident memory with the pre-implementation baseline and measures equal-work junction circuits.

## Code areas

The follow-up covers junction mix and reconstruction precision, hydraulic balance and pump seeding, adaptive substep growth, the column port's first solve after zero feed, and the warm transition from open to regulating.

## History

- 2026-11-18: plan written after the column test world's coil outlet took about 900 ticks to come within 6 K of a set point the coil had met since tick 1000.

- 2026-11-18: reviewed the flow-sized buffer proposal against the saved trace, D46 ledger and certified replay. Revisions requested before implementation.

- 2026-11-18: revised the plan after the conservation and replay review. The owner selected 0.5 K and kept passive hot and cold inlets in scope. Solver and plant qualification are in progress.

- 2026-11-19: extended same-slice delivery to isothermal composition changes. Added direct receiving-port and two-furnace measurements. See [implementation qualification](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md) for verified behavior and open gates.

- 2026-11-19: completed the optional full lane and recorded the failed test, GameTest and campaign cases. Qualification remains open.

- 2026-11-19: corrected the moving parcel, slurry carrier, phase seeds, zero-demand valve carry, and inlet-plan retention. The follow-up restored-world measurements and gate results are in the [qualification review](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md).

- 2026-11-19: fixed the positive-demand furnace branch stall and stale shut-valve certification. Recorded the first 6,000-tick plant run with the paired furnace changes, warm worker replay and checkpoint regressions in the [qualification review](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md).

- 2026-11-19: fixed the late blanket drain at its finite phase outlet and separated the optional Newton equation layouts. All 196 focused tests passed on `e4321d66b`. The final 6,000-tick saved world finished at full column throughput after recovering four charge deadlines. A separate copy of the shutdown checkpoint resumed positive feed after its saved retry. The [qualification review](JUNCTION_LAG_IMPLEMENTATION_REVIEW.md) records the earlier campaign and its remaining holds.

- 2026-11-19: corrected the backed-junction precision stall in `cfead1ca6`, kept workspace keys stable during vessel emptying in `c09476c1c`, and corrected the pump start and charge reopening in `1924c2e75`. The [follow-up review](REMAINING_HOLDS_REVIEW.md) records the 80-second drain, the affected lane, watch worlds and saved plant. The subsequent donor derivative, provisional phase and warm valve changes passed 195 focused tests at `5b34bff75`. Normal shutdown cancellation passed 137 focused runtime and checkpoint tests at `0f4c08c41`. An in-flight bottoms solve was discarded cleanly on shutdown and all 14 islands saved without a pending retry.

- 2026-11-19: benchmarked two saved-plant runs per revision and matched liquid and mixed-gas circuits. The [performance review](JUNCTION_PERFORMANCE_REVIEW.md) records the CPU, allocation, resident-memory and plant-state differences.
