# Equipment visual design

Batch started 2026-11-15 on `claude/equipment-visual-design-d4e22c`. Status: In progress since 2026-11-15. The combined steam turbine (W1 to W4) is built on `claude/turbine-sections-365c63`, not merged.

Purpose: make the implemented equipment look better and less bare (owner request, 2026-11-15).

## History

- 2026-11-15: review of every kind on fresh drawings ([VISUAL_REVIEW.md](VISUAL_REVIEW.md): G1 to G6, K1 to K13).
  One run of `tools/equipment-drawings/update.ps1`, no test run. Owner rule added to the `models-and-sfx` skill: no
  weld seams.

- 2026-11-15: sketches of the furnace platforms (K14) and the HP and MP casings (K15) shown to the owner. Owner on
  K15: the HP turbine includes the MP and LP sections, the MP turbine the LP section, as one combined model and
  equipment; answers: straight through, add an LP class, every size
  ([STEAM_TURBINE_SECTIONS_PLAN.md](STEAM_TURBINE_SECTIONS_PLAN.md)). Not built.

- 2026-11-15, W1 (branch `claude/turbine-sections-365c63`): `MachineDuty.Steam.LP` (450 kPa, 200 °C, first),
  `TURBINE_EXHAUSTS` (10 to 350 kPa) for the exhaust step and `withSteam`, a fresh draft at HP to 10 kPa
  (`UnitDesign.draft`), `NetworkProtocol.VERSION` `icheme-2`, the LP button and lang lines. Wet-exhaust table: every
  class wears at 10 to 20 kPa (LP 5.45 %, HP 4.61 %, VHP 4.06 % condensed at 10 kPa); all are dry at 150 to 350 kPa.
  So T1's condition fails, and the table went to the owner. Tests: `SteamTurbineTest`, `EquipmentNetworkCodecTest`,
  `ConstructorColumnTest`, `MachineColumnTest`, `FluidPacketCodecTest`, `TurbineSizingTest` (test);
  `ConstructorDefaultsTest`, `SteamTurbineSectionsTest` (equipmentGates). All pass.

