# Vacuum pump casings

Status: **In progress since 2026-11-10** (phase 2: built on the branch, not merged). Branch `claude/vacuum-pump-bcd40b`, cut from `claude/custom-shader-system-caff9e` at `6e95356b5`.

## Scope

Owner, 2026-11-10: "Add a vacuum pump head to rotating machines"; "Both types & different models, in sub-category" (liquid ring and dry screw); column assembly "yes, but this should be placed on the drum vapor outlet".

## Documents

- [VACUUM_PUMP_PLAN.md](VACUUM_PUMP_PLAN.md): the curves (liquid ring `(1 − P_v/P_s)^n`, dry screw flat to 1 Pa), shaft power and discharge temperature, seal water, 11 models, the Vacuum sub-category, the pump on the receiver's vapour outlet, limits, sound, the simulator unit; decisions V1 to V9.

## History

- 2026-11-10: plan written (revision 1).
- 2026-11-10: owner "V6 B with noise, others as recommended" (plan 12.1). Built: the `VacuumPump` law (liquid ring 1 and 2 stages, dry screw; flow-form MOVER_LAW row), facts schema 19 (`vacuum_pump` law record) and checkpoint 44, corpus `compat/fluid/schema-19`; kinds `LIQUID_RING_PUMP`, `DRY_SCREW_PUMP` (`VacuumPumpDesign`, 11 models, layout rows); a dry screw's liquid wears it and plays the cavitation layer (`PumpHealth`, `MoverSounds`); constructor and simulator Vacuum category with the ejector; `VacuumPumpUnit`, `VacuumPumpSizing`, `FlowsheetCatalog` MODELLED; the column's `Vacuum` field (shape fields 12, `shapes-12.snbt`): the pump on the receiver's vapour outlet and a water pump on the boot (`ColumnBlueprint`, `AssemblyDiagram`, the simulator column door, its solve and sizing). As-built notes in plan 12.2. Targeted tests only (owner).

## Code areas

`science/machine` (`VacuumPump`, `MachineLaw`), `science/fluid/network/FlowControl.Machine`, `science/equipment` (`EquipmentShape.Kind`, `ProcessUnit`, `UnitDesign`, `ColumnComponents`), `science/assembly/ColumnBlueprint`, `world/level/block/EquipmentStructure`, `client/gui/common/ConstructorColumn`, `AssemblyDiagram`, the simulator's `Tiles`, `FlowsheetCatalog` and a `VacuumPumpUnit`.

## Open items

- In-game check (offered, not run): a constructor-placed LR and DS on a shaft train pulling a rigid drum down; the cavitation layer on a wet screw.
- A vacuum column still refuses to start in the world ("a vacuum column cannot run yet"); the simulator's column block drawing shows no pump part yet.
- A large vent beyond the largest model (the default preset at 80 kPa vents 4.6 m³/s) gets the largest and a warning: no parallel pumps.
- Merge: the chimney branch also appends kinds and may also take shape fields 12; renumber one at merge.
- Translations at merge (`translation-commit`).
