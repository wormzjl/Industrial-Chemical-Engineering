# 2026-10-28-column-operation

Purpose: run the V3 column simulator inside the built plant. Buffered inlet and outlet ports detach the fluid islands; a split table from the last accepted solve turns the feed taken into products every engine cycle; a new solve runs only when the input drifts (owner request 2026-10-28: "Think about how to hook the distillation column simulator into the actual structure").

Status: Planned 2026-10-28 (branch `claude/distillation-column-integration-fef62f`, base `ac572f12`). Index: [../INDEX.md](../INDEX.md).

## Documents

- [COLUMN_OPERATION_PLAN.md](COLUMN_OPERATION_PLAN.md): the expected behaviour and three facts that shape the design (throughput scales exactly, recoveries drift slowly, the condenser closes the energy); what the code has; the column unit (boundary at the valves, void inlets, buffer vessels at product pressure, the master's valve jobs, conservation); the cycle and the split table; new solves (bands, warm route, fixed application, trip); short inputs (rules A and B); drift and the specifications (S1 to S4); timing and determinism; utilities (stages A and B, W9); package, water, nitrogen, solids; saves; the Operation tab; work packages C1 to C8; owner questions Q1 to Q11.

## Code areas (planned)

`science/column/v3` (warm solves through `V3ColumnCalculator.calculateWithStrategy`, no solver change in stage A), `science/fluid/solver/PhaseLayout.wallRow` (the pad pressure), `science/fluid/network/ScheduledTransfer` (its first runtime producer), `runtime/fluid` (the column unit's registration and facts, the suspension, the ports, `IslandCoordinator` fence and valve operations), `runtime/assembly` (Start and Stop on a COMPLETE report), `science/equipment/ColumnDesign` and `ColumnTrays` (the operating point), the assembly screen's Operation tab.

## History

- 2026-10-28: plan written. Design only: no code, no Gradle run. It answers the 2026-10-26 plan's open next-stage decisions D5 (members suspended with their contents), D7 (the operating point on the design), D14 (inlet voids and outlet buffer vessels; generator ports rejected again for conservation) and D16 (the distillate valve is its port's valve), and gives the heat plan's deferred W9 a place (stage B of the utilities).

## Open items

- Owner questions Q1 to Q11 (plan section 15). The defaults taken are listed under the table.
- C1 sets the drift bands of plan section 6; the bands in the plan are proposals.
