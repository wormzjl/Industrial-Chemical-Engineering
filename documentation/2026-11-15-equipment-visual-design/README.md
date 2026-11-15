# Equipment visual design

Batch started 2026-11-15 on `claude/equipment-visual-design-d4e22c`. Status: Planned. No code has changed.

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

## Documents

- [VISUAL_REVIEW.md](VISUAL_REVIEW.md): the findings and the proposed order.
- [STEAM_TURBINE_SECTIONS_PLAN.md](STEAM_TURBINE_SECTIONS_PLAN.md): the combined steam turbine, decisions T1 to T4,
  work packages W1 to W4.
- Contact sheets (main checkout): `research/2026-11-15-equipment-visual-design/review/`.

## Code areas

`science/equipment/` (`ProcessUnit`, the `*Design` classes), `client/model/EquipmentShellModel`,
`assets/icheme/textures/block/`.

## Open items

- The owner's four decisions at the end of the review.
- Scope (owner, 2026-11-15): the boilers and gasifiers belong to another work and are left out.
- A sprite render or in-game look for the findings marked "sprite" (K5, K9, K11).
