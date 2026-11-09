# Batch still: structure (W2)

Status: written 2026-11-09 on `claude/still-structure-37aceb`; not committed, not run through Gradle. Drawing only: the
simulator tile is DRAWING_ONLY and the still is not yet a fluid device (W3).

## Sizes

`BatchStillDesign`. Pot working volume 100, 150, 200, 300, 500, 750, 1000, 1500, 2000 L. The constructor's default is 500 L
(the middle of the list). The shape's `diameter` field holds the litres; `volume()` returns m³. The pot diameter is
d = 0.50 (V / 100 L)^b with b = ln 2.74 / ln 20, rounded to 0.01 m. The heated surface is 1.73 d² (the bottom, π/4 d², plus
the wall to the setting's top, π d · 0.3 d). The column diameter is 0.248 d.

| Pot L | d m | heated m² | column mm | charge row | box worm | box no condenser (fired / electric) | column 2 | column 8 |
|---|---|---|---|---|---|---|---|---|
| 100 | 0.50 | 0.43 | 124 | 1.5 | 3x3x3 | 3x3x3 / 3x2x3 | 3x5x3 | 3x6x3 |
| 500 | 0.86 | 1.28 | 213 | 1.5 | 3x4x3 | 3x4x3 / 3x3x3 | 3x5x3 | 3x7x3 |
| 750 | 0.98 | 1.66 | 243 | 2.5 | 3x4x3 | 3x4x3 / 3x3x3 | 3x6x3 | 3x7x3 |
| 1000 | 1.09 | 2.06 | 270 | 2.5 | 3x4x3 | 3x4x3 / 3x4x3 | 3x6x3 | 3x7x3 |
| 2000 | 1.37 | 3.25 | 340 | 2.5 | 3x5x3 | 3x5x3 / 3x4x3 | 3x6x3 | 3x8x3 |

Box x × y × z, frame y up, front −z (phase 1 sizes; every still is now one block wider in x, see Parts). The pot axis is
at (1.5, 1.5), on the second of four columns from the right seen from the front; the condenser's line is x 3.5. The
right column is free for the terminal box or the burner's line, and one free column lies between the pot and the
condenser (owner, 2026-11-10).

## Options (shape fields 11)

`BatchStillOptions(heater, condenser, stages)` is a fact of the shape, the last record component. Default
(SOLID, BUILT_IN, 0); every other kind carries the default and refuses another value. `EquipmentShellBlockEntity`
writes a `Still` compound (Heater, Condenser by lower-case name, Stages int) for a still only. On read, a missing
compound or field takes its default. An unknown name or an out-of-range stage count takes its default, with a named
repair. A column without the built-in condenser loses the column.

## Parts

Phase 1 as built. The owner's feedback changed it (OWNER_FEEDBACK_REVIEW.md): every still four blocks wide, one top
condenser along z at x 3.5 on two H-frames for every condenser still, no worm tub and no `WOOD`, baked sprites in each
heater tier's finish, the fuel on the right face with the liquid still's burner (a small fuel connection through the
right wall at ground level, its neck across the right column), the electric still's terminal box in the right column's
centre, the charge and drain straight on the pot's line, and no data port on a fired still. The nozzle table below is
the current one.

- Every still: a copper pot (shell, dished bottom, head, seam hoop) and its charge and drain lines. The worm option adds
  the helmet with a sight glass and the swan neck.
- SOLID / LIQUID: a brick setting with its coping and a brick flue with a cap in the back right corner. The front has an
  iron firing door with its frame and handle, and an ash-pit door. LIQUID adds a burner gun and register through the
  door, with the fuel line to the front.
- ELECTRIC: a steel skirt with louvres and a base hoop. A terminal box on a stand at the front left, with a glass lamp
  and a conduit.
- Worm tub (BUILT_IN, no column): a brick plinth and a wooden tub with lid and three iron hoops. The tail pipe has a
  sight glass and a spout at the front. A water dip pipe enters from the top, an overflow leaves at the back and a vent
  riser stands at the front.
- No condenser: the helmet's arm runs to the VAPOUR outlet on the right, held by a post.
- Column (2 to 8 stages, BUILT_IN only): a copper column with a section hoop and a glass porthole per stage, and foot and
  head flanges. A gooseneck runs to an overhead tube condenser along x at the front row, with channels and hoops. Under
  it, a reflux pot with a glass gauge and an iron hand-wheel valve. The reflux line runs back into the column top. The
  product line runs down to the spout. A post and a cradle carry the condenser.
- New unpainted materials: COPPER, BRICK, IRON, WOOD. They use placeholder textures (`copper_block`, `bricks`, `anvil`,
  `spruce_planks`), and the drawings have their colours.
- Parts named for phase 3 animation (`ShapeRasterizer.marks`, box coordinates):
  - fired stills: `door`, `fire_bed`, `flue_top`;
  - LIQUID: `burner`;
  - ELECTRIC: `lamp`;
  - with a condenser: `spout`;
  - column: `reflux_valve`.

## Nozzles (frame, front −z; "pipe" = the pipe block beyond the face)

| Role | When | Face | Axis | Pipe block |
|---|---|---|---|---|
| CHARGE | always | (1.5, row, 0) | z− | (1, row−.5, −1) |
| DRAIN | always | (1.5, 0.5, 3) | z+ | (1, 0, 3) |
| FUEL | LIQUID | (0, 0.5, 1.5) | x− | (−1, 0, 1) |
| DISTILLATE | condenser | (3.5, 0.5, 0) | z− | (3, 0, −1) |
| WATER_INLET | condenser | (3.5, yk − 1, 0) | z− | (3, yk−1.5, −1) |
| WATER_OUTLET | condenser | (3.5, yk, 3) | z+ | (3, yk−.5, 3) |
| VENT | condenser | (3.5, yk + .5, 0.5) | y+ | (3, yk + .5, 0) |
| VAPOUR_OUTLET | no condenser | (4, yv, 1.5) | x+ | (4, yv−.5, 1) |

- The charge row is the lowest cell centre at least 0.42 m over the setting or skirt top. A pipe's largest neck then
  clears the coping.
- yk, the condenser row, is at least 0.5 m over the column or helmet top and at least 3.5 m (the water inlet a row
  under it keeps a free block from the spout). FUEL is the burner, at most
  a 50 mm line; its neck crosses the right column from the setting's wall (0.46 m at 2000 L, 0.9 m at 100 L).
- yv is the first cell centre over the helmet top.
- Each face is on its block face and on its pipe block's centre line. The pipe blocks are at least two blocks apart, and
  every neck clears the unit at a pipe's largest.
- Data gland (`EquipmentServices`, through `serviceHousing`): the electric still's only, on its terminal box in the right
  column's centre, x 0.5, y 0.5, facing the front; its FE gland on the box's back face. Each opens on a cell outside the
  shell (gate: `aStillsDataGlandSitsCentredOnItsBox`). A fired still has none.
- Checked before hand-back on the gates' own helpers (`tmp/still-structure/probe`, javac only, 176 shapes × 4 headings):
  free block, centre line, pipes apart, flanges, frame fill, neck room, faces, gland and marks.

## What the golden and the drawings should show

- `layouts.txt`:
  - gains 6 BATCH_STILL lines: 100, 1000 and 2000 L default, and three at 500 L (LIQUID + 8 stages, ELECTRIC without a
    condenser, SOLID + 2 stages);
  - every other line is unchanged, because the default still options are stripped from the printed shape.
- Corpus `shapes-11.snbt`: every kind's shape plus two stills with options. Shapes 1 to 10 read with the default options.
- Drawings (`-Only BATCH_STILL`): `BATCH_STILL` (500 L worm), `_SMALL` (100 L), `_LIQUID_COLUMN` (5 stages),
  `_ELECTRIC_VAPOUR`, `_LARGE_COLUMN` (2000 L, 8 stages).
  - Look for: copper pot and helmet, brick setting with door and flue, wooden tub with hoops; the column with portholes,
    the condenser over it, the reflux pot and the hand wheel.
  - Nozzles: CHARGE on the left, DRAIN and water out at the back, distillate, vent and fuel at the front.

## Owner questions

- **O1** A column needs the built-in condenser (gravity reflux from the still's own condenser). The other choice is a
  column feeding the player's condenser, with its reflux returned through a nozzle.
- **O2** The column still uses an overhead tube condenser, not the worm tub, so the reflux runs back by gravity. The
  worm tub stays for the plain pot still.
- **O3** The placeholder textures above; real copper, brick and iron art is open.
- **O4** The simulator tile uses the kettle symbol until W7 draws its own.
- **O5** The charge rises to row 2.5 from 750 L, so that a pipe's largest neck clears the coping.
- Phase 3 adds the option widgets on the constructor and their packet fields. Until then the constructor builds the
  default still.
