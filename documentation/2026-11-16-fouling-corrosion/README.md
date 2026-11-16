# 2026-11-16 fouling and corrosion

Status: Planned (2026-11-16). Plan written; no code. Branch `claude/fouling-corrosion-system-ba0275`, cut from `main` at `f305ac6b0` (0.32.0).

Purpose: walls that corrode by their material and their fluid (a refinery corrosion matrix, eight materials of construction chosen in the constructor and in a simulator tab), failures through the existing breach and machine failure, final blast damage, repair only when idle and empty, corrosive air on equipment, and fouling of heat sides (lower films, higher drop, cleaning).

Lane: new package `science/integrity`; hot files touched, each in one work package: `MaterialCatalog` (WP1), `FluidFacts`, `FluidWorldAuthority`, `ShaftTrains` (WP2), `EquipmentShape`, `EquipmentStructure` (WP3), `SheetSizing` (WP5). Class: new feature; no reference moves expected.

## History

- 2026-11-16, plan: survey of the existing pieces (`Wear` and `MachineHealth`, `Breaches` and `FluidDeviceSpec.breached`, the corrosive channel that nothing reads for equipment, the constant films, the assays' sulfur as metadata, free construction); the corrosion matrix from API 571, 581, 939-C, 932-B, de Waard–Milliams and TEMA (game-level fits; three web searches gave no table values beyond these references); [FOULING_CORROSION_PLAN.md](FOULING_CORROSION_PLAN.md). Decisions D1 to D4 taken by the agent; O1 to O4 put to the owner. No code, no test run.

- 2026-11-16, owner: O1 the Kern–Seaton placeholder grows the deposit until the reaction module; O2 the cost index only, construction stays free; O3 sounds set A (one new loop, `wall_weep`); the start of WP1: "Not yet", the owner reviews the plan first. Nothing started.

## Documents

- [FOULING_CORROSION_PLAN.md](FOULING_CORROSION_PLAN.md): the request, the physics in numbers, scope and CPU, materials, the matrix, the wall and its failures, repair, fouling, GUI, sounds, formats, work packages.

## Code areas

None yet.

## Open items

- The owner's review of the plan; then O4: the format reservation on `main` and the approval of WP1 (sub-thread, Opus medium).
- `wall_weep` candidates (three, synthesised) once the build starts.
- Whether another live batch holds the hot files named above.
