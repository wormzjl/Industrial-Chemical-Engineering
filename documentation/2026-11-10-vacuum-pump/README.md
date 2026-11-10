# Vacuum pump casings

Status: **In progress since 2026-11-10** (phase 1: plan written, owner decisions V1 to V9 open). Branch `claude/vacuum-pump-bcd40b`, cut from `claude/custom-shader-system-caff9e` at `6e95356b5`.

## Scope

Owner, 2026-11-10: "Add a vacuum pump head to rotating machines"; "Both types & different models, in sub-category" (liquid ring and dry screw); column assembly "yes, but this should be placed on the drum vapor outlet".

## Documents

- [VACUUM_PUMP_PLAN.md](VACUUM_PUMP_PLAN.md): the curves (liquid ring `(1 − P_v/P_s)^n`, dry screw flat to 1 Pa), shaft power and discharge temperature, seal water, 11 models, the Vacuum sub-category, the pump on the receiver's vapour outlet, limits, sound, the simulator unit; decisions V1 to V9.

## History

- 2026-11-10: plan written (revision 1).

## Code areas (planned)

`science/machine` (`VacuumPump`, `MachineLaw`), `science/fluid/network/FlowControl.Machine`, `science/equipment` (`EquipmentShape.Kind`, `ProcessUnit`, `UnitDesign`, `ColumnComponents`), `science/assembly/ColumnBlueprint`, `world/level/block/EquipmentStructure`, `client/gui/common/ConstructorColumn`, `AssemblyDiagram`, the simulator's `Tiles`, `FlowsheetCatalog` and a `VacuumPumpUnit`.

## Open items

- Owner decisions V1 to V9 (plan section 12).
