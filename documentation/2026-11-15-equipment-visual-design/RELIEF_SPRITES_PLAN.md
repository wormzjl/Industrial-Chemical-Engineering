# Relief sprites for equipment shells (plan)

Date: 2026-11-15. Batch `2026-11-15-equipment-visual-design`; findings P1, P4, P5, G2 and G5 of
[VISUAL_REVIEW.md](VISUAL_REVIEW.md). Status: built 2026-11-15 on `claude/relief-sprites-10dd59` (R-W1 to R-W3; build notes in section 9), not merged. It runs beside the steam turbine build
([STEAM_TURBINE_SECTIONS_PLAN.md](STEAM_TURBINE_SECTIONS_PLAN.md)).

## 1. Owner rules this rests on

- 2026-11-15: "Take advantage of the normal texture system to implement details without increasing polygon too much,
  keep performance in mind and decrease poly when you can".
- 2026-11-15: no weld seams; insulation band straps stay.
- 2026-11-10, decision X5 A of `2026-11-10-custom-shader-system`: a chunk sprite carries its relief baked into the
  albedo without a shader pack; while a pack is in use the model swaps to a flat albedo with unshaded vertex colour,
  and the pack lights it from `_n` and `_s`. The quad cache keys on the variant.

## 2. Expected behaviour

A bolt head, a strap, a louvre bar or a fin is a few millimetres of relief on a flat face. At the distances equipment
is seen from, its shading shows and its outline does not. One height field gives that shading: baked into the colour
for the game's block shader, or as a normal map for a shader pack. A relief face costs no polygons beyond the face.

## 3. What the code does now

- `Mesh.bolts` (line 640) and `Mesh.nut` (line 707) draw each nut as a closed hexagonal prism at normal detail, 18
  polygons, and as one flush hexagon of `Material.BOLT_FACE` at low detail. 46 call sites in 21 files, pipes and
  fittings among them (`LineGeometry`, `ValveGeometry`).
- Bolts are the largest material on 18 of the 61 reference meshes: `CABIN_HEATER` 4,318 of 12,118, `LIQUID_RING_PUMP`
  920 of 1,544, both exchangers 768 of 1,940.
- `EquipmentShellModel` holds one sprite per `Mesh.Material` (`sprites`, line 189), bakes the directional shade and
  the sheen into the vertex colour (`shade`, line 223) and caches quads by `CellKey` (`quads`, line 61). It knows
  nothing of shader packs. `FxCompat.iris().packInUse()` and `ClientConfig.pbrTextures()` exist and serve the wiring.
- `tools/pbr-textures/wiring_textures.py` writes the wiring's albedo, `_n` and `_s` from one height field. No block
  sprite has `_n` or `_s`.
- `vessel_shell.png` draws plate courses with seams (`tools/custom-equipment-rendering-poc/textures/VesselTextures.java`).
  `painted_shell.png` has one faint horizontal line.
- The large motor and generator draw their cooler openings as `Material.OPENING` boxes (`ProcessUnit`, lines 1726 and
  1744): three flat black rectangles each.

## 4. Design

### 4.1 The sprite tool

`tools/pbr-textures/equipment_textures.py`, standard library only, deterministic, beside `wiring_textures.py`. For
each relief sprite it writes four files from one height field in millimetres:

| File | Content | Used |
|---|---|---|
| `<name>.png` | albedo with the relief lit from the upper left and its crevices darkened | no shader pack |
| `<name>_flat.png` | albedo without relief | shader pack in use |
| `<name>_flat_n.png`, `<name>_flat_s.png` | LabPBR 1.3 normal (with AO and height) and specular, as the wiring's | shader pack in use |

The baked sprite gets no `_n`, so a pack cannot light its relief twice.

### 4.2 The model's two variants

- `EquipmentShellModel` keeps two sprite arrays: baked (today's keys) and flat (texture key `<material>_flat` in the
  shell model JSON, falling back to the baked sprite where a material has none).
- The variant is flat when `ClientConfig.pbrTextures()` and `FxCompat.iris().packInUse()`, read when a cell is baked.
  The quad cache keys on (variant, `CellKey`).
- Flat variant: vertex colour 255 (no directional shade, no sheen), `setShade(true)` so the pack shades by its own
  normals. Baked variant: exactly today's path.
- `MachineAnimation`, `EquipmentBodyRenderer`, `TankRenderer` and `InstrumentRenderer` read sprites through
  `EquipmentShellModel.sprite`: it returns the current variant's sprite.

### 4.3 First relief sprites

| Sprite | Relief | Replaces |
|---|---|---|
| `equipment_bolt_face` | one nut and stud end, 1.2 d high | the nut prism at normal detail: `Mesh.bolts` and `Mesh.nut` draw the flush face at every detail. Nuts at least `SOLID_NUT` across their flats stay prisms (R2) |
| `motor_louvre` (new `Material.MOTOR_LOUVRE`, body paint) | louvre bars | the `OPENING` boxes of the large motor and generator |
| `equipment_cladding`, `painted_cladding` | a band strap every metre, the panel joints as they are | nothing; adds the straps |
| `vessel_shell`, `painted_shell` | none: the plate courses and the line go | the seams |

`Mesh.Material` gains constants only at its end; nothing saves a material's ordinal.

### 4.4 What does not change

Layout rows, collision solids, nozzles, saves, wire formats, lang files. A layout row that moves is a defect of the
build.

## 5. For the owner

