# 2026-11-10 column failure modes and control

Status: In progress since 2026-11-10 (decisions taken; execution in five stages, see `EXECUTION_PLAN.md`).

## History

- 2026-11-10: owner request: the running column's failure modes and control are scattered; review what is not covered, propose one homogeneous scheme that is light on the column and fluid solvers, its integration with the control system, and operating parameters adjustable in the GUI and the DCS. Review and plan written on `claude/distillation-failure-mode-control-db2da5` from main 0.24.0 (`79d0a6254`). Read only: no code changed, no Gradle run.

- 2026-11-10: the owner took the recommended option of D1 to D8 ("commence as recommended") and asked for a test world with a full-size column, tested at the end and left for a manual test (D9). `EXECUTION_PLAN.md` written: five stages for Opus sub-threads (1: condition record, solve policy, alarms; 2: flooding, Operation tab, DCS page, feed-rate loop; 3: reboiler and pumparound utilities; 4: member power; 5: the test world and `MANUAL_TEST.md`). Design details changed there: advisories as one notice, no port level channel, the column page on the DCS only, requested set points stay the fact.

- 2026-11-10: stage 1 (W1, W2, W6.1) on this branch: `a99164867` condition record (`Condition`, `ColumnStatus`, `Refusal`; `OperationView.conditions`, assembly-5), `9fedc6e11` solve policy (accepted set points run the cycle; refused set points and states remembered in memory; one retry after an unfinished solve; `RETRY_TICKS` gone), then the column alarms (`EquipmentAlarm.COLUMN`, `FluidWorldAuthority.columnAlarms`, `alarm_*` channels on the column's host). No fact change. Targeted runs green: the section 2 invariants, `ColumnCycleTest`, `ColumnRunTest`, `OperationColumnTest`, `AssemblyNetworkCodecTest`, `BuiltInInputsTest` and the tests that load `EquipmentAlarm`, `ProcessSolveServices` and the control service. The alarm in `ControlPublication`'s list needs a server and is the stage 5 GameTest (item 7).

- 2026-11-10: stage 2 on this branch: `560a386b6` W3.1 to W3.3 (the flood fraction per feed in `SplitTable.Flood` from V3's tray hydraulics; `FLOODING` limits the feed, `HIGH_LOAD` from 85 %, the live drop on `differential_pressure`; the two stage 1 fixes; T4 linear within 0.52 %, no interpolation; `SimulatorResults` format 3; an older column state is dropped and solved again), `937c9693a` W7 (the Operation tab: tray rows beside the duties, a conditions table, each ratio entry at the set feed with the accepted value beside it, the accepted value and the refusal on the entry; set points before the first run on a stopped unit with no stations; assembly-6), `17b9ad081` W6.2 (the DCS lists the columns on its bus and shows their status and Operation door; queued Start, Stop and Apply checked for the data link; control-4), `7f9ff007b` W6.3 (a loop on a column host or a feed boundary valve plans the feed; the cycle takes min(set, plan)). W3.4 (downcomer backup) stopped at its stop condition and is not committed: it raises the crude sample's flood at 8 m from 70.6 % to 92.3 %, so the sizer takes 10 m and the sample's sized readings move; the draft and the readings are in the worktree's `tmp/col-stage2/`. No fact change. Renders in `research/2026-11-10-column-failure-modes/renders-w7/`.

## Documents

- [COLUMN_FAILURE_MODES_REVIEW.md](COLUMN_FAILURE_MODES_REVIEW.md): expected behaviour, the seven mechanisms in the code, coverage, findings F1 to F12.
- [COLUMN_FAILURE_MODES_PLAN.md](COLUMN_FAILURE_MODES_PLAN.md): one condition record in five classes, work packages W1 to W7, control system integration, adjustable parameters, cost and saves, owner decisions D1 to D8.
- [EXECUTION_PLAN.md](EXECUTION_PLAN.md): the decisions in force, the rules for every stage, stages 1 to 5 with their steps, tests and done lines, the test world's plant and script.

## Code areas

Stage 1 changed `science/column/operation/**` (new `Condition`, `ColumnStatus`, `Refusal`), `runtime/fluid/ColumnOperation`, `EquipmentAlarm`, `FluidWorldAuthority` (start refusals, `columnAlarms`, `editColumn`), `runtime/assembly/ColumnStarts`, `AssemblyService`, `runtime/control/BuiltInInputs`, `runtime/ProcessSolveServices`, `network/ProcessSolveCoordinator`, `network/AssemblyNetwork` (assembly-5), `client/gui/common/OperationColumn`, `en_us.json`. The plan names `science/column/operation/**`, `runtime/fluid/ColumnOperation`, `ColumnUnit`, `runtime/control/**` (`BuiltInInputs`, `ControlLaw`, `ControlPlans`, `ControlPublication`), `EquipmentAlarm`, `network/ControlNetwork`, `AssemblyNetwork`, `client/gui/common/OperationColumn`, `HeatUnit`, `ElectricStores`.

## Open items

- Stages 1 to 5 of `EXECUTION_PLAN.md`; each needs the owner's approval of its sub-thread.
- W3.4 waits for the owner: fold the downcomer backup into the flood fraction and re-pin the sample at 10 m, size the apron clearance from the liquid load, or keep the backup a separate reading.
- The Operation glass no longer fits one view at the design size once the column has tray data (7 px over) or conditions; it scrolls. The DCS column page has no glass (its 464 px window is narrower than the ports table).
- Stage 4, step 4 (a member pump with no driver refuses Start while `machinesNeedPower` is on) is an assumption the owner may veto.
- Stage 3 changes old plants: after their next Start, a reboiler or pumparound cooler with no utility line limits the column.
- After stage 5: the owner's manual test in `run/saves/column-failure-modes`, then the final lane and the merge on the owner's word.
- D3 was kept (rule B stays). One rule in place of A and B would need one run of `tools/column-operation` for its numbers.
- Not in the plan: a turndown floor (owner Q8), vacuum columns, a relief path to the flare.
