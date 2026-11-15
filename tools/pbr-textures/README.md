# pbr-textures

Writes the instrument wiring's tiled textures and their LabPBR 1.3 maps (batch 2026-11-10-custom-shader-system,
`CUSTOM_SHADER_SYSTEM_PLAN.md` section 4.4, W2), and the equipment shells' relief sprites (batch
2026-11-15-equipment-visual-design, `RELIEF_SPRITES_PLAN.md` 4.1). Python standard library only.

## Run

Python in Git Bash is a stub; use the Codex runtime's interpreter, isolated (`-I`), from the repository root:

```
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/pbr-textures/wiring_textures.py tmp/fx-wiring
cp tmp/fx-wiring/branch*.png tmp/fx-wiring/tray_*.png src/main/resources/assets/icheme/textures/wiring/
```

The output is deterministic: the same script writes the same bytes. `WiringTexturesTest` (client/fx) checks the sizes and
channel ranges of the copies under `assets/icheme/textures/wiring/`.

## What it writes

All textures are 64 x 128 px. One image height is one 0.5 m period along the run (owner X1); u runs across the ribbon.

| File | Content |
|---|---|
| `branch.png` | u 0 to 0.5: the 20 mm branch, one 12 mm light-blue armoured IS cable on a galvanised strap, a 316L saddle per period. u 0.5 to 1: the sheath unrolled round the cable, for the drip-loop tube at each head (owner X9 A) |
| `tray_1.png` .. `tray_6.png` | the 25 mm baseline: a perforated galvanised channel with rolled lips, 1 to 6 cables on its floor, a black nylon tie per period (owner X10 A). Perforations are transparent |

Each has `_n` (R, G: normal X along +u and Y along +v, DirectX "Y-" as LabPBR asks; B: AO; A: height 1 to 255) and `_s`
(R: smoothness, sheath 0.35, galvanised 0.5, 316L 0.65, nylon 0.3; G: F0 10 for the dielectrics, 230 iron for every
steel part; B: porosity 0; A: emission 0). All maps come from one height field in millimetres; the albedo has no
painted relief.

## Equipment relief sprites (`equipment_textures.py`)

Detail too small for polygons (nuts, louvres, band straps) is relief from one height field per sprite, in millimetres
(owner, 2026-11-15: "Take advantage of the normal texture system to implement details without increasing polygon too
much"). Chunk-meshed shells cannot use the mod's shader, so each sprite comes in two variants (decision X5 A of
`2026-11-10-custom-shader-system`), and `EquipmentShellModel` picks one when it bakes a cell:

| File | Content | Used |
|---|---|---|
| `<name>.png` | albedo with the relief lit from the upper left and its crevices darkened | no shader pack, or `pbr_textures` off |
| `<name>_flat.png` | the same albedo without relief | a shader pack in use and `pbr_textures` on |
| `<name>_flat_n.png`, `<name>_flat_s.png` | LabPBR 1.3 normal (with AO and height) and specular of the flat albedo, encoded as the wiring's | the pack lights the flat albedo from them |

The baked sprite has no `_n`, so a pack never lights its relief twice. A baked sprite keeps the mean colour of the sprite
it replaced within 2 per channel, so paint tints and colours do not move; painted sprites stay neutral greys.

| Sprite | Size | Relief |
|---|---|---|
| `equipment_bolt_face` | 32 x 32, stretched over one nut (`Mesh.disc`, corners on u) | hex nut 1.6 d across its flats, 30 degree chamfer, countersink, stud end 0.4 d proud; drawn for M20, every dimension in d |
| `motor_louvre` | 64 x 64, 1 m, tiled | 16 blades a metre, each face sloping out to its lip, the dark slot under it; neutral grey (body paint) |
| `equipment_cladding`, `painted_cladding` | 64 x 64, 2 m, tiled | today's jacketing laps, twice the texels; a band strap every metre (rows 0 and 32), drawn 2.5 mm proud |

The tool also ports the old generators of these sprites exactly (`VesselTextures.cladding`, `paintedCladding`,
`PumpSprites.boltFace`, removed from those tools on 2026-11-15), so a baked sprite equals today's pixels outside its new
relief. Run, from the repository root:

```
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/pbr-textures/equipment_textures.py tmp/relief-sprites/sprites
cp tmp/relief-sprites/sprites/*.png src/main/resources/assets/icheme/textures/block/
```

`EquipmentTexturesTest` (client/fx) checks sizes, channel ranges, the absent `_n` beside a baked sprite, the mean
colours, the neutral painted sprites and the shell model's `<material>_flat` keys. The shell seams are gone from
`vessel_shell`, `painted_shell`, `vessel_head` and `painted_head` (`VesselTextures`, owner 2026-11-15: no weld seams);
those sprites have no relief and no flat variant.