| Id | Question | Options | Recommended |
|---|---|---|---|
| R1 | Do pipe and fitting flanges take the relief nuts too? | A: every flange, one rule in `Mesh.bolts`. B: equipment only, pipes keep their prisms | A: pipes are the most numerous flanges. It changes the look of every laid line, which the owner asked bolts for on 2026-10-16 |
| R2 | From what size does a nut stay a solid? | The build counts polygons at 40, 60 and 80 mm across the flats | 60 mm, unless the counts say otherwise |
| R3 | The shader pack variant cannot be judged without a client | An owner-run check with one pack after the build | Offer it; do not start it |

## 6. Work packages

**R-W1 Tool and sprites** (`tools/pbr-textures/`, `assets/icheme/textures/block/`, the shell model JSON,
`tools/custom-equipment-rendering-poc/textures/VesselTextures.java`, `tools/INDEX.md`)

- The tool of 4.1 and the sprites of 4.3. A test beside `WiringTexturesTest` checks sizes and channel ranges.
- Proof: a contact sheet of every changed sprite, before and after, in `tmp/relief-sprites/`.

**R-W2 Model variants** (`client/model/EquipmentShellModel.java`, `EquipmentClient.renderConfigReloaded`)

- 4.2. Unit-test what has no Minecraft class: the variant choice and the cache key. `PolygonBakeTest` must stay bit
  for bit on the baked variant.

**R-W3 Nuts, louvres** (`science/equipment/Mesh.java`, `ProcessUnit.java` lines 1726 and 1744)

- 4.3. Tests: `LineGeometryTest`, `PipelineFittingTest`, `ProcessUnitTest`, `RenderDetailTest`, `ShapeRasterizerTest`,
  `EquipmentLayoutGoldenTest` (expected: no row moves).
- Drawings: `update.ps1 -Only EXCHANGER,KETTLE,PUMP,MOTOR_LARGE,GENERATOR_LARGE,CABIN_HEATER`, with the sprite
  close-ups (`--sprites`). Polygon counts by material before and after, for all 61 meshes.

## 7. Pitfalls

- The steam turbine thread owns `SteamTurbineDesign.java`, `SteamTurbineTrain.java`, `MachineDuty.java`,
  `EngineeringExport.java`, `layouts.txt`, `en_us.json`, `FORMATS.md` and the batch README. Do not edit them. Use the
  drawing scenes that exist.
- `LineGeometryTest` found a flange's inside seen from outside without its seat. Keep the seat.
- The flush face sits 0.4 mm proud (`.0004`), as low detail does, or it fights the flange face in depth.
- A nut face seen edge-on vanishes. That is R2's reason.
- Models bake on chunk-meshing workers. Read the variant once per bake, not per quad.
- Whether a pack toggle re-meshes chunks is not known (shader plan, section 7). If it does not, the old variant stays
  until F3+T; say so in the report.
- The sheen and the baked shade must stay exactly as they are on the baked variant: `PaintTest` and the owner's
  2026-10-16 banding report rest on them.

## 8. Done

- The three packages pass their named tests.
- The report holds the polygon table, the sprite sheets, the views, R2's counts and anything the plan had wrong.
- `documentation/reference/code-guide/client-rendering.md` and `effects.md` describe the two variants and the tool.

## 9. Build notes (2026-11-15)

Commits on `claude/relief-sprites-10dd59`: R-W1 `291967979`, R-W2 `ada5afb51`, R-W3 `ca884d357`. R1 A, R2 60 mm and
R3 (no client) as recommended; each is one constant (`Mesh.RELIEF_NUTS_ON_LINES`, `Mesh.SOLID_NUT`).

Where the build differs from sections 3 to 6:

- The plan's route (`Mesh.bolts`, `Mesh.nut`) does not reach the largest bolt loads. The fired heaters' casing-joint
  nuts were 50 mm boxes (`ProcessUnit.joint`, `seam`: 4,318 of `CABIN_HEATER`'s polygons) and a pump cover's recessed
  nuts were prisms inside `Mesh.disc`. Both now go through `Mesh.nut` and its size rule.
- `vessel_head` and `painted_head` lose their tangent-line weld as well (the owner's no-weld rule); the plan named only
  the shells.
- `PumpSprites.boltFace` and `VesselTextures.cladding` / `paintedCladding` moved into `equipment_textures.py`, ported bit
  for bit, so the old tools no longer overwrite the relief sprites. The cladding sprites are 64 x 64 now (each old
  texel two by two) so a strap has an edge.
- Four tests encoded the old look: `ShapeRasterizerTest` (a drum nozzle's nuts are `BOLT_FACE`), `LineGeometryTest`
  (the watertight check skips the nut decals), `RenderDetailTest` (low detail is never costlier, not always cheaper;
  a new case pins `SOLID_NUT`), `PaintTest` (`MOTOR_LOUVRE` is body paint).
- The louvre panels keep their 10 mm boxes but leave out the hidden back face.

Polygons of the 81 drawing references (EngineeringExport, NORMAL), before 304,279 (bolts 45,864):

| SOLID_NUT | Total | Bolts (BOLT + BOLT_FACE) | Meshes changed |
|---|---|---|---|
| 40 mm | 296,639 | 38,236 | 54 |
| 60 mm (built) | 275,807 | 17,404 | 70 |
| 80 mm | 270,767 | 12,364 | 72 |

At 60 mm the 1.5 in studs (61 mm nuts) of lines from 32 in up stay prisms: the exchangers keep 432 bolt polygons,
which 80 mm takes to 96. No mesh grew; no layout row moved.

Open: the baked bolt face keeps the old low-detail sprite's mean grey (122.6) as the brief asked, so normal-detail nuts
read paler than the black prisms they replace (about 68); `BOLT_FACE` is unpainted, so a darker mean moves no paint.
Nuts edge-on vanish, as section 7 expected. The shader-pack variant and whether a pack toggle re-meshes chunks need the
owner's client check (R3).
