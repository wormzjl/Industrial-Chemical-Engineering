# 2026-10-28-column-operation

Purpose: run the V3 column simulator inside the built plant. Buffered inlet and outlet ports detach the fluid islands; a split table from the last accepted solve turns the feed taken into products every engine cycle; a new solve runs only when the input drifts (owner request 2026-10-28: "Think about how to hook the distillation column simulator into the actual structure").

Status: In progress since 2026-10-29: owner answers recorded, implementation by an Opus session (high effort) on branch `claude/column-operation-5c3005` (main 79b853e0 with the plan branch merged); C1 done, waiting for the go to C2. Index: [../INDEX.md](../INDEX.md).

## Documents

- [COLUMN_OPERATION_PLAN.md](COLUMN_OPERATION_PLAN.md): the expected behaviour and three facts that shape the design (throughput scales exactly, recoveries drift slowly, the condenser closes the energy); what the code has; the column unit (boundary at the valves, void inlets, buffer vessels at product pressure, the master's valve jobs, conservation); the cycle and the split table; new solves (bands, warm route, fixed application, trip); short inputs (rules A and B); drift and the specifications (S1 to S4); timing and determinism; utilities (stages A and B, W9); package, water, nitrogen, solids; saves; the Operation tab; work packages C1 to C8 with C6b (the water-cooled condenser); owner questions Q1 to Q11 and the answers.
- [C1_MEASUREMENT_REVIEW.md](C1_MEASUREMENT_REVIEW.md): C1: homogeneity, the table's error against drift and the proposed bands, warm solves (dry, wet, kettle), rule B's cost (decision B), S2 or S3.

Tools: [`tools/column-operation/`](../../tools/column-operation/README.md) (the C1 bench and its TSV).

## Code areas (planned)

`science/column/v3` (warm solves through `V3ColumnCalculator.calculateWithStrategy`, no solver change in stage A), `science/fluid/solver` (a constant-pressure buffer node; the vessel wall row unchanged), `science/fluid/network/ScheduledTransfer` (its first runtime producer), `runtime/fluid` (the column unit's registration and facts, the suspension, the ports, `IslandCoordinator` fence and valve operations), `runtime/assembly` (Start and Stop on a COMPLETE report), `science/equipment/ColumnDesign` and `ColumnTrays` (the operating point), the assembly screen's Operation tab.

## History

- 2026-10-28: plan written. Design only: no code, no Gradle run. It answers the 2026-10-26 plan's open next-stage decisions D5 (members suspended with their contents), D7 (the operating point on the design), D14 (inlet voids and outlet buffer vessels; generator ports rejected again for conservation) and D16 (the distillate valve is its port's valve), and gives the heat plan's deferred W9 a place (stage B of the utilities).
- 2026-10-28: owner decision on Q3: outlets are constant-pressure variable-volume buffers (pressure fixed at the port, inventory free, capacity-bounded) instead of padded vessels; the wall-row change is dropped.
- 2026-10-29: owner answers. Q1: C1 benches rule B's cost; B (with A where a solve fails) if its warm solves take at most 1.5 s at the median and 5 s at the 95th percentile, else A (plan section 7). Q2 and Q4 to Q11 as recommended. The water-cooled condenser joins this batch as C6b: its cooling-water side stays in the network as a DUTY heat side with the column as partner (plan section 10). Implementation handed to an Opus session at high effort.
- 2026-10-29: branch `claude/column-operation-5c3005` from main 79b853e0 (0.17.1); the plan branch (tip 91868502, with the C++ column default 9c5eb01f) merged at a4397868, conflicts in index rows, the changelog and the two code-guide snapshot lines only; `compileJava compileTestJava` green.
- 2026-10-29: C1 (review `C1_MEASUREMENT_REVIEW.md`; bench in `tools/column-operation/`). One timing sweep alone: 60 of 60 warm solves accepted on the warm route; rule B median 0.33 s, 95th percentile 0.52 s, so **rule B with A where a solve fails** (Q1). Fact 1 exact at a prescribed drop; the table's error against drift is first order, not second, so tighter bands are proposed (composition 0.5 %, enthalpy 1 K, closure 3 %). No modelled wet tray on the network package; the owner's cold-condenser and high-reflux cases publish a dry tray with the dew-point warning and warm-start (Q10: nothing to extend). Q6: S3 not needed (23 of 23 kettle warm solves accepted); S2 later only if wanted. Kettle design: 15 MW (25 MW fails cold).

## Open items

- The proposed bands of the C1 review (composition 0.5 %, enthalpy 1 K, closure 3 %) replace the plan's section 6 values unless the orchestrator or owner says otherwise.
- C5: a table whose seed holds modelled free water solves with no seed (the fast cold route), not the strict cold chain.
- C7: a drawing of the Operation tab goes to the owner before the GUI is built.
