# 2026-10-28-column-operation

Purpose: run the V3 column simulator inside the built plant. Buffered inlet and outlet ports detach the fluid islands; a split table from the last accepted solve turns the feed taken into products every engine cycle; a new solve runs only when the input drifts (owner request 2026-10-28: "Think about how to hook the distillation column simulator into the actual structure").

Status: In progress since 2026-10-29: owner answers recorded, implementation by an Opus session (high effort) on its own branch cut from `claude/distillation-column-integration-fef62f`, C1 first. Index: [../INDEX.md](../INDEX.md).

## Documents

- [COLUMN_OPERATION_PLAN.md](COLUMN_OPERATION_PLAN.md): the expected behaviour and three facts that shape the design (throughput scales exactly, recoveries drift slowly, the condenser closes the energy); what the code has; the column unit (boundary at the valves, void inlets, buffer vessels at product pressure, the master's valve jobs, conservation); the cycle and the split table; new solves (bands, warm route, fixed application, trip); short inputs (rules A and B); drift and the specifications (S1 to S4); timing and determinism; utilities (stages A and B, W9); package, water, nitrogen, solids; saves; the Operation tab; work packages C1 to C8 with C6b (the water-cooled condenser); owner questions Q1 to Q11 and the answers.

## Code areas (planned)

`science/column/v3` (warm solves through `V3ColumnCalculator.calculateWithStrategy`, no solver change in stage A), `science/fluid/solver` (a constant-pressure buffer node; the vessel wall row unchanged), `science/fluid/network/ScheduledTransfer` (its first runtime producer), `runtime/fluid` (the column unit's registration and facts, the suspension, the ports, `IslandCoordinator` fence and valve operations), `runtime/assembly` (Start and Stop on a COMPLETE report), `science/equipment/ColumnDesign` and `ColumnTrays` (the operating point), the assembly screen's Operation tab.

## History

- 2026-10-28: plan written. Design only: no code, no Gradle run. It answers the 2026-10-26 plan's open next-stage decisions D5 (members suspended with their contents), D7 (the operating point on the design), D14 (inlet voids and outlet buffer vessels; generator ports rejected again for conservation) and D16 (the distillate valve is its port's valve), and gives the heat plan's deferred W9 a place (stage B of the utilities).
- 2026-10-28: owner decision on Q3: outlets are constant-pressure variable-volume buffers (pressure fixed at the port, inventory free, capacity-bounded) instead of padded vessels; the wall-row change is dropped.
- 2026-10-29: owner answers. Q1: C1 benches rule B's cost; B (with A where a solve fails) if its warm solves take at most 1.5 s at the median and 5 s at the 95th percentile, else A (plan section 7). Q2 and Q4 to Q11 as recommended. The water-cooled condenser joins this batch as C6b: its cooling-water side stays in the network as a DUTY heat side with the column as partner (plan section 10). Implementation handed to an Opus session at high effort.

## Open items

- C1 sets the drift bands of plan section 6 and decides between rules A and B; the bands in the plan are proposals.
- Q6: S2 or S3 after C1.
- C7: a drawing of the Operation tab goes to the owner before the GUI is built.