- 2026-11-15, W2: `SteamTurbineDesign.sections` is one rule for every size (the `SINGLE_CASING` guard is gone),
  `Section.HP` and `front`, `drops` per section (HP to 10 kPa: 181, 266, 761 kJ/kg). Up to 45 MW `SteamTurbineTrain`
  draws one barrel per section (0.65, 0.85 and 1 Dc, cones between) between the old end walls. The steam chest, the
  exhaust branch, the feet and every solid stay at one section's stations. `MachineDuty.step` fix: a value above the
  list (a saved back pressure) now steps down onto the list's last value, not the one before it. Layout rows: none
  moved (a first try that put the feet at the new cone dropped 4 shell cells of the 45 MW row, so the feet stay at
  one section's stations). Tests (equipmentGates): `SteamTurbineSectionsTest` (rewritten), `ProcessUnitTest`,
  `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest`, `MachineRpmDesignTest`, `EquipmentShapeCompatTest`,
  `ConstructorDefaultsTest`; `ConstructorColumnTest` (test). All pass.

- 2026-11-15, W3: tandem casings above 45 MW. Each casing is split along the shaft line by its joint flange. The MP
  casing is a cone from 0.8 of its exhaust radius. The LP hood is a box under a half-round top (`Mesh.vault`) closed at
  both ends, with a centre ring when double-flow. The crossover is in legs (front exhaust to the next inlet, MP exhaust
  at its back end to the LP inlet, a double flow's at its middle). Pedestals have a base block, a body and a bearing
  cap inside the old box. An LP-class turbine above 45 MW is the LP hood alone behind the front standard, on a wider
  frame. No new bolts; the girth ribs keep one section's count. Layout rows: only HP 300 MW to 10 kPa moved (shell
  587 to 588, the double-flow inlet at its middle; nozzles unchanged); turbine layout revision 2, table updated.
  Polygons before/after: STEAM_TURBINE 1774/1818, MP 1600/1600, LP 1504/1504, LP_TANDEM 2052/1508, TANDEM_80
  2342/2345, TANDEM_300 2590/2634. Views: `tmp/turbine-sections/views/` (worktree). Tests (equipmentGates):
  `SteamTurbineSectionsTest`, `ProcessUnitTest`, `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest`,
  `EquipmentShapeCompatTest`. All pass.

- 2026-11-15, W4: `TurbineSizing.exhaust` picks from `TURBINE_EXHAUSTS`. New Must row `Limit.TURBINE_EXHAUST`: the
  LP header over the tile's exhaust must be above 0, so a tile exhausting at or above 450 kPa is red; lang line added.
  The tile's run is unchanged. Corpus image `compat/equipment/shapes-12-turbine-sections.snbt`: LP 800 kW to 150 kPa,
  LP 63 MW to 10 kPa and HP 12.8 MW back-pressure to 1,000 kPa, read with no repair. No facts schema moved. Code guide:
  `equipment-designs.md`, `rotating-machinery.md`, `simulator-sizing.md`. Tests: `TurbineSizingTest`, `LimitTest`,
  `FlowsheetUnitsTest`, `SizingCoverageTest`, `FluidFactsCompatTest` (test); `EquipmentShapeCompatTest`,
  `ShapeRasterizerTest` (equipmentGates). All pass.

- 2026-11-15, W3 follow-up (`bd9168e81`): the vault helper moved from `Mesh` into `SteamTurbineTrain`, because the
  relief-sprite thread owns `Mesh`. `Mesh.java` is back to its base, and the six scenes draw the same polygons.

- 2026-11-15, W5 (owner: "So you need to change the wear rules instead"): `Wear.wetOnset(P)` is 1 % from 150 kPa up,
  `WET_ONSET_20_KPA` 4.8 % and `WET_ONSET_10_KPA` 5.6 % (from W1's design readings: MP 4.66 % at 20 kPa, LP 5.45 % at
  10 kPa, each up to the next 0.1 %, plus 0.1 %), linear in ln P, flat below 10 kPa. `wetExhaust(share, P)` keeps the
  span of 5 %. `MachineHealth.booked` reads `wetExcess(share, row.secondPressure())`, the row's exhaust end. From
  150 kPa up, every number is today's bit for bit (`WearTest`). Design steam wears at no class or exhaust. Saturated
  inlet, 10 MW: at 10 kPa LP 0.47, MP 0.77, HP 0.97, VHP 1.00; at 20 kPa LP 0.50, MP 0.88, HP 1.00, VHP 1.00. Every
  saturated reading at 10 and 20 kPa lies above the onset. The table is printed by
  `SteamTurbineTest.theClassesExpandStraightThroughToTheirExhausts`. Tests: `WearTest`, `SteamTurbineTest`,
  `MachineHealthTest`, `MoverHealthStatusTest` (test). All pass.

- 2026-11-15, W6, the LP casing's look (owner: "really need to improve its visual"; exhaust "Top, short neck").
  - The shared `SteamTurbineTrain.hood` now has a cover set in at both ends, side-wall ribs and gland cones, plus a
    relief diaphragm on a double flow.
  - LP class above 45 MW: a single flow is an inlet belt (0.32 Dc) and a flare to 0.6 Dc with two girth ribs, the hood
    at the rear only. A double flow has the hood over the whole length. Both have one inlet riser on the shaft line with
    a butterfly valve (it fixes `z0`), a short neck on the hood's top, and the tandem pedestal (`pedestal` moved to
    `SteamTurbineTrain`). The steam chest, the trip-and-throttle valve and the chimney exhaust are gone.
  - Tandem: the exhaust stub is a tapered neck.
  - New scene `STEAM_TURBINE_LP_DOUBLE`.
  - Where the plan was wrong:
    - (a) A unit's cells come from its facets as well as its solids ("every cell it reaches is its own",
      `ShapeRasterizer`). So the new hood mesh moved two rows of `main`: 80 MW lost 6 shell cells and 300 MW gained 3
      to 5. Fixes: the cover's inset and each gland cone stay inside their end wall's cell column, and the tandem
      neck's base stays inside the columns the casing's crest reaches (less 0.05 m). Every row of `main` is as before.
    - (b) The pitfalls print LP duties to 350 kPa, but an LP exhaust is at most 225 kPa, so 150 kPa was used.
    - (c) `shapes-12-turbine-sections.snbt` stores shapes only, no cells or stamp, so it needed no re-record.
  - Belt and flare at 46, 63 and 100 MW: 1.11 and 1.44 m to 10 kPa, 0.84 and 0.36 m to 150 kPa (casing 5.1 and
    2.4 m).
  - New rows: LP 63 MW to 10 kPa `box 5 7 10, shell 225, interior 10, pipes 2,7,3 2,7,6`; LP 150 MW to 10 kPa
    `box 7 8 10, shell 306, interior 38, pipes 3,8,5 3,7,7`.
  - Polygons before → after:
    - LP_TANDEM 1,508 → 1,139 (ACTUATOR 110 → 12, BOLT 312 → 408, CLADDING 12 → 81, FLANGE 210 → 72, MACHINE 350 → 387,
      PIPE 416 → 92, STEELWORK 41 → 30).
    - LP_DOUBLE new, 1,062 (BOLT 408, CLADDING 156, MACHINE 237, FLANGE 86, PIPE 76).
    - TANDEM_80 2,345 → 2,439 (MACHINE 732 → 822, PIPE 472 → 476).
    - TANDEM_300 2,634 → 2,782 (MACHINE 761 → 895, FLANGE 224 → 238).
    - STEAM_TURBINE, MP and LP unchanged.
  - Tests (equipmentGates): `SteamTurbineSectionsTest`, `ProcessUnitTest`, `ShapeRasterizerTest`,
    `EquipmentLayoutGoldenTest`, `EquipmentShapeCompatTest`. All pass.

