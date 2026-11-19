# Junction lag

The solver must deliver hot and cold fronts within 0.5 K on the first slice while retaining the D46 lifetime ledger and valid certificate replay. Owner, 2026-11-18: "the pipe does not have holdup, so the heated oil should reach the column instantly".

## Documents

- [JUNCTION_LAG_PLAN.md](JUNCTION_LAG_PLAN.md): the measured lag (about 70 s after the test world's fired coil), why the buffer was sized at the cap flow (D2, a TR-BDF2 bound, gone with D9), the fixed lifetime stock, the proposed thermal balance and the qualification criteria.

- [JUNCTION_LAG_PLAN_REVIEW.md](JUNCTION_LAG_PLAN_REVIEW.md) records the conservation, replay and measurement findings.

## History

- 2026-11-18: plan written after the column test world's coil outlet took about 900 ticks to come within 6 K of a set point the coil had met since tick 1000.

- 2026-11-18: reviewed the flow-sized buffer proposal against the saved trace, D46 ledger and certified replay. Revisions requested before implementation.

- 2026-11-18: revised the plan after the conservation and replay review. The owner selected 0.5 K and kept passive hot and cold inlets in scope. Solver and plant qualification are in progress.
