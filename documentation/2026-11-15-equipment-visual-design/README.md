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

## Documents

- [VISUAL_REVIEW.md](VISUAL_REVIEW.md): the findings and the proposed order.
- [RELIEF_SPRITES_PLAN.md](RELIEF_SPRITES_PLAN.md): relief sprites for shells, nuts and louvres out of polygons, decisions R1 to R3,
  work packages R-W1 to R-W3.
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
