# 2026-10-27-skirt-shadow

Purpose: remove the shadow on the floor inside a vessel's skirt, seen through its slots and port, which grew with the diameter (owner request 2026-10-27: "Remove the shadow in drum/vessel skirt"; clarified: "the bottom plane looked through the windows, drums with very large diameter makes this worse").

Status: Implemented 2026-10-27 (branch `claude/modest-nightingale-c9932d` fast-forwarded into `main`; released in 0.15.1 with the column sump batch); not pushed. GameTest fix released in 0.15.2 (branch `claude/distillation-sump-enlargement-59e8e7`, fast-forward). Index: [../INDEX.md](../INDEX.md).

## Documents

- [SKIRT_SHADOW_REVIEW.md](SKIRT_SHADOW_REVIEW.md): expected behaviour, the light the floor got (by diameter), the change, what the owner validates, the gates, the GameTest's light waits (section 6).
- `figures/floor-before-after.png`: D 3, D 6 and D 10 drums and a D 8 column cut at 0.3 m, in-game skylight at noon, before and after.

## Code areas

`science/equipment/ShapeRasterizer.skylight`, `ProcessUnit.openSkirt` (set by `skirt`); `world/level/block/EquipmentInteriorBlock.SKYLIGHT`; `EquipmentStructure` (`interior`, `restateLater`, `restate`, `restateCells`, the tick queue, `refresh`); `EquipmentShellBlockEntity.onLoad` (the controller queues the restate); `blockstates/equipment_interior.json`. Tests: `ShapeRasterizerTest.aSkirtShowingItsInsideLetsSkylightThrough`; GameTest `EquipmentCompatGameTests.aSkirtsFloorTakesTheSkylightAboveTheDrum`. Code guide: `equipment.md`. Tools: [../../tools/skirt-shadow/](../../tools/skirt-shadow/README.md).

## History

- 2026-10-27: first reading: the skirt's darker paint (structure channel). Skirts moved to the body paint (`c9e827ac`, docs `0711471d`). The owner corrected the reading; reverted (`f2b15b31`).
- 2026-10-27: the shadow is the floor's light: opaque interior cells leave it lit only sideways from the skirt ring (skylight 9 at a 10 m drum's centre). Owner choice "Skylight through" (over baking light into the inside faces). Code `d02d221f`: `EquipmentInteriorBlock.SKYLIGHT` on the interior cells of a vessel whose skirt shows its inside; placed vessels restated at load. `test` 1644/1644, fluid GameTests 35/35; floor skylight 15 at every size (probe).
- 2026-10-27: merged into `main` by fast-forward from `995dbd9c` on the owner's "merge". Owner: "no bump" (`mod_version` stays 0.15.0; the CHANGELOG line stays under `[Unreleased]`) and "no test" (`slowGates` not run). Code guide: equipment, verification, fluid-runtime and README at `d02d221f`.
- 2026-10-27: released in 0.15.1 with the column sump batch (CHANGELOG `[0.15.1]`). In that merge's run `sump2` the GameTest `aSkirtsFloorTakesTheSkylightAboveTheDrum` failed once: its tick budget ran out before the light engine had a turn.
- 2026-10-27: GameTest fix (owner: "Fix the flaky skylight GameTest here: make it wait for the light engine to settle, not a tick count."): each light reading waits for the engine's run of the queued work, then reads once (`33936ffd`). Probe: the old poll fails with the engine asleep, the new wait passes, the gate fails at its first reading with the rule off. Fluid GameTests 35/35 (`skyfix1`). Review section 6; tools `gametest-probe/`.
- 2026-10-27: merged as 0.15.2 (owner: "Merge the skylight GameTest fix to main as 0.15.2 (patch bump)."). Release prep `1699862c` (version, CHANGELOG `[0.15.2]`, code guide README and verification chapter). Gates on the release tree: `test` 1644/1644, `slowGates` 188/188; fluid GameTests 35/35 at `33936ffd` (`skyfix1`; the release tree adds only documents and the version). Main fast-forwarded from `0c8728b3`; not pushed.

## Open items

- Owner validation (review section 4); Iris shadow map and Distant Horizons not checked in game.