## Documents

- [VISUAL_REVIEW.md](VISUAL_REVIEW.md): the findings and the proposed order.
- [RELIEF_SPRITES_PLAN.md](RELIEF_SPRITES_PLAN.md): relief sprites for shells, nuts and louvres out of polygons, decisions R1 to R3,
  work packages R-W1 to R-W3.
- [STEAM_TURBINE_SECTIONS_PLAN.md](STEAM_TURBINE_SECTIONS_PLAN.md): the combined steam turbine, decisions T1 to T4,
  work packages W1 to W4.
- Contact sheets (main checkout): `research/2026-11-15-equipment-visual-design/review/`.

## Code areas

`science/equipment/` (`ProcessUnit`, the `*Design` classes), `MachineDuty`, `SteamTurbineDesign`, `SteamTurbineTrain`, `Mesh.vault`, `science/flowsheet/sizing/TurbineSizing`, `Limit.TURBINE_EXHAUST`, `network/NetworkProtocol`, `client/model/EquipmentShellModel`,
`assets/icheme/textures/block/`.

## Open items

- The owner's four decisions at the end of the review.
- Combined steam turbine: T1 to T3 stand as built (owner, after W5): LP 200 °C, a fresh draft at HP to 10 kPa, MP
  unchanged, each one constant (`MachineDuty.LP_STEAM_TEMPERATURE`, `SteamTurbineDesign.DRAFT_STEAM`/`DRAFT_EXHAUST`,
  `MachineDuty.MP_STEAM_TEMPERATURE`).
- Combined steam turbine: the owner's look at the six drawings (worktree `tmp/turbine-sections/views/`).
- Combined steam turbine: `CHANGELOG.md`, the translation commit and the version wait for the merge.
- Scope (owner, 2026-11-15): the boilers and gasifiers belong to another work and are left out.
- A sprite render or in-game look for the findings marked "sprite" (K5, K9, K11).
