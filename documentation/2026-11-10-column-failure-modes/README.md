# 2026-11-10 column failure modes and control

Status: In progress since 2026-11-10 (decisions taken; execution in five stages, see `EXECUTION_PLAN.md`).

## History

- 2026-11-10: owner request: the running column's failure modes and control are scattered; review what is not covered, propose one homogeneous scheme that is light on the column and fluid solvers, its integration with the control system, and operating parameters adjustable in the GUI and the DCS. Review and plan written on `claude/distillation-failure-mode-control-db2da5` from main 0.24.0 (`79d0a6254`). Read only: no code changed, no Gradle run.

- 2026-11-10: the owner took the recommended option of D1 to D8 ("commence as recommended") and asked for a test world with a full-size column, tested at the end and left for a manual test (D9). `EXECUTION_PLAN.md` written: five stages for Opus sub-threads (1: condition record, solve policy, alarms; 2: flooding, Operation tab, DCS page, feed-rate loop; 3: reboiler and pumparound utilities; 4: member power; 5: the test world and `MANUAL_TEST.md`). Design details changed there: advisories as one notice, no port level channel, the column page on the DCS only, requested set points stay the fact.

## Documents

- [COLUMN_FAILURE_MODES_REVIEW.md](COLUMN_FAILURE_MODES_REVIEW.md): expected behaviour, the seven mechanisms in the code, coverage, findings F1 to F12.
- [COLUMN_FAILURE_MODES_PLAN.md](COLUMN_FAILURE_MODES_PLAN.md): one condition record in five classes, work packages W1 to W7, control system integration, adjustable parameters, cost and saves, owner decisions D1 to D8.
- [EXECUTION_PLAN.md](EXECUTION_PLAN.md): the decisions in force, the rules for every stage, stages 1 to 5 with their steps, tests and done lines, the test world's plant and script.

## Code areas

None changed. The plan names `science/column/operation/**`, `runtime/fluid/ColumnOperation`, `ColumnUnit`, `runtime/control/**` (`BuiltInInputs`, `ControlLaw`, `ControlPlans`, `ControlPublication`), `EquipmentAlarm`, `network/ControlNetwork`, `AssemblyNetwork`, `client/gui/common/OperationColumn`, `HeatUnit`, `ElectricStores`.

## Open items

- Stages 1 to 5 of `EXECUTION_PLAN.md`; each needs the owner's approval of its sub-thread.
- Stage 4, step 4 (a member pump with no driver refuses Start while `machinesNeedPower` is on) is an assumption the owner may veto.
- Stage 3 changes old plants: after their next Start, a reboiler or pumparound cooler with no utility line limits the column.
- After stage 5: the owner's manual test in `run/saves/column-failure-modes`, then the final lane and the merge on the owner's word.
- D3 was kept (rule B stays). One rule in place of A and B would need one run of `tools/column-operation` for its numbers.
- Not in the plan: a turndown floor (owner Q8), vacuum columns, a relief path to the flare.
