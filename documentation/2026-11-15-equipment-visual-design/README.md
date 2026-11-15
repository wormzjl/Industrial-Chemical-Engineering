# Equipment visual design

Batch started 2026-11-15 on `claude/equipment-visual-design-d4e22c`. Status: Planned. No code has changed.

Purpose: make the implemented equipment look better and less bare (owner request, 2026-11-15).

## History

- 2026-11-15: review of every kind on fresh drawings ([VISUAL_REVIEW.md](VISUAL_REVIEW.md): G1 to G6, K1 to K13).
  One run of `tools/equipment-drawings/update.ps1`, no test run. Owner rule added to the `models-and-sfx` skill: no
  weld seams.

## Documents

- [VISUAL_REVIEW.md](VISUAL_REVIEW.md): the findings and the proposed order.
- Contact sheets (main checkout): `research/2026-11-15-equipment-visual-design/review/`.

## Code areas

`science/equipment/` (`ProcessUnit`, the `*Design` classes), `client/model/EquipmentShellModel`,
`assets/icheme/textures/block/`.

## Open items

- The owner's three decisions at the end of the review.
- A sprite render or in-game look for the findings marked "sprite" (K5, K9, K11).
