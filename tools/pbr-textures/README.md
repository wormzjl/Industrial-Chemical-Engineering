# pbr-textures

Writes the instrument wiring's tiled textures and their LabPBR 1.3 maps (batch 2026-11-10-custom-shader-system,
`CUSTOM_SHADER_SYSTEM_PLAN.md` section 4.4, W2). Python standard library only.

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
