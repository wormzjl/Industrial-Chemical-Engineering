# 2026-11-10 column failure modes and control

Status: Planned (review and proposal written 2026-11-10; owner decisions D1 to D8 open; no code).

## History

- 2026-11-10: owner request: the running column's failure modes and control are scattered; review what is not covered, propose one homogeneous scheme that is light on the column and fluid solvers, its integration with the control system, and operating parameters adjustable in the GUI and the DCS. Review and plan written on `claude/distillation-failure-mode-control-db2da5` from main 0.24.0 (`79d0a6254`). Read only: no code changed, no Gradle run.

## Documents

- [COLUMN_FAILURE_MODES_REVIEW.md](COLUMN_FAILURE_MODES_REVIEW.md): expected behaviour, the seven mechanisms in the code, coverage, findings F1 to F12.
- [COLUMN_FAILURE_MODES_PLAN.md](COLUMN_FAILURE_MODES_PLAN.md): one condition record in five classes, work packages W1 to W7, control system integration, adjustable parameters, cost and saves, owner decisions D1 to D8.

## Code areas

None changed. The plan names `science/column/operation/**`, `runtime/fluid/ColumnOperation`, `ColumnUnit`, `runtime/control/**` (`BuiltInInputs`, `ControlLaw`, `ControlPlans`, `ControlPublication`), `EquipmentAlarm`, `network/ControlNetwork`, `AssemblyNetwork`, `client/gui/common/OperationColumn`, `HeatUnit`, `ElectricStores`.

## Open items

- Owner decisions D1 to D8 (plan section 7). Nothing is built before them.
- D3 (one rule in place of rules A and B) needs one run of `tools/column-operation` for its numbers before it can be proposed as a simplification.
- Not in the plan: a turndown floor (owner Q8), vacuum columns, a relief path to the flare.
